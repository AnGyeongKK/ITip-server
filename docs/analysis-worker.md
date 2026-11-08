# Worker 결과 저장

`WorkerResultStore`는 검증한 화면 결과와 자막 검색 조각을 짧은 DB 트랜잭션으로 저장한다. 작업 선점은 `JobQueue`가 담당한다. 스케줄러·GCS 다운로드·분석 모델·임베딩 모델 호출은 이 저장 기능에 포함하지 않는다.

## 내부 호출 형식

```java
Optional<Draft> draft(JobQueue.Claim claim, Instant now);
Source source(JobQueue.Claim claim, Instant now);
Draft saveDraft(JobQueue.Claim claim, ResultDocument document, Instant now);
void saveIndex(JobQueue.Claim claim, UUID analysisId, List<CaptionChunker.Chunk> chunks,
               String embeddingModel, List<float[]> vectors, Instant now);
UUID complete(JobQueue.Claim claim, UUID analysisId, Instant now);
```

- `Draft`는 `analysisId`, 복원한 `ResultDocument`, `indexed`를 반환한다.
- `Source`는 `bucket`, `objectKey`, 고정한 `generation`, nullable `durationMs`, `declaredSizeBytes`를 반환한다. 길이가 null이면 실행 담당 코드가 메타데이터 부족을 처리해야 한다. URL 만료만으로 소비한 업로드를 거절하지 않는다.
- `complete()`는 생성한 개인 기록의 `recordId`를 반환한다.
- `now`는 각 DB 호출 시점에 전달한다. 외부 요청 전에 얻은 시각을 외부 요청 완료 후 저장에 재사용하지 않는다. lease 갱신도 저장 호출 밖에서 수행한다.

## 트랜잭션과 재시작

1. `draft()`·`source()`는 읽기 전용이다. 현재 token·lease와 claim의 소유자·영상·배치·업로드·generation이 DB 작업과 같은지 확인한다. 배치·항목·업로드의 실제 소유자·영상 연결도 확인한다.
2. `saveDraft()`는 작업 행을 잠근다. 결과 JSON·서버 section UUID·시간·상세와 작업의 `analysisId`·`INDEXING`을 함께 저장한다. 이미 연결된 private draft가 있으면 처음 저장한 ID와 JSON을 반환한다. 이 시점에 `chat_ready`는 false이고 개인 기록은 없다.
3. 실행 담당 코드는 저장된 화면 구간과 검증한 **자막 원문**으로 `CaptionChunker`를 호출하고, DB 트랜잭션 밖에서 임베딩을 계산한다. AI 요약을 검색 원문으로 바꾸지 않는다.
4. `saveIndex()`는 작업 행을 잠그고 원문·시간·section UUID 배열·원문 segment ID 배열·벡터·모델·차원을 하나의 JDBC batch와 트랜잭션에 저장한다. 중간 행 저장이 실패하면 전체 batch를 되돌린다. 벡터는 조각별 하나, 동일한 양수 차원, 유한한 값, 비영 벡터여야 한다. 모델 이름은 공백이 아닌 255자 이하다.
5. 전체 batch를 한 번에 저장하므로 검색 조각이 1개 이상이고 소유자·구간·모델·차원이 일관되면 `indexed:true`로 재사용한다. 이미 저장한 인덱스는 재호출 시 덮어쓰지 않으며 성공 단계를 되돌리지 않는다. 모델·차원이 섞인 기존 행은 완료로 취급하지 않고 저장도 거절한다.
6. `complete()`는 **소유자 advisory lock → 작업 행 잠금** 순서를 지킨다. JSON과 저장 구간의 일치 및 검색 준비를 확인한 뒤 `FINISHING`·결과 `chat_ready:true`·개인 기록 생성·작업 `COMPLETED`를 함께 확정한다. 기록 저장 등에 실패하면 결과 준비와 단계 변경도 되돌린다.

수동 재시도는 `AnalysisJob.reuseProgress()`가 연결한 이전 실패 작업의 private draft와 소비한 업로드를 재사용한다. 새 작업의 batch item과 업로드 발급 당시 batch item이 다른 것은 정상이다. 소유자·영상·업로드 generation은 같아야 한다. 완료된 결과는 처리 중인 작업에 연결하여 복구하지 않는다.

lease가 만료되거나 다른 token으로 재선점되면 읽기·저장·완료는 `LostJobLeaseException`을 던진다. 완료 후 중복 호출도 같은 예외로 종료하며 결과·조각·기록을 추가하지 않는다. `JobQueue.advance()`도 같은 예외를 사용한다. DB 연결 정보나 저장 데이터가 맞지 않는 경우는 별도의 `IllegalStateException`으로 구분한다.

