# API 요청·응답 규칙

## 적용 범위와 기준

- 2026-11-06에 [Notion API 명세서](https://www.notion.so/40c46c2fafdf805fb00aeac128e2bdc3)의 상세 페이지 54개를 확인했다. 현재 명세 50개와 `이전안 제외` 4개를 구분했으며, JSON 예시 117개와 SSE 예시 6개를 검토했다.
- 현재 시연용 MVP는 `02·03·04·05·06·08`이다. Notion에 MVP로 남아 있는 회원·인증 및 요금제·이용 한도는 최신 사용자 결정에 따라 제외한다. `AUTH-*`, `PLAN-*`, 폐기된 `ARCHIVE-01`을 먼저 구현하지 않는다.
- 이 파일은 공통 연동 규칙이다. API별 필수 필드·경로·상태값은 해당 상세 페이지를 함께 읽는다. 명세의 `제안`, `연동 협의`, 예시 제한값을 확정 정책으로 바꾸지 않는다.
- 충돌 시 최신 사용자 결정과 합의한 API 변경을 우선한다. 불일치는 보고하고, 클라이언트에 노출되는 필드·상태·오류를 에이전트가 임의로 추가하거나 변경하지 않는다. 구현 확정 시 명세와 이 규칙도 함께 맞춘다.

## 1. 호출 서버와 HTTP

| 대상 | 주소·형식 | 구현 규칙 |
| --- | --- | --- |
| 일반 API | `API_BASE_URL` + `/api/v1/...` | JSON 요청·응답 |
| 채팅 API | `CHAT_BASE_URL` + `/api/v1/conversations...` | 조회·방 관리는 JSON, 답변 생성만 POST SSE |
| 자막 업로드 | 발급된 `uploadUrl` | GCS에 직접 PUT. YouSum 컨트롤러를 거치지 않음 |
| 분석 실행 | 비공개 Worker URL | Cloud Tasks의 인증된 HTTP 호출. 모바일 호출 API와 분리 |

- JSON 본문은 `Content-Type: application/json`, 일반 응답 요청은 `Accept: application/json`을 사용한다. SSE 요청만 `Accept: text/event-stream`이다.
- GET은 path/query로 조건을 받는다. 조회 때문에 분석 생성·열람 위치 저장 등 업무 데이터 변경을 수행하지 않는다.
- POST·PATCH·PUT의 본문 구조는 상세 명세를 따른다. PATCH의 생략 필드는 기존 값을 유지하며, `null`로 초기화할 수 있는지는 필드별로 정한다.
- `Location`과 `statusUrl`은 명세대로 리소스 경로를 제공한다. 상대 경로는 해당 API를 호출한 서버의 base URL로 해석한다.
- 요청 추적용 `X-Request-Id`를 응답 헤더로 제공한다. 토큰·자막 원문·질문 원문·서명 URL 전체를 로그에 남기지 않는다.
- 기존 명세의 `Authorization: Bearer`는 로그인 도입 시 적용할 규칙이다. 로그인 제외 MVP의 사용자 식별 방식은 아직 미정이며, 아래의 구현 전 확인 사항을 따른다. 내부 Worker의 IAM·OIDC 검증은 별도로 유지한다.

## 2. 일반 JSON 응답

일반 JSON 응답은 `ApiResponse<T>`로 감싼다. HTTP 상태는 `ResponseEntity` 등으로 실제 응답에 설정하고, 오류를 HTTP 200으로 통일하지 않는다.

```json
{
  "code": "OK",
  "message": "요청이 완료됐어요.",
  "data": {
    "recordId": "301",
    "analysisId": "201",
    "lastViewedAt": null
  }
}
```

반환 데이터가 없는 성공과 오류는 `data`를 생략한다.

```json
{
  "code": "OK",
  "message": "요청을 처리했어요."
}
```

```json
{
  "code": "RESOURCE_NOT_FOUND",
  "message": "요청한 항목을 찾을 수 없어요."
}
```

- 최상위 필드는 `code`, `message`, 필요 시 `data`다. `success`, `timestamp`, `errors` 등을 독자적으로 추가하지 않는다.
- 빈 목록은 `[]`, 미생성·미측정 값은 필드 정의에 따라 `null`로 반환한다. 없다는 의미를 `0`, 현재 시각, 임의 문자열로 대체하지 않는다.
- `data` 내부의 nullable 필드는 명세대로 유지한다. 전역 `NON_NULL` 설정으로 `analysisId`, `nextCursor`, `positionMs` 등의 `null`까지 사라지게 하지 않는다. 최상위 `data` 생략은 wrapper에서 처리한다.
- 응답 DTO를 별도로 정의하고 JPA Entity·`Page`를 그대로 반환하지 않는다. 요청·응답 DTO는 Java 21 `record`를 기본으로 검토하며, JPA Entity에는 적용하지 않는다.
- GCS 응답과 SSE 이벤트는 이 wrapper의 적용 대상이 아니다.

## 3. HTTP 상태와 오류

| HTTP | 의미·적용 |
| --- | --- |
| `200` | 조회·수정·삭제 성공. 삭제도 현재 명세의 JSON 응답을 유지 |
| `201` | 리소스 생성. 해당 명세의 `Location` 제공 |
| `202` | 분석 작업 접수. `jobId`, 처리 상태, `statusUrl` 제공. 분석 완료를 뜻하지 않음 |
| `400` | JSON·타입·필수값·커서 등 요청 형식 오류 |
| `404` | 리소스 없음 또는 타인 소유. 소유 여부를 구분해 노출하지 않음 |
| `409` | 현재 상태에서 불가능한 동작·중복 요청·멱등 키 충돌 |
| `410` | 해당 명세에서 정의한 원문 만료 등 재업로드 필요 상황 |
| `413` | 허용한 업로드 크기 초과 |
| `422` | 형식은 맞지만 영상·자막·구간·질문 등 업무 조건 위반 |
| `5xx` | 서버 또는 외부 서비스 오류. 원문 예외·스택 트레이스를 응답에 노출하지 않음 |

- `401/403` 인증 오류와 `429` 이용 한도 오류는 기존 상세 명세에 있으나, 제외된 로그인·한도 기능을 이 문서 때문에 구현하지 않는다.
- 각 오류의 구체적인 `code` 이름·메시지는 구현 시 확정한다. 화면 분기는 HTTP 상태와 `code`로 하고 `message` 문구에 의존하지 않는다.
- `GET analysis-jobs/{jobId}`에서 작업이 `FAILED`인 것은 조회 실패가 아니다. HTTP 200의 `data.status=FAILED`와 작업의 `error.code/message/retryable`로 전달한다.
- 공통 예외 처리에서 검증 오류와 업무 예외를 같은 JSON 형식으로 변환한다. 오류를 잡아 성공 응답이나 빈 정상 결과로 바꾸지 않는다.

## 4. 필드·ID·시간·소유권

| 종류 | 전송 규칙 |
| --- | --- |
| 필드명·enum | 필드명 `lowerCamelCase`, enum은 명세의 `UPPER_SNAKE_CASE` |
| 서비스 ID | 서버의 Long/BIGINT ID도 JSON·path·query에서는 문자열. 프론트에서 숫자로 변환하지 않음 |
| 외부 ID | `videoId`, YouTube `playlistId`는 원래 문자열을 유지 |
| 시각 | ISO 8601 UTC `Z`. 예: `2026-11-04T00:00:00Z`; 서버는 `Instant` 중심 |
| 시간 길이·위치 | `durationMs`, `startMs`, `endMs`, `positionMs`는 정수 밀리초 |
| 개수·용량·논리값 | count·`sizeBytes`는 숫자, boolean은 JSON boolean |

- `recordId`는 사용자 보관 기록, `analysisId`는 분석 결과, `jobId`는 작업, `batchId`는 묶음이다. 같은 값으로 취급하거나 한 ID로 다른 리소스를 직접 조회하지 않는다.
- `youtube.com/watch?v=...`, `youtu.be/...`, 재생 시각이 붙은 URL은 같은 `videoId`로 정규화해 영상 중복을 판별한다. URL 원문이나 재생 시각을 별도 영상의 식별자로 사용하지 않는다.
- `sectionId`는 화면의 주제 구간, `segmentId`는 자막 구간이다. 재생목록 그룹의 `groupId`/검색의 `playlistGroupId`는 내부 ID이며 YouTube `playlistId`와 다르다.
- 배치 조회의 `items[].itemId`와 업로드·접수 요청의 `batchItemId`는 같은 배치 항목을 가리킨다. 현재 필드명을 한쪽에서만 바꾸지 않는다.
- 필수 여부와 null 허용은 별개다. 예: 열람 위치의 `sectionId`와 기기 등록의 `fcmToken`은 필수지만 null을 허용한다.
- 영상 게시일, `analyzedAt`, `lastViewedAt`을 구분한다. 열람 위치 저장으로 분석 최신순 목록을 재정렬하지 않는다.
- 입력 ID마다 소유권과 연결 관계를 확인한다. 구간은 해당 분석에, 업로드는 해당 사용자·배치 항목·영상에 속해야 한다. 존재하는 ID라는 이유만으로 접근을 허용하지 않는다.

## 5. 목록·커서·검색

[기록 검색 SEARCH-01](https://app.notion.com/p/40c46c2fafdf817395a0c0109462cd36)의 커서 방식이 기본이다. 모든 배열을 페이지네이션으로 바꾸는 규칙은 아니며, 홈 카드·키워드·추천 목록은 각 명세를 따른다.

아래는 페이지네이션 공통 필드만 표시한 예시다. 실제 응답에는 해당 API의 `matchedCount`, `filters`, `selectionContext` 등 추가 필드를 유지한다.

```json
{
  "code": "OK",
  "message": "요청이 완료됐어요.",
  "data": {
    "items": [],
    "nextCursor": null,
    "hasNext": false
  }
}
```

- 기본 `limit=20`. 최대 50은 명세의 제안값이므로 구현 시 확정한다. 커서는 클라이언트가 해석·조립하지 않는 불투명 문자열로 제공한다.
- 정렬 키와 고유 ID를 함께 사용한다. 기록은 `analyzedAt DESC, recordId DESC`, 폴더는 `createdAt DESC, folderId DESC`, 대화방은 `updatedAt DESC, conversationId DESC`를 기준으로 한다.
- `limit + 1`개를 조회해 `hasNext`를 판단한다. 다음 커서는 실제 반환한 마지막 항목에서 만들며, 마지막 페이지는 `nextCursor:null`이다.
- 메시지는 최신 묶음을 조회하되 응답 `items`는 과거 → 최근 순서다. 다음 커서는 더 오래된 메시지를 가져오기 위한 값이다.
- query 배열은 `keywordIds=41&keywordIds=42`처럼 반복한다. 검색어 `q`는 trim하고 빈 검색어는 검색 조건 없음으로 처리한다.
- `q`, `folderId`, `keywordIds`, `playlistGroupId` 조건은 AND이며, 여러 키워드도 모두 만족해야 한다. `targetFolderId`는 추가 대상 폴더의 포함 여부 표시용이고 목록 필터가 아니다.
- 검색·폴더·키워드·그룹·정렬·대상 폴더 조건이 바뀌면 커서를 초기화한다. 서버는 잘못되거나 현재 조건과 맞지 않는 커서를 검증한다.
- `matchedCount`는 전체 조건 일치 수, `recordCount`는 해당 폴더 전체 기록 수다. 현재 페이지 길이나 RAG chunk 수로 대체하지 않는다.
- 중복 조인으로 같은 기록이 여러 번 나오거나 count가 부풀지 않게 한다. 컬렉션 fetch join에 페이지네이션을 바로 적용하지 않는다. ID 페이지 조회 후 일괄 조회 또는 DTO 쿼리를 검토한다. EntityGraph 도입은 필수가 아니다.
- MVP 검색은 명세의 제목·한 줄 요약·키워드·상세 내용·기술·도구 범위를 따른다. 자막 전체 검색·Elasticsearch는 후속 논의다. 검색 결과의 일치 문구는 일반 텍스트로 전달한다.

## 6. 생성·재전송·멱등성

- `Idempotency-Key` 필수 API: `ANALYSIS-01`, `ANALYSIS-03`, `ANALYSIS-07`, `ARCHIVE-06`, `CHAT-01`, `CHAT-04`.
- 같은 사용자 동작의 통신 재전송은 같은 UUID 키와 같은 본문을 사용한다. 새로운 동작·사용자가 선택한 새 재시도는 새 키를 사용한다. 같은 키로 다른 본문을 보내면 409다.
- 키를 API·사용자·대상과 어떻게 묶을지, 보관 기간, 본문 비교 방식은 구현 전 합의한다. 컨테이너 메모리만으로 중복을 막지 않고 DB 제약·트랜잭션으로 동시 요청도 처리한다.
- 분석은 같은 업로드·항목에 기존 `jobId`를 재사용한다. 이미 완료된 경우 200과 기존 `analysisId`, `recordId`를 반환한다. 다른 사용자의 결과를 그대로 반환하지 않는다.
- 채팅은 예외적으로 동일 키·동일 본문 재전송도 409 `DUPLICATE_CHAT_REQUEST`로 접수 상태 조회를 유도한다. SSE 재생이나 새 AI 호출을 수행하지 않는다.
- 폴더 기록 추가는 연결 관계의 중복을 막고 `addedRecordIds`·`alreadyIncludedRecordIds`로 구분한다. 이 API에 새 멱등 헤더를 임의로 요구하지 않는다. 대상 중 유효하지 않거나 타인 소유 기록이 있으면 일부만 추가하지 않는다.

## 7. 자막 업로드와 비동기 분석

근거: [UPLOAD-01](https://app.notion.com/p/40d46c2fafdf818d87d1e1374d528ae0), [UPLOAD-03](https://app.notion.com/p/40d46c2fafdf812eb8f2c91517b92a92), [ANALYSIS-01](https://app.notion.com/p/40c46c2fafdf81e0bcdfe6f6aa6aef28), [ANALYSIS-08](https://app.notion.com/p/40d46c2fafdf81549020d524063ea046).

```text
배치 생성 → Android 자막 수집 → 업로드 URL 발급 → GCS PUT
→ uploadId로 분석 접수 → Cloud Tasks → Worker → 상태·결과 조회 / 알림
```

- 한국어 수동 자막 → 한국어 자동 자막 순으로 시도한다. 영상 길이는 최대 60분이다. 자막 없음, 접근 제한, 네트워크 실패를 구분하며 음성 전사·다국어 지원은 이후 범위다.
- 기기별 동시 수집은 1개다. 중복 수집 방지·제한 응답 시 중단·재시도 제한을 적용한다. 요청 간격·재시도 수치는 기기 테스트 후 조정하며 임의의 안전 간격을 보장하지 않는다.
- 업로드 URL 요청은 `batchId`, `batchItemId`, `videoId`, `contentType`, `sizeBytes`를 보낸다. 응답의 `method`, `headers`, `expiresAt`, `maxBytes`를 그대로 사용한다. 예시의 1 MiB·10분을 확정 제한으로 하드코딩하지 않는다.
- GCS에는 UTF-8 JSON 파일을 본문으로 PUT한다. multipart나 `ApiResponse`로 감싸지 않으며 YouSum Bearer 토큰을 전달하지 않는다. GCS 응답은 YouSum JSON 형식이 아닐 수 있다.

```json
{
  "videoId": "dQw4w9WgXcQ",
  "language": "ko",
  "segments": [
    { "startMs": 0, "endMs": 3000, "text": "영상의 한국어 자막입니다." }
  ]
}
```

- 자막은 비어 있지 않은 텍스트와 `0 <= startMs < endMs <= durationMs`를 검증한다. 자동 자막의 겹침·정규화 정책은 AI 파트와 합의한다.
- 분석 접수는 `batchId`, `batchItemId`, `uploadId`를 받는다. 임의 URL·objectKey·자막 본문을 추가로 받지 않는다. 서버가 소유권·영상 일치·실제 객체와 버전을 확인하고, Worker가 내용·언어·시간·실제 용량을 최종 검증한다.

| 상태 대상 | 상태값 |
| --- | --- |
| 업로드 | `ISSUED`, `UPLOADED`, `CONSUMED`, `EXPIRED`, `REJECTED` |
| 분석 작업 | `QUEUED`, `PROCESSING`, `RETRY_WAIT`, `COMPLETED`, `FAILED` |
| 작업 단계 | `VALIDATING`, `SUMMARIZING`, `INDEXING`, `FINISHING` |
| 배치 전체 | `PREPARING`, `PROCESSING`, `COMPLETED`, `PARTIALLY_FAILED`, `FAILED` |
| 배치 개별 항목 | `AWAITING_UPLOAD` 및 분석 작업 상태 |

- `UPLOADED`는 객체가 존재한다는 뜻이며, 자막 검증·분석 완료를 뜻하지 않는다. 아직 없는 `jobId`, `analysisId`, `recordId`는 null이다.
- `nextPollAfterMs`는 서버가 제공하고 종료 상태에서는 null이다. 예시 3000ms를 클라이언트의 고정 정책으로 만들지 않는다. 배치 상태별 개수는 중복 없이 합계가 전체 항목 수와 일치해야 한다.
- 요약·키워드·주제별 상세·마인드맵·채팅 검색 데이터가 모두 준비되어야 `COMPLETED`, `chatReady:true` 및 기록 자동 생성을 확정한다.
- 완료 영상은 기존 결과로 이동한다. 분석 취소·완료 결과 재분석은 MVP에 추가하지 않는다. `ANALYSIS-09`는 업로드 대기 중 수집 실패 보고이며 이미 실행된 작업의 취소 API가 아니다.
- AI 모델 원문 응답을 클라이언트에 직접 전달하지 않는다. 검증 후 결과 DTO로 변환한다. 요약의 `highlightSectionIds`, 마인드맵의 `sectionIds`, 상세의 `sectionId`가 동일한 실제 구간을 가리키게 한다.
- 결과의 `SOURCE`와 `AI_SUGGESTION`을 구분한다. 추천 영상은 실제 존재하는 정보로 검증하고, 개인 분석 전에는 RAG 근거로 자동 편입하지 않는다. 추천 준비 중·준비 완료 후 빈 결과·HTTP 실패를 구분한다.

## 8. 기록·폴더·열람 위치

근거: [ARCHIVE-03](https://app.notion.com/p/40c46c2fafdf81778f52d24850731b24), [ARCHIVE-04](https://app.notion.com/p/40c46c2fafdf81359778da25e5e2f60e), [ARCHIVE-10](https://app.notion.com/p/40c46c2fafdf81f99414f8027bccc664).

- 같은 기록을 여러 폴더에 보관할 수 있다. 폴더에서 기록 제거·폴더 삭제·분석 기록 삭제는 서로 다른 동작이다. 폴더 삭제로 분석 기록을 삭제하지 않는다.
- 분석 기록 삭제는 복구 기능 없이 처리하고 검색·RAG 대상에서 제외한다. 공유 영상 메타데이터·자막 객체를 무조건 삭제하지 않는다. 다른 사용자와의 참조 관계에 따른 객체 정리 정책은 별도로 합의한다.
- 기존 대화 텍스트는 유지한다. 삭제된 기록을 가리키는 출처는 `availability:DELETED`로 표시하며 삭제된 원본 조회를 허용하지 않는다.
- 열람 위치 PUT은 `tab`과 `sectionId`가 필수이며 `sectionId:null`을 허용한다. `tab`은 `SUMMARY/MINDMAP/DETAIL`이다. `positionMs`는 재생 위치 복원이 정해지기 전까지 생략 또는 null이고, 생략도 null로 저장한다.
- `lastViewedAt`은 서버 저장 시각이다. 구표의 0 예시보다 최신 열람 위치 상세 페이지의 null 규칙을 따른다. 외부 YouTube 실행만으로 실제 재생 위치를 추정하지 않는다.
- 홈 응답에 기존 아이디어의 카드를 임의 추가하지 않는다. [ARCHIVE-14](https://app.notion.com/p/40d46c2fafdf8130a115d076c2ad55e8)의 현재 필드와 화면 협의 사항을 확인한다.

## 9. 채팅 요청·SSE·복구

근거: [CHAT-04 스트림](https://app.notion.com/p/40c46c2fafdf81069e84ffd0fd5fd1bc), [CHAT-03 메시지 조회](https://app.notion.com/p/40c46c2fafdf8118a678cddeb3f28e54), [CHAT-06 대화 상태](https://app.notion.com/p/40d46c2fafdf81549510e53cc081a4b9).

```http
POST /api/v1/conversations/801/messages
Content-Type: application/json
Accept: text/event-stream
Idempotency-Key: c5ee8dda-7235-4794-a072-2e118cc952a0
```

```json
{
  "clientRequestId": "c5ee8dda-7235-4794-a072-2e118cc952a0",
  "content": "저장한 영상에서 RAG 구현 방식을 비교해 줘",
  "retryOfMessageId": null
}
```

- `clientRequestId`는 전송 전에 생성하며 `Idempotency-Key`와 같다. 서버는 접수한 질문과 생성 답변을 이 값으로 찾을 수 있어야 한다.
- 성공 응답은 HTTP 200, `Content-Type: text/event-stream`, `Cache-Control: no-cache`다. 202 접수 후 별도 스트림을 여는 구조가 아니다.
- 스트림 시작 전 오류는 일반 HTTP 오류·JSON으로 반환한다. 시작 후 오류는 `error` 이벤트를 보내고 종료한다. 이미 시작한 응답의 HTTP 상태를 바꾸려 하지 않는다.
- 이벤트 `data`는 아래처럼 전용 JSON이며 `ApiResponse`로 다시 감싸지 않는다. 각 이벤트 끝에는 빈 줄이 필요하다.

```text
event: meta
data: {"conversationId":"801","userMessageId":"901","assistantMessageId":"902","clientRequestId":"c5ee8dda-7235-4794-a072-2e118cc952a0","scopeRecordCount":4}

event: delta
data: {"assistantMessageId":"902","sequence":1,"text":"선택한 기록에서 답변할 근거를 찾지 못했어요."}

event: sources
data: {"assistantMessageId":"902","sources":[],"usedRecordCount":0}

event: done
data: {"assistantMessageId":"902","status":"COMPLETED","finishReason":"INSUFFICIENT_EVIDENCE","usedRecordCount":0}

```

위는 근거 부족으로 정상 완료한 예시다. 아래는 별개 흐름인 스트림 도중 실패 예시다.

```text
event: error
data: {"assistantMessageId":"902","status":"INCOMPLETE","code":"CHAT_STREAM_FAILED","message":"답변 생성이 중단됐어요.","retryable":true}

```

- `delta.sequence` 순으로 텍스트를 누적한다. `sources`는 검증된 출처 전체 배열이다. `done`은 최종 본문·출처·상태 저장 이후 발행하며 조회 API에서도 같은 값이 보여야 한다.
- 메시지 상태는 `QUEUED/GENERATING/COMPLETED/INCOMPLETE/FAILED`, 완료 이유는 `STOP/LENGTH/INSUFFICIENT_EVIDENCE`다. 아직 완료하지 않았다면 `finishReason:null`이다.
- `scopeRecordCount`는 이번 답변에 허용된 기록 수, `usedRecordCount`는 최종 출처의 중복 없는 `recordId` 수다. chunk 수나 전체 검색 범위 수를 인용 수로 표시하지 않는다.
- 출처의 `recordId/analysisId/sectionId/videoId/startMs/endMs`는 서버가 검증한다. 모델이 만든 임의 ID·URL을 통과시키지 않는다. 권한 없는 기록과 삭제된 기록은 검색·프롬프트 구성 단계부터 제외한다.
- 근거 부족은 `COMPLETED`, `INSUFFICIENT_EVIDENCE`, `sources:[]`, `usedRecordCount:0`으로 끝낸다. 임의 외부 검색으로 보충하거나 서버 오류로 바꾸지 않는다.
- 대화방당 생성은 동시에 1개다. 앱 전송 버튼 제한과 별도로 서버도 진행 중 요청을 409로 막는다. 생성 중인 방 삭제도 409다. 여러 Chat 인스턴스에서 동일하게 동작해야 한다.
- 연결 종료만으로 완료를 판단하지 않는다. 대화 상태의 진행 메시지 ID → 개별 메시지 조회로 복구한다. `meta` 이전에 끊기면 `CHAT-03?clientRequestId=...`로 접수 여부를 찾는다. 이 조회와 cursor를 함께 사용하지 않는다.
- MVP는 `Last-Event-ID` 재생을 제공하지 않는다. 끊김 때문에 자동으로 새 질문을 만들지 않는다. 사용자 재요청에만 새 UUID와 실패·미완료 답변의 `retryOfMessageId`를 사용하고 기존 질문·답변은 유지한다.
- 네트워크 조각과 SSE 이벤트는 다르다. React Native는 POST·헤더 설정 가능한 스트림 클라이언트로 UTF-8 및 이벤트 경계를 조립한다. `: heartbeat`는 표시할 답변이 아니다.
- 참고 범위를 바꾸면 새 대화를 만든다. 기존 범위의 고정/재평가, 답변 Markdown·문단별 인용 위치는 미정이므로 임의의 `blocks` 필드를 추가하지 않는다.

## 10. 기기·알림

근거: [PUSH-01](https://app.notion.com/p/40d46c2fafdf812384d8e6c432468887).

- `installationId`는 앱 설치 단위 UUID이며 기기 하드웨어 ID가 아니다. 플랫폼은 현재 `ANDROID`다.
- `fcmToken`은 필수·nullable이며 알림 권한은 `GRANTED/DENIED/NOT_DETERMINED`로 구분한다. 권한 거절과 서버 분석 실패를 혼동하지 않는다.
- FCM `data`의 값은 개수까지 문자열로 전송한다. 알림의 ID로 서버의 최신 상태·결과를 다시 조회한다.
- 알림 전송 실패·권한 거절로 완료 분석을 실패 처리하거나 분석을 재실행하지 않는다. 알림 수신을 결과 조회의 필수 조건으로 만들지 않는다.

## 11. 구현 전 확인할 명세 차이

아래는 아직 확정되지 않은 항목이다. 관련 기능을 구현할 때 영향과 선택안을 먼저 제시한다.

| 항목 | 현재 차이·필요한 합의 |
| --- | --- |
| 로그인 제외 MVP의 사용자 구분 | 기존 명세는 Bearer·`/users/me`·사용자 소유권을 전제함. 시연용 사용자/설치 식별을 정해야 하며 임의 `userId` 헤더나 전체 공개 기록으로 대체하지 않음 |
| 이용 한도 제외 | 기존 분석·채팅 명세에 `quota`, `quotaReleased`, 한도 오류가 남아 있음. 제거·보류 시 프론트 DTO와 함께 변경하며 가짜 잔여량을 반환하지 않음 |
| 업로드 전 중복·앱 복귀 | `AWAITING_UPLOAD`에는 아직 jobId가 없음. 기존 배치로 돌아가는 응답과 진행 중 배치 재조회 방식이 필요함 |
| 제한값·멱등성 | 입력 길이, 최대 limit, 파일 용량·만료, 멱등 키 보관 기간·범위는 예시와 확정값을 구분해 합의 |
| AI·채팅 범위 | AI 응답 schemaVersion·구간 매핑, RAG 검색 원문, 범위 고정/재평가, 답변 표현·인용 위치를 AI·모바일과 합의 |
| 영상·재생목록 정보 | 메타데이터 수집 주체와 공개 재생목록 조회, 내부 재생목록 그룹 생성·필터 연계는 각 명세의 협의 상태 확인 |

관련 논의: [개발 논의사항](https://www.notion.so/40c46c2fafdf8032a762cbe43121ce41), [백엔드 역할·구현 계획](https://www.notion.so/40d46c2fafdf809c8949e00f884079b0).
