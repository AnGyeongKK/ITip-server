# 분석 배치·작업 API

호출 서버는 `api-app`이다. 로그인·한도 제외 시연 버전에서는 서버의 `DEMO_USER_ID`를 사용한다. 앱이 보낸 사용자 ID 헤더로 소유자를 바꾸지 않는다.

| API | 성공 | 용도 |
| --- | --- | --- |
| `POST /api/v1/analysis-batches` | 201 + `Location` | 영상 선택 묶음 생성·기존 결과/작업 연결 |
| `GET /api/v1/analysis-batches/{batchId}` | 200 | 영상별 현재 상태와 개수 조회 |
| `PATCH /api/v1/analysis-batches/{batchId}/items/{itemId}` | 200 | 서버 접수 전 기기 수집 실패 보고 |
| `POST /api/v1/analysis-jobs` | 202 또는 완료 재사용 200 + `Location` | 실제 GCS 객체 확인 후 분석 접수 |
| `GET /api/v1/analysis-jobs/{jobId}` | 200 | 작업 단계·시도 횟수·결과 조회 |
| `POST /api/v1/analysis-jobs/{jobId}/retries` | 202 또는 완료 재사용 200 + `Location` | 최종 실패 작업의 수동 재시도 |

## 생성과 중복 처리

```http
POST /api/v1/analysis-batches
Content-Type: application/json
Idempotency-Key: <앱이 만든 UUID>
```

```json
{"videoIds":["dQw4w9WgXcQ"],"playlistId":null}
```

가져오기 담당이 `Video`를 먼저 저장한다. 등록되지 않은 영상은 404이며 서버가 영상 길이·제목을 추정하지 않는다. 요청 안의 중복 videoId는 하나로 합친다.

| 현재 데이터 | `items[].action` | 앱의 다음 행동 |
| --- | --- | --- |
| 내 완료 기록 존재 | `OPEN_RESULT` | analysisId·recordId로 결과 열기 |
| 내 진행 작업 존재 | `OPEN_JOB` | jobId 또는 새 batchId로 상태 조회 |
| 신규 영상 | `COLLECT_CAPTIONS` | 기기 수집 → GCS 업로드 → 분석 접수 |
| 내 다른 배치에서 아직 수집·업로드 준비 중 | HTTP 409 `STATE_CONFLICT` | 앱에 저장한 기존 batchId로 복귀 |

같은 사용자·API·`Idempotency-Key`의 재전송은 기존 배치를 반환한다. 동일 키에 다른 본문은 409 `IDEMPOTENCY_CONFLICT`다. 비교할 때 입력 목록 순서와 중복까지 포함하며, 생략한 playlistId와 null은 같은 요청으로 본다. 시연 버전은 멱등 키를 자동 삭제하지 않는다.

**현재 복구 범위:** 알고 있는 batchId로만 다시 조회할 수 있다. 앱은 생성 응답의 batchId를 보관해야 한다. 미업로드 상태에는 아직 jobId가 없으며, 진행 중 배치 목록·기존 준비 배치 ID를 찾아주는 API는 이번 범위에 없다. 이 상태의 중복 선택은 새 수집을 만들지 않고 409로 안내한다.

## 상태 조회

```json
{
  "code":"OK",
  "message":"요청이 완료됐어요.",
  "data":{
    "batchId":"<UUID>",
    "status":"PREPARING",
    "counts":{"total":1,"awaitingUpload":1,"queued":0,"processing":0,"retryWait":0,"completed":0,"failed":0},
    "items":[{
      "itemId":"<UUID>","videoId":"dQw4w9WgXcQ","status":"AWAITING_UPLOAD",
      "jobId":null,"analysisId":null,"recordId":null,"error":null,
      "video":{"videoId":"dQw4w9WgXcQ","title":null,"channelName":null,"channelImageUrl":null,"thumbnailUrl":null,"publishedAt":null,"durationMs":null},
      "stage":null,"uploadDeadlineAt":"2026-11-08T00:30:00Z"
    }],
    "nextPollAfterMs":3000,"playlist":null
  }
}
```

items의 상태를 합산하므로 `total = items.length = 모든 상태 개수의 합`이다. 연결된 job은 원래 다른 배치에서 생성했더라도 현재 job 상태·단계를 사용한다. 모두 완료면 `COMPLETED`, 모두 실패면 `FAILED`, 완료·실패만 섞이면 `PARTIALLY_FAILED`다. 종료 상태의 nextPollAfterMs는 null이다.

