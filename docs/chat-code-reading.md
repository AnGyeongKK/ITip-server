# 채팅 코드 읽기

`ITip/`는 Gradle 집계 루트다. 일반 API·분석 Worker·Chat은 각각 실행되며 같은 PostgreSQL을 사용한다. 이 문서의 Java 경로는 `ITip/` 기준이다.

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

## 테스트로 동작 확인

Docker를 켠 뒤 `ITip/`에서 실행한다. 테스트는 Testcontainers가 별도 DB를 만들며 개발 DB를 비우지 않는다.

```sh
./gradlew :apps:chat-app:test
./gradlew build
```

`ConversationCreateApiTest`의 멱등 키·검색 공백 테스트, `ConversationReadDeleteApiTest`의 동일 시각 커서·삭제 테스트, `MessageHistoryApiTest`의 부분 답변·삭제 출처 테스트를 먼저 읽는다. 설정·실행 순서는 [로컬 실행](local-run.md), 범위 선택 제안과 API는 [채팅 계약](chat-contract.md)을 따른다.
