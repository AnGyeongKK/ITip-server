# 채팅 코드 읽기

먼저 `ChatStreamBridge.write()`를 열고 호출하는 함수를 따라간다. Java 경로는 `ITip/apps/chat-app/src/main/java/com/gkk/itip/` 기준이다. API·Worker·Chat은 각각 실행되고 같은 PostgreSQL을 사용한다.

## 전체 흐름

```text
ChatStreamController.send()
  → 답변/임베딩 모델 구성·동시 처리 여유 확인
  → ChatGenerationStore.begin() [짧은 TX]
     키 advisory lock → 소유 방 row lock
     중복/활성 답변/재요청/현재 범위 확인
     USER·ASSISTANT·멱등 키 함께 저장 → 커밋
  → ChatStreamBridge.write() [AI 대기 전체에는 TX 없음]
     meta 전송
     QuestionEmbeddingClient.embed() [질문만 외부 임베딩, 최대10초]
     ChatGenerationStore.refresh() [현재 범위/history 읽기 TX]
     ChatContextQuery.search() [pgvector SQL, 최대3초]
     ChatAnswerGenerator.prepare() [system + JSON 데이터, 토큰 예산 조정]
     ChatAnswerGenerator.stream() [실제 Spring AI/OpenAI SDK]
       AnswerJsonStream.accept() → answer만 점진 추출
       checkpoint() TX → delta 앱 전송/flush
     AnswerJsonStream.finish() [공급자 STOP·완전한 JSON·후보 인용 확인]
     ChatGenerationStore.complete() [짧은 TX]
       방 → 메시지 잠금 → 현재 token/lease
       ChatCitationQuery.validate() [현재 권한·기록·구간, FOR SHARE]
       본문·출처·COMPLETED 저장 → 커밋
     저장값 재조회 → sources → done
```

## 핵심 파일

| 파일·함수 | 볼 지점 |
| --- | --- |
| `stream/ChatStreamController.send()` | 서버 DemoUserProvider·Idempotency-Key·모델 준비·처리 여유. 실제 servlet 출력 스트림으로 flush |
| `stream/ChatGenerationStore.begin()` | 방 잠금·중복·순번·질문/답변/키의 원자 저장 |
| `retrieval/QuestionEmbeddingClient.embed()` | 유일한 EmbeddingModel, 벡터/모델 검증, 10초 대기·최대4개 호출·무대기열 |
| `stream/ChatGenerationStore.refresh()` | 임베딩 중 삭제/범위 변경 반영. 처음 허용 기록과 현재 기록의 교집합 및 안전한 history |
| `retrieval/ChatContextQuery.search()` | owner·기록·완료·모델/차원 제한 후 코사인 정렬. MATERIALIZED로 혼합 차원 연산 방지. 현재는 ANN 없는 정확 검색 |
| `generation/ChatAnswerGenerator.prepare()` | 고정 SystemMessage와 JSON UserMessage 분리. 오래된 대화 쌍→후순위 원문 제외, 실제 전송 후보 유지 |
| `generation/AnswerJsonStream` | JSON 첫 answer만 순차 decode. escape/Unicode 경계, 중복/손상 JSON·EOF·LENGTH·refusal·잘못된 인용 확인 |
| `config/ChatModelConfiguration` | 명시한 답변 모델·encoding·전체 context/input/output 예산·strict JSON Schema |
| `stream/ChatCitationQuery.validate()` | 실제 전송한 후보 + 현재 DB 권한. 제목/영상/시간을 DB로 만들고 기록 FOR SHARE 잠금 |
| `libs/ai` (집계 루트 기준) | Worker/Chat 공통 임베딩 설정·SDK client 수명·HTTP 재시도 정책. 일반 API는 의존하지 않음 |

고정 지시문은 `apps/chat-app/src/main/resources/prompts/rag-system.txt`다. 분석 모델 프롬프트·AnalysisOutput과 채팅 모델 Schema를 섞지 않는다. 요청·결과 JSON과 설정은 [직접 생성 계약](chat-generation-contract.md)을 본다.

## 구현에서 유지할 조건

