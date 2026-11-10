# Spring AI 채팅 직접 생성 계약

[AI-RAG 논의의 현재 결정](https://www.notion.so/41346c2fafdf8017b61af4e271aa67a6)을 구현한 내부 모델 계약이다. 공개 앱 API는 [채팅 계약](chat-contract.md)을 따른다. 이전 Python HTTP2.0이나 영상 분석의 AnalysisOutput/schemaVersion1.0과 별개다.

## 실행·역할

```text
Worker: 자막 원문 → chunk·임베딩 → analysis_chunks
Chat: 접수 TX → 질문 임베딩 → 현재 범위/history 재확인 → pgvector 검색
    → 프롬프트 예산 조정 → Spring AI ChatModel → OpenAI SDK 스트림
    → answer만 부분 저장·delta → 종료/출처 검증 → 완료 TX → sources/done
```

- Spring Chat은 기본8082, Spring MVC·JPA다. AI 대기와 전체 SSE를 DB 트랜잭션으로 묶지 않는다.
- AI 파트는 프롬프트·모델/옵션·출력 Schema·정상/근거 부족/실패 예시·평가 질문과 정답 근거를 제공한다. Python은 실험에 사용할 수 있으며 실행용 채팅 서버는 필요 없다.
- 분석용 ChatModel은 Worker의 별도 역할이다. 이번 채팅 변경은 분석 입력/출력·마인드맵 매핑을 바꾸지 않는다. Worker 분석 모델의 실제 설정은 남아 있다.

## 프롬프트 입력

고정 지시문은 `apps/chat-app/src/main/resources/prompts/rag-system.txt`의 SystemMessage, 아래 값은 JSON 문자열 UserMessage다. 템플릿을 사용하지 않아 중괄호를 변수로 해석하지 않는다. history와 자막은 비신뢰 데이터이며 system 지시로 합치지 않는다.

현재 지시문은 Notion 제안으로 만든 초안이다. AI 파트의 최종 프롬프트·모델 옵션·Schema·평가 사례와 실제 모델로 확인해야 한다.

```json
{
  "question": "RAG는 어떻게 답변을 만들어?",
  "contexts": [
    {
      "contextId": "C1",
      "sectionIds": ["66666666-6666-4666-8666-666666666666"],
      "title": "RAG 검색 흐름 이해",
      "text": "질문과 관련된 원문을 검색한 뒤 그 내용을 근거로 답변합니다.",
      "startMs": 0,
      "endMs": 10000
    }
  ],
  "history": [{"role":"USER","content":"이 영상의 주제는 뭐야?"},
              {"role":"ASSISTANT","content":"RAG의 기본 흐름입니다."}]
}
```

contextId는 이번 요청의 라벨이다. 서버가 실제 recordId/analysisId/chunkId와 연결한다. 사용자·대화·기록 ID를 모델 입력에 추가하지 않는다. 모델은 전달한 sectionIds 중에서만 인용한다. 시간 단위는 ms다.

## 검색·토큰 예산

- 기존 analysis_chunks의 코사인 정확 검색이다. 현재 소유자·존재하는 기록·질문 범위·완료·모델/차원을 SQL에서 제한한 뒤 topK를 선택한다. 기본8, 범위1~20, 원문 합계24000 UTF-16 문자다.
- history는 현재 사용 가능한 완료 대화 최대5쌍·32000 UTF-16 문자다. 삭제/범위 밖 출처가 붙은 과거 답변은 새 프롬프트에서 제외한다. 앱의 과거 기록은 보존한다.
- `ChatAnswerGenerator.prepare()`는 명시한 JTokkit encoding으로 system·JSON 입력·출력 Schema를 세고 봉투 여유128토큰을 포함해 추정한다. 예산을 넘으면 오래된 대화 쌍부터, 이후 후순위 원문을 통째로 제외한다. 질문이나 원문을 중간에서 자르지 않는다.
- 실제 전송한 원문만 인용 후보로 남긴다. 필수 입력만으로도 예산을 넘으면 CHAT_PROMPT_TOO_LARGE로 실패한다. 전체 context 용량은 input budget + output cap + 여유256 이상이어야 한다. 토큰 수는 추정치이며 모델·encoding·실제 사용량을 확인해야 한다.

## 구현된 모델 출력 Schema

AI 파트와 실제 모델로 확인할 내부 출력이다. OpenAI Chat Completions의 strict JSON Schema를 사용하며 첫 필드는 answer 문자열이다. `ChatAnswerGenerator.SCHEMA`가 공급자에 전달하는 기준이다.

```json
{
  "answer": "RAG는 관련 원문을 검색하고 그 내용을 근거로 답변을 생성해요.",
  "citations": [{"contextId":"C1","sectionId":"66666666-6666-4666-8666-666666666666"}],
  "evidenceStatus": "SUPPORTED"
}
```

```json
{
  "answer": "검색된 자막만으로는 이 질문에 답할 근거가 부족해요.",
  "citations": [],
  "evidenceStatus": "INSUFFICIENT_EVIDENCE"
}
```

SUPPORTED는 실제 인용이 필요하고 INSUFFICIENT_EVIDENCE는 인용이 비어 있어야 한다. 본문은 공백일 수 없으며 인용은 최대20개다. 실제 전송 context/section, 완료 시점의 현재 소유권·삭제·범위·시간을 확인한다. 모델의 제목·시간·기록 ID를 신뢰해 저장하지 않는다.

## 스트리밍·저장

`ChatModel.stream()`의 전체 ChatResponse를 사용한다. `AnswerJsonStream`이 answer 문자열의 escape·Unicode를 순차 해제하므로 JSON 구문·citations·evidenceStatus가 앱 본문에 섞이지 않는다. 본문은 첫 조각과 이후 약250ms 간격으로 저장한다.

| 공급자·출력 상태 | 서버 처리 |
| --- | --- |
| 실제 STOP + 완전한 JSON + 유효한 근거 | COMPLETED, finishReason STOP |
| 실제 STOP + 근거 부족 안내 + 빈 인용 | COMPLETED, finishReason INSUFFICIENT_EVIDENCE |
| LENGTH | CHAT_ANSWER_TOO_LONG, 부분 본문이 있으면 INCOMPLETE, 없으면 FAILED. 재시도 불가 |
| refusal/content filter | CHAT_AI_REFUSED, 완료 처리하지 않음. 공급자 거절 본문은 노출하지 않음 |
| 잘린/중복 필드/손상 JSON·잘못된 인용 | CHAT_INVALID_AI_RESPONSE, 완료 처리하지 않음 |
| 공급자 종료 metadata 없는 EOF·통신/시간 초과 | CHAT_STREAM_FAILED, 저장한 부분 본문 보존 |

모델이 본문에 STOP이라고 쓰는 것은 종료 근거가 아니다. 정상 공급자 종료를 확인한 뒤 본문·출처·COMPLETED를 한 TX에 저장하고 `sources → done`을 전송한다. 완료 후 앱 쓰기 실패로 COMPLETED를 되돌리지 않는다. 실패 상태도 저장할 수 없는 DB 장애에서는 종료 이벤트를 꾸미지 않고 연결을 닫는다.

앱 공개 이벤트는 기존 meta/delta/sources/done, 실패는 error다. SSE는 ApiResponse로 감싸지 않는다. 새 질문은 완료 뒤 보내며 같은 키는 중복409, 재시도는 새 clientRequestId와 retryOfMessageId를 사용한다.

## 공급자·실행 설정

| 변수 | 용도·기본값 |
| --- | --- |
| `OPENAI_API_KEY` | 각자 로컬/실행 환경에 주입. 파일·Git·로그·PR에 값을 기록하지 않음 |
| `OPENAI_BASE_URL` | 기본 https://api.openai.com/v1. 테스트만 로컬 HTTP fixture 사용 |
| `AI_EMBEDDING_ENABLED` | 기본false. Worker·Chat 공통 임베딩 Bean 활성화 |
| `EMBEDDING_MODEL`, `EMBEDDING_DIMENSIONS` | 모델/버전·차원 합의 후 같은 값 사용. 임의 모델 기본값 없음 |
| `ANALYSIS_EMBEDDING_MODEL_ID` | 응답 metadata.model이 없을 때만 쓰는 fallback. 실제 모델 선택 옵션과 별개 |
| `CHAT_GENERATION_ENABLED` | 기본false. 답변 ChatModel 활성화 |
| `CHAT_MODEL` | strict JSON Schema·Chat Completions를 지원하는 답변 모델. 미확정이며 기본값 없음 |
| `CHAT_TOKEN_ENCODING`, `CHAT_CONTEXT_TOKENS` | 선택한 답변 모델과 맞는 JTokkit encoding·전체 context 용량. 기본값 없음 |
| `CHAT_INPUT_TOKEN_BUDGET`, `CHAT_MAX_OUTPUT_TOKENS` | 기본8000·2048. 출력 cap은 본문뿐 아니라 JSON·인용·모델의 reasoning 사용도 고려 |
| `CHAT_RETRIEVAL_TOP_K` | 기본8, 1~20. 측정 후 조정 |

활성화 전에도 방·기록 조회는 가능하고 질문 접수는 JSON503 CHAT_AI_NOT_CONFIGURED다. 활성화했는데 필수 모델·키·차원·tokenizer/예산 설정이 잘못되면 기동 시 실패한다. 키 하나만 넣어 준비됐다고 판단하지 않는다. 설정 변경 뒤 앱/이미지를 다시 실행한다.

Worker·Chat은 libs/ai의 실제 OpenAiEmbeddingModel을 사용한다. 현재 원문/질문 모두 prefix나 메타데이터를 붙이지 않은 텍스트다. 모델·버전·차원·전처리를 바꾸면 기존 벡터 재색인을 검토한다. 답변 모델과 분석 모델을 임베딩 모델로 사용하지 않는다.

임베딩 HTTP는 연결3초·읽기/전체8초, Chat의 임베딩 대기는10초다. 답변 HTTP는 연결3초·읽기 공백30초·전체120초이며 MVC140초·lease160초다. SDK와 하위 HTTP transport의 자동 재시도는0회다. 실제 HTTP408의 재전송까지 테스트했다. SDK client는 Bean 종료 시 close하며 스트림은 종료·오류·앱 쓰기 실패 시 dispose한다. 앱 네트워크 종료 감지는 쓰기/시간 제한 시점일 수 있다.

HTTP408/429/5xx·통신 오류는 재시도 가능, 401 등 영구 오류·잘못된 결과는 재시도 불가다. Worker의 DB 작업 재시도와 Chat의 사용자 재요청은 별개다. 내부 자동 재생성을 추가하지 않는다.

## 검증 범위·다음 단계

- 실제 SDK → 로컬 HTTP fixture, PostgreSQL(pgvector), 실제 앱 포트의 첫 본문·부분 저장·최종 GET을 확인한다. 공급자 종료·JSON/인용 오류·예산 제외·삭제·중복/동시 요청·SDK 단일 시도도 테스트한다.
- 사용자의 결정에 따라 실제 키 발급·외부 GPT/임베딩 호출은 하지 않았다. 실제 모델 선택·encoding·옵션·AI 파트 프롬프트/Schema 검증·품질·비용·지연 측정은 남아 있다.
- [품질 개선 계획](chat-contract.md#답변-품질-개선-계획)에 따라 대표 질문·정답 근거를 준비한다. 필수 권한 필터는 유지하고 필요할 때 청크/topK/임계값·고급 메타데이터·하이브리드·재정렬을 확장한다.

참고: [OpenAI 구조화 출력·필드 순서·거절/불완전 결과](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat), [공급자 스트림 종료 metadata](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events).