## 로컬 Worker 실행

`AnalysisScheduler.poll()`은 `AnalysisWorker.runNext()`를 동기로 호출한다. 한 주기가 끝난 뒤 다음 주기를 예약하므로 인스턴스당 한 작업을 처리한다. 여러 인스턴스에서는 기존 `JobQueue.claim(now, leaseDuration)`의 PostgreSQL `FOR UPDATE SKIP LOCKED`로 작업을 나눈다. 응답 이후 분석을 실행하는 별도 스레드는 만들지 않는다. `runNext()`의 boolean은 선점 여부이며 처리 성공 여부가 아니다.

호출 순서는 준비 기한 만료 사용자 조회 → 사용자별 `expire()`의 독립 트랜잭션 → 작업 한 개 선점 → draft 조회 → 필요한 GCS·모델 호출 → 결과 저장 → 원문 조각·임베딩 저장 → 완료다. 준비 만료 조회 또는 특정 사용자의 만료 처리가 실패해도 다른 사용자와 접수한 작업을 계속 처리한다. GCS·모델 대기 동안 DB 트랜잭션은 없고, 각 DB 호출은 호출 직전의 `Clock.instant()`를 받는다.

- draft가 없으면 고정 generation 원문을 읽고 실제 영상 길이·업로드 선언 크기·JSON·원문 범위를 확인한다. 길이가 없으면 `VIDEO_METADATA_REQUIRED`로 실패하며 추정하지 않는다. 검증 후 `SUMMARIZING`에서 모델을 호출하고 서버 UUID를 포함한 draft를 저장한다.
- draft가 있으면 원래 서버 UUID와 JSON을 재사용하고 생성을 반복하지 않는다. 인덱스가 없을 때만 고정 generation 원문을 다시 읽고 저장된 화면 구간에 맞춰 임베딩한다. 인덱스까지 있으면 GCS·모델을 다시 호출하지 않고 완료한다. 이미 성공한 단계를 `VALIDATING`으로 되돌리지 않는다.
- 작업마다 heartbeat를 등록하고 첫 실행 및 이후 간격마다 `JobQueue.renew()`로 lease를 연장한다. 완료·실패·lease 상실 시 예약을 취소한다. 폴링과 heartbeat는 플랫폼 스레드 두 개를 가진 단일 `ThreadPoolTaskScheduler`를 공유하며, 취소한 예약은 즉시 제거한다. Virtual Threads는 비활성화한다.
- heartbeat 갱신 실패는 새 실행을 실패 처리하지 않는다. 각 저장 시점의 run token·lease 확인이 최종 권한을 판단한다. `LostJobLeaseException` 후에는 결과 저장·실패 전환을 추가 호출하지 않는다. processor와 heartbeat는 `MDC.jobId`를 넣고 종료 시 제거한다.

| 설정 / 환경변수 | 로컬 기본값·조건 |
| --- | --- |
| `itip.worker.scheduling-enabled` / `WORKER_SCHEDULING_ENABLED` | `true`. `false`이면 자동 폴링 Bean을 만들지 않으며 processor·DB는 수동 호출 가능 |
| `itip.worker.poll-delay` / `WORKER_POLL_DELAY` | `PT2S`, 양수 |
| `itip.worker.lease-duration` / `WORKER_LEASE_DURATION` | `PT2M`, 양수 |
| `itip.worker.heartbeat-delay` / `WORKER_HEARTBEAT_DELAY` | `PT20S`, 양수이며 lease보다 짧음 |
| `itip.worker.retry-delay` / `WORKER_RETRY_DELAY` | `PT5S`, 0 이상 |
| `itip.worker.chunk-code-points` / `WORKER_CHUNK_CODE_POINTS` | `1200`, 양수 Unicode 문자 수 |
| `itip.analysis.max-caption-bytes` / `ANALYSIS_MAX_CAPTION_BYTES` | API와 공유하는 `1048576`, 양수이며 `Integer.MAX_VALUE` 미만 |