- 외부 모델/SSE 대기를 DB TX로 묶지 않는다. 다른 Bean의 `complete()`가 반환돼 실제 commit된 뒤 done을 보낸다.
- generation token·lease는 늦은 저장이 완료/만료 상태를 덮는 것을 막는다. 만료160초 뒤 로컬10초 복구 작업이 FAILED/INCOMPLETE로 회수한다.
- 원문이 DB에 존재해도 모델 입력에서 예산 때문에 제외했다면 인용할 수 없다. 정상 종료 metadata와 근거 충분성도 별도로 확인한다.
- 완료 TX의 FOR SHARE는 검증한 기록 삭제를 commit까지 기다리게 한다. 이후 삭제해도 과거 답변·출처 snapshot은 보존하고 조회에 DELETED로 표시한다.
- SDK maxRetries0과 하위 HTTP 자동 재전송0을 모두 확인한다. Spring AI 기본 transport의 HTTP408 재전송을 실제 테스트로 발견해 같은 버전의 공식 SDK transport로 바꿨다.
- try-with-resources로 모델 스트림을 닫는다. 앱 쓰기 실패는 부분 본문을 보존하고 완료 뒤 네트워크 실패는 COMPLETED를 유지한다. 실패 상태도 저장하지 못하면 종료 이벤트를 꾸미지 않는다.
- Spring7 callback 출력 스트림은 flush를 전달하지 않을 수 있다. Controller가 실제 servlet 스트림을 넘기는 처리를 유지한다.

## 방·기록을 읽을 때

```text
ConversationController → ConversationService / ConversationQueryService
  → 소유자·멱등 키·범위 확인 → 저장/조회
ConversationMessageController → MessageQueryService
  → position 커서 → 출처/현재 기록 일괄 조회 → 오래된 순서로 표시
```

메시지는 방별 position으로 정렬한다. clientRequestId로 접수 여부를 확인한 뒤 재시도한다. 범위를 바꾸면 새 방을 만들고, 원본 기록 삭제가 과거 대화 삭제로 이어지지 않게 한다. [채팅 범위·API](chat-contract.md)를 함께 본다.

## 테스트로 따라가기

Docker를 실행한 뒤 `ITip/`에서 확인한다. Testcontainers의 별도 PostgreSQL을 사용한다.

```sh
./gradlew :apps:chat-app:test
./gradlew :libs:ai:test
./gradlew build
```

| 테스트 | 검증할 동작 |
| --- | --- |
| `ChatAnswerGeneratorTest` | 고정 지시문/비신뢰 데이터·중괄호·토큰 예산·제외된 후보 |
| `AnswerJsonStreamTest` | 한 글자 경계·본문/JSON 분리·실제 ID 매핑·잘린 출처·LENGTH·refusal·EOF |
| `ChatModelConfigurationTest` | 실제 SDK→로컬 HTTP에서 strict Schema·출력 cap·종료 metadata |
| `ChatStreamApiTest` | 실제 pgvector와 SDK fixture. 정상/근거 부족·예산 제외 인용·삭제·오류/재시도·부분 저장 |
| `ChatHttpStreamingTest` | 실제 앱 포트에서 JSON 전체가 오기 전 첫 본문과 DB GENERATING을 확인 |
| `ChatCompletionConcurrencyTest` | 기록 삭제/완료 잠금 순서·lease 회수와 완료 경합 |
| `QuestionEmbeddingProviderTest`, `libs/ai` 테스트 | 실제 SDK HTTP401/408/429/503 분류와 단일 시도·모델/차원 설정 |
| `ChatGenerationStoreTest`, 방/이력 API 테스트 | 중복·동시 질문·재시도·position·삭제 출처·커서 |

실제 키·외부 모델은 사용하지 않았다. 모델/encoding/옵션·프롬프트/Schema·대표 질문의 품질·시간·비용은 AI 파트와 확인한다. [품질 개선 계획](chat-contract.md#답변-품질-개선-계획)과 [검토 기록](chat-review-log.md)을 참고한다.

[PR #21](https://github.com/AnGyeongKK/ITip-server/pull/21)과 [API 호출 구조](yousum-api-pipelines.html)의 CHAT-04에서 현재 함수 흐름을 본다. 이전 저장/SSE 구현은 PR #12·#13·#19 기록에 보존된다.
