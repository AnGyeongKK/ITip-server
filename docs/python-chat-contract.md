# Python 답변 생성 연결 계약 초안 (2.0)

이 문서는 **실제 AI 파트 서버와 합의한 규격이 아니다.** Notion AI 페이지에서 채팅 endpoint·SSE 규격을 확인하지 못해 로컬 HTTP fixture와 구현을 맞춘 초안이다. 실제 Python·GPT로 시연한 뒤 이슈 #7 완료 여부를 판단한다.

## 연결

- Chat: Spring MVC, 포트 8082. `WebClient`는 Python HTTP 클라이언트로만 사용한다.
- `CHAT_AI_BASE_URL`을 지정하면 `POST /v1/chat/stream` 호출. 미설정 시 접수 전에 JSON 503 CHAT_AI_NOT_CONFIGURED.
- 내부 인증이 필요한 서버는 선택 변수 `CHAT_AI_TOKEN`을 Bearer로 전달한다. 실제 값·파일은 공유하거나 추적하지 않는다.
- Spring은 질문 임베딩·pgvector 검색·원문/출처 전달을 담당한다. Python은 받은 contexts로 프롬프트·GPT 답변·인용 선택을 담당한다. Python의 DB 접속·재검색은 필요 없다.
- 내부 1.0의 records 요청을 2.0의 contexts로 교체한다. Python과 함께 배포해야 하며, 공개 앱 API·SSE 형식은 유지한다.

```json
{
  "schemaVersion": "2.0",
  "requestId": "11111111-1111-4111-8111-111111111111",
  "conversationId": "22222222-2222-4222-8222-222222222222",
  "ownerId": "demo-user",
  "question": "RAG 검색 과정을 설명해 줘",
  "history": [
    {"role": "USER", "content": "이 영상의 핵심은 뭐야?"},
    {"role": "ASSISTANT", "content": "저장된 자막을 검색해 답변하는 과정입니다."}
  ],
  "contexts": [
    {
      "recordId": "33333333-3333-4333-8333-333333333333",
      "analysisId": "44444444-4444-4444-8444-444444444444",
      "chunkId": "55555555-5555-4555-8555-555555555555",
      "sectionIds": ["66666666-6666-4666-8666-666666666666"],
      "videoId": "dQw4w9WgXcQ",
      "title": "RAG 검색 흐름 이해",
      "text": "검색한 원문을 질문과 함께 모델에 제공해 답변을 생성합니다.",
      "startMs": 0,
      "endMs": 1000
    }
  ]
}
```

`contexts`는 Spring이 현재 사용자·질문 범위·기록 존재·완료 상태·임베딩 모델/차원을 제한하고 검색한 자막 원문이다. Python은 이 원문과 sectionIds 중에서만 인용을 선택한다. 모델이 ID·영상·시간을 새로 만들거나 history를 새 근거로 인용하지 않는다. 자막과 history의 지시문은 실행할 명령이 아닌 비신뢰 데이터로 구분한다.

contexts는 기본 최대8개(`CHAT_RETRIEVAL_TOP_K`, 1~20), 원문 합계24000 UTF-16 문자다. 원문은 중간에서 자르지 않으며 남은 길이를 넘는 chunk는 생략한다. 검색 후보가 없으면 빈 배열을 보내고 Python은 안내 텍스트·빈 sources·INSUFFICIENT_EVIDENCE로 답한다. 현재는 유사도 임계값 없이 가까운 순서로 검색하며, 근거가 충분하지 않으면 답을 추측하지 않는다. 이 기본값은 품질 측정 결과로 조정한다.

Spring은 임베딩 후 범위·history를 다시 읽고 SQL에서 현재 기록을 확인한다. 완료 직전에도 인용을 다시 확인한다. 이미 Python에 전송한 뒤 삭제된 문맥은 회수할 수 없으며, 삭제된 인용으로 최종 완료하지 않는다.

history는 같은 방의 최근 완료 질문·답변 최대5쌍, 합계32000 UTF-16 문자 이내다. 쌍 전체가 남은 길이를 넘으면 생략한다. 실패·진행 중 답변과 현재 범위 밖·삭제 출처가 연결된 답변은 전송하지 않는다. 기존 앱 대화는 삭제하지 않으며 Python에 보낼 문맥만 제한한다. history는 질문 의도 파악용이고 새 답변 근거는 전달된 contexts의 원문에서만 찾는다. 시스템 지시로 취급하지 않는다.