조회는 데이터를 변경하지 않는다. 영상·재생목록의 확인되지 않은 메타데이터는 null로 유지한다. `quota`, `quotaReleased`는 로그인·한도 제외 시연 응답에 없다.

## 기기 수집 실패

```json
{"collectionFailure":{"code":"KOREAN_CAPTION_NOT_FOUND"}}
```

허용 code: `KOREAN_CAPTION_NOT_FOUND`, `VIDEO_UNAVAILABLE`, `DURATION_EXCEEDED`, `COLLECTION_RATE_LIMITED`, `UPLOAD_FAILED`. `AWAITING_UPLOAD`에서만 보고할 수 있고 접수된 작업이나 이미 실패한 항목은 409다. 이 오류의 retryable은 false이며 서버 분석 재시도 대상이 아니다. 필요하면 기기 수집·업로드 흐름을 다시 시작한다.

## 서버 설정과 트랜잭션

| 변수 | 용도·로컬 기본값 |
| --- | --- |
| `DEMO_USER_ID` | 고정 시연 사용자·기본 `demo-user` |
| `ANALYSIS_PREPARATION_TTL` | 미업로드 준비 기한·기본 `PT30M` |
| `ANALYSIS_POLL_AFTER_MS` | 앱 권장 조회 간격·기본 3000ms |
| `ANALYSIS_MAX_CAPTION_BYTES` | 실제 GCS 자막 최대 크기·기본 1048576 bytes |

위 기한·간격은 로컬 실행 기본값이며 모바일 회의에서 확정할 서비스 정책은 아니다.

변경 트랜잭션은 `AnalysisOwnerLock.lock(ownerId)`로 사용자 단위 PostgreSQL advisory lock을 먼저 획득한다. 이후 작업 행을 잠글 때에도 **사용자 잠금 → 작업 행 잠금** 순서를 유지한다. API·Worker의 생성·완료 흐름이 같은 순서를 사용해야 하며 외부 GCS·AI 요청 동안 이 잠금을 유지하지 않는다. 조회는 항목·작업·영상을 일괄 조회한다.

일반 오류 형식과 HTTP 상태는 [API 규칙](../.agents/rules/api.md)을 따른다. 없는 배치와 타인 소유 배치는 모두 404다.

## 분석 접수

```http
POST /api/v1/analysis-jobs
Content-Type: application/json
Idempotency-Key: <앱이 만든 UUID>
```

```json
{"batchId":"<UUID>","batchItemId":"<UUID>","uploadId":"<UUID>"}
```

업로드 담당이 저장한 `CaptionUpload` 참조를 받는다. 사용자·배치·항목·영상 연결과 배치 항목의 준비 기한을 확인한다. GCS 객체의 generation·실제 크기·Content-Type을 조회하고 선언한 크기와 `application/json`이 일치하는지 확인한다. 실제 크기는 0보다 크고 서버 최대 크기 이하여야 한다. 서명 URL이 만료되어도 준비 기한 안이고 실제 객체가 있으면 접수할 수 있다. 임의 URL·objectKey·자막 본문은 입력으로 받지 않는다.

```json
{
  "code":"OK","message":"요청이 완료됐어요.",
  "data":{
    "jobId":"<UUID>","batchId":"<UUID>","batchItemId":"<UUID>",
    "status":"QUEUED","statusUrl":"/api/v1/analysis-jobs/<UUID>",
    "analysisId":null,"recordId":null
  }
}
```

`Location`은 `statusUrl`과 같다. 새 접수는 202이며 실제 GCS generation을 작업에 고정하고 업로드를 `CONSUMED`로 변경한다. 같은 사용자·영상의 진행 작업은 다른 배치에서도 참조할 수 있다. 완료 기록이 실제로 존재하면 완료 작업과 결과를 재사용하여 200을 반환한다. 삭제된 기록은 완료 작업만으로 재사용하지 않는다.

동일 키·동일 본문은 기존 job을 반환하고, 동일 키·다른 본문은 409 `IDEMPOTENCY_CONFLICT`다. 새 키를 써도 이미 접수된 동일 업로드·항목은 기존 job을 반환한다. 접수된 항목에 다른 업로드를 연결하면 409 `STATE_CONFLICT`다. 멱등 키는 사용자와 작업별로 구분하며 `SUBMIT_ANALYSIS_JOB`과 `RETRY_ANALYSIS_JOB`은 서로 다른 범위다.

## 작업 조회

