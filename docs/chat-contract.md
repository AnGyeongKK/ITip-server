# 채팅 범위·공유 연결

## 현재 구현안과 확인할 선택

Notion CHAT-01·06은 고정된 조건을 질문 시점에 재평가하는 방식을 제안한다. 이 구현은 해당 제안으로 준비하며 사용자 회신으로 확정된 정책이라고 표시하지 않는다. 범위를 바꾸면 새 방을 만들고, 삭제·타인 기록은 항상 제외한다. 생성 POST의 recordCount는 최초 개수를 보존하고 상세 GET·질문 접수는 현재 범위를 계산한다.

폴더 API는 아카이브 담당 범위다. 채팅은 `folders(id,owner_id,name,created_at)`와 `folder_records(folder_id,record_id,owner_id)`만 조회한다. V5는 이 최소 공유 구조와 소유권 FK를 준비한다. 동료의 폴더 구현 전 이름·필드·길이를 맞춘다. 폴더 삭제는 연결만 제거하고 기존 채팅·분석 기록은 유지한다.

검색은 원본 제목·한 줄 요약·키워드·구간 상세·도구 텍스트의 대소문자 무시 포함 검색이다. keywordIds는 summary.keywords에 모두 포함돼야 하며 다른 조건과 AND다. JSON 키·서비스 ID·자막 원문은 검색하지 않는다. 공유 검색 서비스가 아직 없어서 소유 완료 결과를 한 번 조회한 뒤 Java에서 필터링한다. 아카이브 검색 구현 때 같은 의미로 통합한다.

시연 입력 제한은 limit 1~50(기본20), keywordIds 최대50, q 최대200자다. 일일 질문 한도·요금제는 추가하지 않는다.

검색어의 앞뒤 공백만 제거하고 내부 공백·특수문자는 보존한다. 생성 시각은 PostgreSQL의 마이크로초 정밀도에 맞춰 저장하여 첫 응답과 멱등 재전송의 시각이 일치하게 한다.

## 외부 AI

현재 Notion AI 페이지는 RAG 채팅 미착수이며 Python endpoint·스트림 계약은 확인되지 않았다. #7은 내부 HTTP/SSE 계약 초안과 로컬 연동 fixture로 개발하고, 실제 Python과 GPT 검증을 구분한다. 주소·키를 임의로 만들거나 실제 AI가 연결됐다고 표시하지 않는다.

## 채팅방·기록 API (#6)

Chat 기본 주소는 `http://localhost:8082`다. API가 Flyway V4·V5를 적용한 후 실행한다. 로그인 제외 시연에서는 API·Chat의 `DEMO_USER_ID`를 같게 설정한다.

| 메서드·경로 | 동작 |
| --- | --- |
| POST /api/v1/conversations | Idempotency-Key UUID로 방 생성. 방이 유지되면 같은 키·본문은 최초 응답, 다른 본문은 409. 삭제된 방의 키 재전송은 404이며 복원하지 않음 |
| GET /api/v1/conversations | updatedAt/id 내림차순 커서 목록 |
| GET /api/v1/conversations/{id} | 현재 recordCount·scopeStatus·활성 답변 ID |
| DELETE /api/v1/conversations/{id} | 생성 중이면 409, 그 외 방·메시지·출처 삭제. 분석 결과 유지 |
| GET /api/v1/conversations/{id}/messages | 최신 페이지를 가져오고 items는 과거→최근. clientRequestId 조회와 cursor는 함께 사용 불가 |
| GET /api/v1/conversations/{id}/messages/{messageId} | 부분 답변·상태·실패 사유·다음 조회 간격 |

```json
{"scope":{"type":"FILTERED","folderId":null,"keywordIds":[],"q":"RAG"}}
```

일반 성공은 `code/message/data`, 오류는 `code/message`다. 생성은 201·Location, 삭제는 200이다. limit은 기본20·최대50이며 서버의 nextCursor를 그대로 다음 요청에 보낸다. 잘못된 커서는 400, 타인·없는 방은 404, 최초 참고 기록이 없으면 422 EMPTY_CHAT_SCOPE다.

메시지 순서는 방별 position이며 동일 시각에도 질문→답변 순서를 유지한다. 출처는 당시 제목·영상·구간을 저장하고 원본 기록 삭제 시 availability=DELETED를 반환한다. 기존 답변·출처·당시 사용 개수는 유지한다. 목록 조회는 출처·현재 기록을 일괄 조회한다.

공통 RequestIdFilter·ApiExceptionHandler·DemoUserProvider는 libs/common에 있다. 각 앱에 같은 처리 코드를 다시 만들지 않는다.

## 질문 접수·복구 (#7)

`Idempotency-Key`와 `clientRequestId`는 같은 UUID다. 같은 소유자·키는 방 ID·본문까지 비교한다. 동일 요청은 409 DUPLICATE_CHAT_REQUEST로 기존 이력 조회를 유도하고, 다른 방·본문 재사용은 IDEMPOTENCY_CONFLICT다. 키 기록은 대화 삭제 뒤에도 유지하며 자동 재생성하지 않는다.

질문 접수 TX에서 방을 잠그고 현재 범위를 확인한 뒤 USER·ASSISTANT·멱등 키를 함께 저장한다. 방의 position을 두 개 예약해 질문→답변 순서를 보장한다. 한 방의 활성 답변은 DB 부분 unique index로도 제한한다. 외부 AI는 커밋 후 호출한다.

재요청은 같은 질문의 재시도 가능한 FAILED·INCOMPLETE 답변에만 허용한다. 새 UUID와 retryOfMessageId로 새 두 메시지를 생성하며 과거 부분 답변은 보존한다. 완료 답변 재생성은 제공하지 않는다.

구현 기본 제한은 질문 4000 UTF-16 문자, 생성 lease 150초다. 상품 한도와 별개인 서버 입력·작업 보호 값이며 실제 AI 모델 선정 후 팀과 조정한다. 만료 작업은 부분 텍스트가 있으면 INCOMPLETE, 없으면 FAILED로 바꾸고 토큰을 폐기한다. 모든 저장은 현재 생성 토큰·유효 lease를 확인한다.