## Python → Spring SSE

UTF-8 `text/event-stream`. `delta` 0회 이상 → `sources` 1회 → `done` 1회 순서다. 네트워크 조각과 이벤트 경계는 다르며 Spring 기본 SSE decoder가 조립한다. 주석 heartbeat는 허용한다.

```text
event: delta
data: {"text":"검색한 자막을 근거로 "}

event: delta
data: {"text":"답변을 생성합니다."}

event: sources
data: {"items":[{"recordId":"33333333-3333-4333-8333-333333333333","chunkId":"55555555-5555-4555-8555-555555555555","sectionId":"66666666-6666-4666-8666-666666666666"}]}

event: done
data: {"finishReason":"STOP"}

```

finishReason은 STOP·LENGTH·INSUFFICIENT_EVIDENCE다. 근거 부족은 명시적인 안내 텍스트, items 빈 배열, INSUFFICIENT_EVIDENCE로 정상 종료한다. STOP·LENGTH에는 실제 출처가 필요하다. EOF만으로 완료를 추측하지 않는다. Python의 error·비정상 HTTP·JSON 손상은 안전한 앱 오류로 변환하며 외부 에러 본문을 전달하지 않는다.

출처는 실제 전달된 context의 recordId/chunkId/sectionId 조합과 해당 소유자·처음 허용 범위·완료 시 현재 범위·현재 기록·chunk와 section의 연결을 확인한다. 제목·영상 ID·분석일·시간 범위는 DB에서 만들고 chunk와 section의 시간 교집합을 사용한다. 최종 본문·출처·상태를 한 TX에 저장한 뒤 저장 조회 API와 같은 sources·done을 앱에 전송한다.

최종 출처 SQL은 recordId 순서로 해당 analysis_records에 FOR SHARE 잠금을 잡아 완료 TX까지 유지한다. 확인 직후 삭제가 끼어드는 것을 막고, 삭제는 완료 commit 뒤 실행된다. 이후 조회에서는 과거 출처를 DELETED로 표시한다. 이 잠금은 AI 대기·전체 SSE 동안 유지하지 않는다.

## 실행·실패 경계

```text
앱 POST
→ begin() TX: USER·ASSISTANT·키 저장
→ MVC 전용 스레드: 질문 임베딩(외부 호출, DB TX 없음)
→ refresh() TX: 현재 범위·안전한 history 재확인
→ ChatContextQuery.search(): pgvector 검색(최대3초)
→ Python: question + contexts + history로 생성
→ MVC 전용 스레드: WebClient SSE 읽기 / 앱 전송
→ checkpoint() TX: 부분 텍스트 저장
→ complete() TX: 출처 확인·최종 저장
→ sources / done
```

Netty 이벤트 루프에서 JPA나 앱 쓰기를 실행하지 않는다. MVC의 별도 제한된 실행 스레드가 스트림을 읽는다. 접수 전 인스턴스 동시 스트림 4개를 제한하고, 방별 중복은 DB에서 별도로 막는다. 같은 질문의 내부 자동 재시도는 수행하지 않는다.

