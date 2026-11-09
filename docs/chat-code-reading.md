# 채팅 코드 읽기

`ITip/`는 Gradle 집계 루트다. 일반 API·분석 Worker·Chat은 각각 실행되며 같은 PostgreSQL을 사용한다. 이 문서의 Java 경로는 `ITip/` 기준이다.

## 검색 구현에서 볼 지점

| 파일·함수 | 확인할 내용 |
| --- | --- |
| `QuestionEmbeddingClient.embed()` | 공급자 호출 최대4개·대기10초·벡터/모델 검증·영구/일시 오류 구분. DB TX 없음 |
| `ChatGenerationStore.refresh()` | 임베딩 중 삭제·범위 변경 반영. 처음 허용된 기록과 현재 기록의 교집합 및 안전한 history |
| `ChatContextQuery.search()` | SQL에서 owner·record·완료·모델·차원 제한. MATERIALIZED로 혼합 차원 연산 방지. 현재는 정확 검색이며 ANN 인덱스 없음 |
| `ChatContext.citations()` → `ChatCitationQuery.validate()` | 전달한 근거만 인용. 완료 시 현재 DB 권한·삭제·시간 재확인 후 snapshot 저장 |

`QuestionEmbeddingClientTest`는 입력·모델 식별·잘못된 벡터·공급자 오류·시간 제한을 검사한다. `ChatStreamApiTest`는 실제 pgvector에서 가까운 타인/범위 밖 chunk를 섞어도 허용 원문만 선택하고, 전달하지 않은 유효 chunk 인용은 거부하는지 확인한다.