```json
{
  "code":"OK","message":"요청이 완료됐어요.",
  "data":{
    "jobId":"<UUID>","batchId":"<UUID>","videoId":"dQw4w9WgXcQ",
    "status":"QUEUED","stage":null,"attempt":0,"maxAttempts":3,
    "analysisId":null,"recordId":null,"error":null,"nextPollAfterMs":3000,
    "updatedAt":"2026-11-08T00:00:00Z",
    "video":{"videoId":"dQw4w9WgXcQ","title":null,"channelName":null,"channelImageUrl":null,"thumbnailUrl":null,"publishedAt":null,"durationMs":null}
  }
}
```

실패 상태도 HTTP 200이며 `error`는 `{code,message,retryable}`이다. 단계는 `VALIDATING`, `SUMMARIZING`, `INDEXING`, `FINISHING` 또는 null이다. `COMPLETED`에서만 `analysisId`·`recordId`를 공개한다. 미완료 draft ID는 숨긴다. `COMPLETED`·`FAILED`의 `nextPollAfterMs`는 null이다. 없는 작업과 타인 소유 작업은 모두 404다.

## 수동 재시도

`POST /api/v1/analysis-jobs/{jobId}/retries`에 UUID `Idempotency-Key`와 JSON `{}`를 보낸다. `FAILED`이며 `error.retryable:true`인 작업만 대상이다. 자동 재시도 대기·진행 작업, 재시도 불가 실패는 409 `JOB_NOT_RETRYABLE`이다.

```json
{
  "code":"OK","message":"요청이 완료됐어요.",
  "data":{"jobId":"<새 UUID>","previousJobId":"<실패 UUID>","batchId":"<새 UUID>","status":"QUEUED","statusUrl":"/api/v1/analysis-jobs/<새 UUID>"}
}
```

새 배치·항목·작업을 만들며 기존 uploadId·generation을 참조한다. `attempt:0`, `maxAttempts:3`으로 시작한다. 기존 draft가 있으면 결과 ID를 내부에 보존하고 `INDEXING`부터 검색 준비 상태를 확인하며, draft가 없으면 `VALIDATING`부터 시작한다. 서명 URL의 기한이 지났어도 실제 원본 generation이 있으면 재시도할 수 있다. 원본이 없거나 버전이 달라졌으면 410 `CAPTION_REUPLOAD_REQUIRED`다.

동일 키는 같은 새 작업을 반환한다. 다른 키로 수동 재시도가 중복되면 409로 차단하여 추가 작업을 만들지 않는다. 이미 완료한 개인 기록이 발견되면 실행 없이 200 `COMPLETED`와 실제 완료 작업의 `statusUrl`을 반환한다. 해당 URL에서 결과 ID를 확인한다. 재시도 멱등 비교에는 원본 jobId가 포함된다.

재시도로 한 번 완료한 결과의 기록을 삭제했다면, 예전 실패 작업의 draft로 그 결과를 복구하지 않는다. 이 경우 409 `JOB_NOT_RETRYABLE`이며 새 배치의 분석 흐름으로 시작한다.

| 오류 | HTTP | 의미 |
| --- | --- | --- |
| `UPLOAD_NOT_READY` | 409 | 배치 준비 기한 만료, 객체 없음·버전·크기·타입 불일치 |
| `CAPTION_TOO_LARGE` | 413 | 선언 또는 실제 크기가 서버 최대 크기 초과 |
| `JOB_NOT_RETRYABLE` | 409 | 수동 재시도 대상이 아니거나 같은 영상의 작업 진행 중 |
| `CAPTION_REUPLOAD_REQUIRED` | 410 | 재시도 원본 객체 없음 또는 generation 변경 |
| `STORAGE_UNAVAILABLE` | 503 | GCS 설정·연결 문제 |

`JobService`는 `JobDbService.prepareSubmit/prepareRetry`와 `admitSubmit/admitRetry` 사이에서 GCS를 조회한다. GCS 호출 중 DB 트랜잭션은 없다. 변경 트랜잭션은 사용자 잠금을 먼저 잡고, 필요한 작업 행 잠금과 멱등·소유권·연결·기한·중복 검사를 다시 수행한다. 작업 생성·항목 연결·업로드 소비·멱등 키 저장은 같은 트랜잭션이다.

통합 테스트는 실제 PostgreSQL/pgvector와 MockMvc를 사용하고 GCS 통신 경계만 대체한다. 실제 GCS 인증·객체 조회와 업로드 URL 발급은 이 테스트에서 실행하지 않는다.