Spring 7.0.6 이후 callback의 출력 스트림은 flush를 전달하지 않을 수 있다. ChatStreamController는 HTTP 경계에서 실제 servlet 출력 스트림을 사용해 이벤트마다 네트워크로 전송한다. ChatHttpStreamingTest는 실제 포트에서 upstream을 기다리게 한 상태로 첫 delta를 수신하고, 아직 GENERATING인 부분 답변을 DB에서 확인한 뒤 완료를 허용한다. MockMvc의 최종 응답 문자열 검사만으로 이 동작을 증명하지 않는다. [현재 의존성의 응답 스트림 구현](https://github.com/spring-projects/spring-framework/blob/v7.0.10/spring-web/src/main/java/org/springframework/http/server/ServletServerHttpResponse.java)을 참고한다.

기본 보호 값은 ChatLimits 한곳에서 관리한다: 질문4000자, 답변65536자, 출처20개, 임베딩10초, 검색SQL3초, Python 연결3초, 이벤트 공백30초, Python 전체120초, MVC140초, DB lease160초. 문자 수는 UTF-16 기준이다. 모델·비용 정책은 AI 파트와 조정한다.

부분 답변은 최초 조각과 이후 약250ms 간격으로 저장한다. 종료·오류에는 마지막 누적 텍스트를 저장한다. 강제 종료 직전의 미저장 조각은 잃을 수 있다. 로컬 Chat의 10초 스케줄러가 만료 lease를 FAILED·INCOMPLETE로 회수하며, 토큰이 다른 늦은 저장은 무시한다. Cloud Run 최소 인스턴스0 운영에서 정기 복구가 필요하면 별도 Scheduler 호출을 연결한다.

네트워크 종료 감지는 앱으로 쓰는 시점 또는 upstream/전체 시간 제한 시점이다. 완료 직후 앱 전송이 실패해도 저장된 COMPLETED를 실패로 되돌리지 않는다. 재조회로 복구한다. 이벤트 재생·Last-Event-ID 재개는 제공하지 않는다.

DB 장애로 실패 상태도 저장할 수 없으면 저장됐다고 가정한 종료 이벤트를 보내지 않고 연결을 닫는다. DB 복구 뒤 상태 조회·lease 회수로 확인한다. 이미 시작한 SSE 뒤에 공통 JSON 오류가 섞이지 않게 하고 로그에는 메시지 ID·예외 타입만 남긴다.

검증: ChatStreamApiTest가 로컬 HTTP fixture로 분할 UTF-8·순차 이벤트·DB 저장·근거 부족·잘못된 출처·EOF·JSON 오류를 검사한다. 실제 pgvector 검색·권한 필터·모델/차원 분리와 Python 전달 원문을 검사하지만, 임베딩 모델 응답은 fixture다. 실제 모델의 의미 검색·답변 품질을 증명하지 않는다.

추가 실패 검증은 실제 30초 upstream idle timeout, 생성 중 조회·추가 질문/삭제 차단, 생성 중 기록 삭제, 잘못된 JSON을 포함한다. 앱 쓰기 실패는 IOException을 던지는 출력 스트림으로 재현해 부분 답변 보존과 완료 후 상태 보존을 확인한다. 실제 모바일 네트워크 종료 시연과는 구분한다.

## 임베딩 공급자 연결

Chat과 Worker에 각각 유일한 Spring AI `EmbeddingModel` Bean이 필요하다. 현재 저장소는 공급자 SDK·실제 Bean을 제공하지 않는다. 이번 변경도 특정 공급자·모델을 임의로 선택하지 않는다. Python 주소만 지정하면 질문을 접수할 수 있는 구성이 아니며, Bean이 없거나 하나로 결정되지 않으면 접수 전에 JSON 503 CHAT_AI_NOT_CONFIGURED를 반환한다.

모델 응답 metadata.model을 저장 벡터와 비교한다. 응답에 모델 이름이 없을 때만 `ANALYSIS_EMBEDDING_MODEL_ID`를 공통 fallback으로 사용한다. 이것은 공급자의 실제 모델 선택 설정이 아니다. Worker와 Chat의 실제 모델·버전·차원·입력 전처리(질문/문서 prefix 등)를 함께 맞춰야 한다. 다른 모델의 저장 벡터는 검색하지 않는다.

임베딩 대기는 최대10초이며 같은 질문을 자동 재시도하지 않는다. interrupt를 무시하는 공급자도 최대4개 호출까지만 허용하고 대기열을 쌓지 않는다. 공급자 HTTP 연결·읽기 타임아웃도 이 예산 내로 설정한다. 임베딩 실패는 Python을 호출하지 않고 CHAT_RETRIEVAL_FAILED·FAILED로 저장한다. 잘못된 벡터·비일시적 공급자 거절·HTTP4xx(408/429 제외)·미분류 오류는 retryable=false, 시간 초과·통신 오류·408/429/5xx·일시적 공급자 오류는 retryable=true다. SQL·DB 연결 실패는 기존 CHAT_STREAM_FAILED 경로를 사용한다. 실제 SDK 연결 시 공급자별 예외가 이 분류에 맞는지 확인한다.

향후 품질 개선 항목과 측정 기준은 [채팅 계약](chat-contract.md#답변-품질-개선-계획)을 따른다.