모델 Bean이 없는 기본 구성에서는 질문 생성이 JSON503으로 거부된다. 공급자 연결과 Python2.0 동시 적용이 필요하다. 이후 실제 답변 품질 테스트와 고급 RAG 도입 순서는 [품질 개선 계획](chat-contract.md#답변-품질-개선-계획)을 따른다.

## 먼저 볼 순서

| 순서 | 파일·함수 | 확인할 내용 |
| --- | --- | --- |
| 1 | `settings.gradle`, `apps/chat-app/build.gradle` | 실행 앱과 공유 라이브러리 의존성. 앱끼리 호출하거나 의존하지 않음 |
| 2 | `apps/chat-app/.../ChatApplication.java`, `application.yaml` | 실행 진입점, 8082 포트, 공통 DB 설정, Flyway 실행 주체 |
| 3 | `chat/conversation/controller/ConversationController.java` | 방 생성·목록·상세·삭제의 HTTP 경계 |
| 4 | `chat/conversation/service/ConversationService.java` | 멱등 키·짧은 생성 TX·소유 방 잠금·삭제 조건 |
| 5 | `chat/conversation/service/ChatScopeQuery.java` | 소유 완료 기록 → 폴더·키워드 AND·검색어 필터 |
| 6 | `chat/conversation/service/MessageQueryService.java` | position 커서, clientRequestId 복구, 삭제 출처 표시 |
| 7 | `libs/domain/.../domain/chat/`, `db/migration/V4*`, `V5*` | DB가 보장하는 순서·중복·활성 답변·삭제 범위 |
| 8 | `apps/chat-app/src/test/java/com/gkk/itip/chat/` | 실제 PostgreSQL의 실패 입력과 기대 응답 |

`...`는 `src/main/java/com/gkk/itip/`이며, `chat/...`도 Chat 앱의 해당 Java 루트 아래다. 메서드의 어노테이션과 해당 메서드를 호출하는 Bean을 함께 본다. 같은 클래스 내부 호출에 새 `@Transactional` 경계가 생긴다고 가정하지 않는다.

답변 생성부터 읽으려면 `chat/stream/ChatStreamController.send()` → `ChatGenerationStore.begin()` → `ChatStreamBridge.write()` 순서로 시작한다. 검색은 `chat/retrieval/QuestionEmbeddingClient.embed()` → `ChatContextQuery.search()`, 최종 저장은 `ChatGenerationStore.complete()`, Python 통신은 `chat/infrastructure/PythonChatClient.stream()`에 있다.

## 방 생성 요청 한 번 따라가기

```text
POST /conversations + Idempotency-Key
  ConversationController.create()
  → ConversationService.create() [DB TX]
     → 소유자 단위 advisory lock
     → 이전 키·본문 해시 검사
     → ChatScopeQuery.resolve()로 현재 소유 기록 선택
     → 방 + idempotency_requests 함께 저장
  → 커밋 후 201 / Location
```

Controller는 서버의 DemoUserProvider에서 소유자를 얻는다. 요청 JSON의 ownerId·임의 사용자 헤더로 바꾸지 않는다. 키는 원격 AI 재시도를 위한 장치가 아니라 같은 사용자 동작의 중복 접수를 막는 장치다.

## 기록 조회 요청 한 번 따라가기

```text
GET /conversations/{id}/messages?cursor=...
  ConversationMessageController.list()
  → MessageQueryService.list() [읽기 TX / REPEATABLE_READ]
     → 소유 방 확인
     → position DESC로 limit+1 조회
     → 출처·현재 개인 기록을 각각 일괄 조회
     → 페이지 안 items를 과거→최근 순서로 반환
```

시각이 같아도 position이 질문→답변 순서를 유지한다. 원본 기록 삭제는 과거 대화를 지우지 않으며 출처에 `DELETED`만 표시한다. 질문 접수 여부가 불분명하면 새로운 키로 재전송하기 전에 `clientRequestId`로 기존 두 메시지를 찾는다.

## 질문·답변 요청 한 번 따라가기

```text
POST /conversations/{id}/messages
  ChatStreamController.send()
  → Python·임베딩 Bean 설정·인스턴스 처리 여유 확인
  → ChatGenerationStore.begin() [짧은 DB TX]
     → 소유자+키 advisory lock → 소유 방 row lock
     → 중복·활성 답변·재요청·현재 참고 기록 확인
     → 방 순번 2개 예약·USER/ASSISTANT/멱등 키 저장
     → ChatHistoryQuery.select(): 현재 범위로 안전한 과거 대화 선별
  → 커밋
  → MVC 전용 스레드 / ChatStreamBridge.write() [TX 없음]
     → meta
     → QuestionEmbeddingClient.embed(): 질문만 임베딩 [외부 I/O, 최대10초]
     → ChatGenerationStore.refresh() [짧은 읽기 TX]: 현재 범위·history 재확인
     → ChatContextQuery.search(): 소유/범위/완료/모델/차원 제한 후 pgvector 검색
     → PythonChatClient.stream(): question + contexts(원문·출처) + history, schema2.0
     → upstream 이벤트 읽기·delta 누적
     → ChatGenerationStore.checkpoint() [약250ms 간격의 짧은 TX]
     → sequence·delta 앱 전송
     → Python done
     → ChatGenerationStore.complete() [짧은 DB TX]
        → 방 → 메시지 잠금·토큰/lease 재확인
        → ChatCitationQuery.validate(): 전달한 인용 조합·현재 범위·chunk·section·시간 검증
        → 본문·출처·COMPLETED 함께 저장
     → 커밋 후 MessageQueryService.get()으로 같은 저장값 조회
     → sources → done → upstream 닫기·처리 여유 반환
```

`WebClient`를 사용해도 서버는 MVC다. 전용 실행 스레드에서 스트림을 읽고 DB를 호출하며, Netty 이벤트 루프에 JPA를 올리지 않는다. Reactor 연산자·HTTP·SSE 조립은 PythonChatClient, 이벤트 전환·전달은 ChatStreamBridge, 원자 저장은 ChatGenerationStore에서 읽는다.

## 구현 포인트 6개

| 코드 | 이유·확인 방법 |
| --- | --- |
| `begin()`·`ConversationService.delete()`의 방 잠금 | 생성과 삭제가 같은 순서로 잠겨 생성 중 삭제·동시 질문 경합을 제어 |
| `GenerationTicket.token`·`ChatMessage.ownedGeneration()` | 만료된 작업의 늦은 callback이 새 상태를 덮지 못함 |
| `complete()`를 다른 Bean에서 호출 | 메서드 반환 전에 Spring 트랜잭션 프록시가 실제 commit. 그 다음에 done 전송 |
| `ChatCitationQuery.validate()` | 소유 DB에서 출처 구성. FOR SHARE로 인용 기록 삭제를 완료 commit까지 기다리게 함 |
| `ChatStreamBridge.write()`의 try-with-resources·committed | 연결 종료 시 upstream 정리. 완료 뒤 전송 실패를 DB 실패로 되돌리지 않음 |
| `ChatLimits`·전용 executor·semaphore | 인스턴스 자원 제한과 방별 DB 중복 제어를 구분. 시간·크기 값 한곳에서 공유 |

현재 Spring 7의 callback 출력 스트림은 flush가 기본 비활성이다. `ChatStreamController`가 실제 servlet 스트림을 전달하는 이유를 함께 본다. 이 처리가 빠지면 작은 SSE가 완료 시점에 모여 도착할 수 있다. `ChatHttpStreamingTest`는 이 문제의 실제 HTTP 실패→수정을 확인한다.

`fail()`도 DB 장애로 저장할 수 없으면 상태를 꾸미지 않고 연결을 닫는다. 앱은 EOF를 완료로 간주하지 않고 GET으로 복구한다. 프로세스 강제 종료는 160초 lease 만료 후 로컬 10초 복구 작업이 회수한다. 마지막 체크포인트 뒤 조각은 유실될 수 있다.

## 테스트로 동작 확인

Docker를 켠 뒤 `ITip/`에서 실행한다. 테스트는 Testcontainers가 별도 DB를 만들며 개발 DB를 비우지 않는다.

```sh
./gradlew :apps:chat-app:test
./gradlew build
```

`ConversationCreateApiTest`의 멱등 키·검색 공백 테스트, `ConversationReadDeleteApiTest`의 동일 시각 커서·삭제 테스트, `MessageHistoryApiTest`의 부분 답변·삭제 출처 테스트를 먼저 읽는다. 설정·실행 순서는 [로컬 실행](local-run.md), 범위 선택 제안과 API는 [채팅 계약](chat-contract.md)을 따른다.

생성은 `ChatGenerationStoreTest`의 실제 동시 접수부터 본다. `ChatStreamApiTest`는 별도 HTTP 서버가 UTF-8을 쪼개 보내고 EOF·30초 idle·잘못된 출처를 재현한다. 출력 IOException과 DB 장애 경계 대역 테스트는 실제 모바일·DB 중단 시연과 구분한다. [Python 계약 초안](python-chat-contract.md)은 실제 AI 파트와 아직 맞춰야 한다.

`ChatHttpStreamingTest`는 완료 전 첫 조각 수신을 실제 포트에서 확인한다. `ChatCompletionConcurrencyTest`는 SQL 이후 잠시 대기시켜 삭제와 완료의 순서를 확인하고, 테스트 Clock으로 lease를 넘겨 만료 복구가 완료된 답변을 덮지 않는지 확인한다. [검토 기록](chat-review-log.md)에 발견 문제·수정 커밋·258개 전체 테스트 결과가 있다.

채팅방·대화 저장은 [PR #12](https://github.com/AnGyeongKK/ITip-server/pull/12), Python·SSE 원본 구현은 [PR #13](https://github.com/AnGyeongKK/ITip-server/pull/13)이다. PR #13의 원본 대상은 `6-feat-chat`이었고 PR #19로 `dev`에 통합됐다. [dev 통합 PR #19](https://github.com/AnGyeongKK/ITip-server/pull/19)는 영상 가져오기·Docker 설정을 포함한 최신 `dev`에 이 코드를 연결한다. 실제 Python·GPT 응답 확인 전에는 이슈 #7 완료로 표시하지 않는다. [API 호출 구조](yousum-api-pipelines.html)에서 CHAT-04를 선택하면 함수·트랜잭션·원본 커밋을 함께 볼 수 있다.
