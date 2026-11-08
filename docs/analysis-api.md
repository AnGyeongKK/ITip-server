# 분석 배치 API

호출 서버는 `api-app`이다. 로그인·한도 제외 시연 버전에서는 서버의 `DEMO_USER_ID`를 사용한다. 앱이 보낸 사용자 ID 헤더로 소유자를 바꾸지 않는다.

| API | 성공 | 용도 |
| --- | --- | --- |
| `POST /api/v1/analysis-batches` | 201 + `Location` | 영상 선택 묶음 생성·기존 결과/작업 연결 |
| `GET /api/v1/analysis-batches/{batchId}` | 200 | 영상별 현재 상태와 개수 조회 |
| `PATCH /api/v1/analysis-batches/{batchId}/items/{itemId}` | 200 | 서버 접수 전 기기 수집 실패 보고 |

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

위 기한·간격은 로컬 실행 기본값이며 모바일 회의에서 확정할 서비스 정책은 아니다.

변경 트랜잭션은 `AnalysisOwnerLock.lock(ownerId)`로 사용자 단위 PostgreSQL advisory lock을 먼저 획득한다. 이후 작업 행을 잠글 때에도 **사용자 잠금 → 작업 행 잠금** 순서를 유지한다. API·Worker의 생성·완료 흐름이 같은 순서를 사용해야 하며 외부 GCS·AI 요청 동안 이 잠금을 유지하지 않는다. 조회는 항목·작업·영상을 일괄 조회한다.

일반 오류 형식과 HTTP 상태는 [API 규칙](../.agents/rules/api.md)을 따른다. 없는 배치와 타인 소유 배치는 모두 404다.