실행은 마이그레이션을 끝낸 로컬 PostgreSQL과 연결 환경변수를 준비한 뒤 `ITip/`에서 `./gradlew :apps:worker-app:bootRun`으로 한다. 실제 GCS 원문·모델을 사용하려면 [로컬 실행](local-run.md) 및 [분석 모델 설정](analysis-ai-contract.md#spring-ai-모델-어댑터)의 설정이 필요하다. 모델 Bean이 없으면 `AI_NOT_CONFIGURED`로 명확히 실패하며 완료 결과를 만들지 않는다. 공급자 선정·API 키·네트워크 설정은 이 실행 기능에 포함하지 않는다. 실제 모델 Bean 제공자가 HTTP connect/read/전체 timeout과 SDK 시도 횟수 1을 설정·검증해야 한다.

## 실행 실패와 재시도

실패는 민감한 원문 예외 메시지 대신 고정 사용자 메시지와 코드로 저장한다. 로그에는 작업 ID·고정 코드·예외 클래스를 기록한다. 자막 원문·모델 raw 응답·prompt·서명 URL·원래 exception message/cause를 기록하지 않는다.

| 실패 | 코드·자동 재시도 |
| --- | --- |
| 자막 검증 | `InvalidCaptionException` 코드 그대로, 불가. 실제/선언 크기 불일치는 `CAPTION_SIZE_MISMATCH` |
| 분석 응답 검증 | `AI_INVALID_RESPONSE`, 불가 |
| AI 어댑터 실패 | `AnalysisAiException.code()/retryable()` 그대로 |
| GCS 객체 없음·generation 변경 | `CAPTION_REUPLOAD_REQUIRED`, 불가 |
| GCS 크기 초과·설정 없음·잘못된 metadata | 원래 코드, 불가 |
| GCS 일시 통신 실패 | `STORAGE_UNAVAILABLE`, GCS의 `retryable` 그대로 |
| 일시 DB 오류·복구 가능한 DB 오류·연결 자원 실패 | `WORKER_DATABASE_UNAVAILABLE`, 가능 |
| 영구 DB 오류·잘못된 저장 형식·벡터·상태 | `WORKER_DATA_INVALID`, 불가 |
| 그 외 runtime 오류 | `WORKER_ERROR`, 불가. 분류되지 않은 오류를 무조건 반복하지 않음 |

`JobQueue.fail()`이 최초 실행을 포함한 최대 3회와 다음 실행 시각을 결정한다. 실패 기록 자체의 DB 오류는 폴링 바깥에서 안전한 코드로 기록하고 lease 만료 복구에 맡긴다. `poll()`은 runtime 오류를 잡아 이후 주기를 계속 실행한다. 알림·쿼터·취소·외부 메시지 큐는 추가하지 않는다.

## 미업로드 준비 기한

`ExpiredPreparationService.findOwners(now)`는 기한이 지난 `AWAITING_UPLOAD`의 사용자만 찾는다. 실행 담당 코드는 사용자마다 `expire(ownerId, now)`를 별도 호출한다. 사용자 잠금 후 Entity의 `expireAwaitingUpload()`로 실패를 확정하며, 이미 접수한 작업·아직 유효한 준비 항목·기존 실패 원인은 바꾸지 않는다. 여러 사용자 잠금을 하나의 트랜잭션에서 모으지 않는다.

## 확인 범위

`ITip/`에서 아래 명령으로 `pgvector/pgvector:pg17` Testcontainers를 사용한다. Docker가 실행 중이어야 하며 운영 DB는 사용하지 않는다. 테스트에서만 Flyway를 활성화하고 운영 Worker의 마이그레이션 설정은 바꾸지 않는다.

```sh
./gradlew :apps:worker-app:test --tests '*WorkerResultStoreTest'
```

실제 PostgreSQL의 draft 재사용·벡터/배열 값·잘못된 임베딩·batch rollback·완료 rollback·중복 완료·만료 token·수동 재시도를 확인한다. 실제 GCS·AI 모델·임베딩 품질·Python 채팅 연결은 별도 검증 대상이다. 공개 결과·채팅 검색의 연결 범위는 [analysis-integration.md](analysis-integration.md), 화면 결과 형식은 [analysis-ai-contract.md](analysis-ai-contract.md)를 따른다.

`./gradlew :apps:worker-app:test --tests '*AnalysisWorkerTest'`는 실제 PostgreSQL·Spring 컨텍스트·실제 parser/validator/AI 어댑터를 연결하고 GCS·Spring AI `ChatModel`·`EmbeddingModel` 통신만 대체한다. 정상 완료의 결과·구간·vector·기록, 외부 대기의 트랜잭션 부재, 오류·재시도 상한, draft ID·JSON 재사용, 인덱스 이후 완료 재시도, 오래된 token의 저장 차단, 모델 대기 중 실제 heartbeat commit·종료 후 예약 취소, runtime 이후 실제 주기 호출 지속, 준비 만료와 접수 작업의 분리를 확인한다. 완료 저장의 일시 잠금 실패는 DB 경계에서 한 번 주입하며 실제 DB 장애를 재현한 검증은 아니다. 고정 모델 JSON은 테스트용 외부 응답 예시다. 실제 GCS 인증·공급자 인증·HTTP timeout·요약/임베딩 품질·Python 서버는 검증하지 않는다.
