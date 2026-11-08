# Python 채팅 연결 계약 초안

이 문서는 **실제 AI 파트 서버와 합의한 규격이 아니다.** Notion AI 페이지에서 채팅 endpoint·SSE 규격을 확인하지 못해 로컬 HTTP fixture와 구현을 맞춘 초안이다. 실제 Python·GPT로 시연한 뒤 이슈 #7 완료 여부를 판단한다.

## 연결

- Chat: Spring MVC, 포트 8082. `WebClient`는 Python HTTP 클라이언트로만 사용한다.
- `CHAT_AI_BASE_URL`을 지정하면 `POST /v1/chat/stream` 호출. 미설정 시 접수 전에 JSON 503 CHAT_AI_NOT_CONFIGURED.
- 내부 인증이 필요한 서버는 선택 변수 `CHAT_AI_TOKEN`을 Bearer로 전달한다. 실제 값·파일은 공유하거나 추적하지 않는다.
- Python의 GPT·검색·출처 생성은 AI 파트 담당이다. Java는 허용 범위·입출력·저장·앱용 SSE를 담당한다.

```json
{
  "schemaVersion": "1.0",
  "requestId": "11111111-1111-4111-8111-111111111111",
  "conversationId": "22222222-2222-4222-8222-222222222222",
  "ownerId": "demo-user",
  "question": "RAG 검색 과정을 설명해 줘",
  "history": [
    {"role": "USER", "content": "이 영상의 핵심은 뭐야?"},
    {"role": "ASSISTANT", "content": "저장된 자막을 검색해 답변하는 과정입니다."}
  ],
  "records": [
    {
      "recordId": "33333333-3333-4333-8333-333333333333",
      "analysisId": "44444444-4444-4444-8444-444444444444"
    }
  ]
}
```

`ownerId`·records는 Spring이 소유권을 확인한 값이다. Python도 검색 시 `analysis_records.owner_id`, 현재 기록 존재 여부, 전달된 recordId·analysisId를 모두 확인해야 한다. 삭제·타인·범위 밖 기록을 검색이나 프롬프트에 포함하지 않는다. 이미 진행 중인 모델 요청에서 삭제 직전 전송된 문맥까지 회수할 수는 없다.

history는 같은 방의 최근 완료 질문·답변 최대5쌍, 합계32000 UTF-16 문자 이내다. 쌍 전체가 남은 길이를 넘으면 생략한다. 실패·진행 중 답변과 현재 범위 밖·삭제 출처가 연결된 답변은 전송하지 않는다. 기존 앱 대화는 삭제하지 않으며 Python에 보낼 문맥만 제한한다. history는 질문 의도 파악용이고 새 답변 근거는 현재 records의 원문에서 찾는다. 시스템 지시로 취급하지 않는다.

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

출처는 해당 소유자·처음 허용 범위·완료 시 현재 범위·현재 기록·chunk와 section의 연결을 확인한다. 제목·영상 ID·분석일·시간 범위는 DB에서 만들고 chunk와 section의 시간 교집합을 사용한다. 최종 본문·출처·상태를 한 TX에 저장한 뒤 저장 조회 API와 같은 sources·done을 앱에 전송한다.

## 실행·실패 경계

```text
앱 POST
→ begin() TX: USER·ASSISTANT·키 저장
→ MVC 전용 스레드: WebClient SSE 읽기 / 앱 전송
→ checkpoint() TX: 부분 텍스트 저장
→ complete() TX: 출처 확인·최종 저장
→ sources / done
```

Netty 이벤트 루프에서 JPA나 앱 쓰기를 실행하지 않는다. MVC의 별도 제한된 실행 스레드가 스트림을 읽는다. 접수 전 인스턴스 동시 스트림 4개를 제한하고, 방별 중복은 DB에서 별도로 막는다. 같은 질문의 내부 자동 재시도는 수행하지 않는다.

기본 보호 값은 ChatLimits 한곳에서 관리한다: 질문4000자, 답변65536자, 출처20개, 연결3초, 이벤트 공백30초, 전체120초, MVC125초, DB lease150초. 문자 수는 UTF-16 기준이다. 모델·비용 정책은 AI 파트와 조정한다.

부분 답변은 최초 조각과 이후 약250ms 간격으로 저장한다. 종료·오류에는 마지막 누적 텍스트를 저장한다. 강제 종료 직전의 미저장 조각은 잃을 수 있다. 로컬 Chat의 10초 스케줄러가 만료 lease를 FAILED·INCOMPLETE로 회수하며, 토큰이 다른 늦은 저장은 무시한다. Cloud Run 최소 인스턴스0 운영에서 정기 복구가 필요하면 별도 Scheduler 호출을 연결한다.

네트워크 종료 감지는 앱으로 쓰는 시점 또는 upstream/전체 시간 제한 시점이다. 완료 직후 앱 전송이 실패해도 저장된 COMPLETED를 실패로 되돌리지 않는다. 재조회로 복구한다. 이벤트 재생·Last-Event-ID 재개는 제공하지 않는다.

검증: ChatStreamApiTest가 로컬 HTTP fixture로 분할 UTF-8·순차 이벤트·DB 저장·근거 부족·잘못된 출처·EOF·JSON 오류를 검사한다. 실제 AI의 답변 품질과 RAG 검색 동작을 증명하는 테스트는 아니다.

추가 실패 검증은 실제 30초 upstream idle timeout, 생성 중 조회·추가 질문/삭제 차단, 생성 중 기록 삭제, 잘못된 JSON을 포함한다. 앱 쓰기 실패는 IOException을 던지는 출력 스트림으로 재현해 부분 답변 보존과 완료 후 상태 보존을 확인한다. 실제 모바일 네트워크 종료 시연과는 구분한다.
