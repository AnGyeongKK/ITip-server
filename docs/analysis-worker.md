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

## 확인 범위

`ITip/`에서 아래 명령으로 `pgvector/pgvector:pg17` Testcontainers를 사용한다. Docker가 실행 중이어야 하며 운영 DB는 사용하지 않는다. 테스트에서만 Flyway를 활성화하고 운영 Worker의 마이그레이션 설정은 바꾸지 않는다.

```sh
./gradlew :apps:worker-app:test --tests '*WorkerResultStoreTest'
```

실제 PostgreSQL의 draft 재사용·벡터/배열 값·잘못된 임베딩·batch rollback·완료 rollback·중복 완료·만료 token·수동 재시도를 확인한다. 실제 GCS·AI 모델·임베딩 품질·스케줄 실행·Python 채팅 연결은 별도 검증 대상이다. 공개 결과·채팅 검색의 연결 범위는 [analysis-integration.md](analysis-integration.md), 화면 결과 형식은 [analysis-ai-contract.md](analysis-ai-contract.md)를 따른다.
