# 영상 분석 작업 처리 검토 기록

범위: 이슈 #4, `4-feat-analysis` → `dev`. 각 구현 커밋 직후 별도 에이전트가 읽기 전용으로 검토했다. `FIX`는 다음 수정 커밋의 재검토 후 해소했다. 커밋 본문에는 파일별 핵심 로직·실행한 확인·설계 문서 `Refs:`가 있다.

## 커밋별 결과

| 커밋 | 판정 | 확인·수정 연결 |
| --- | --- | --- |
| [ff442df](https://github.com/AnGyeongKK/ITip-server/commit/ff442df) | PASS | 문서·리뷰 절차의 차단 문제 없음. 기능 및 외부 연동은 미검증으로 구분. |
| [0202dab](https://github.com/AnGyeongKK/ITip-server/commit/0202dab) | PASS | 독립 JAR·Start-Class·모듈 의존과 기존 코드 이동 확인. DB 검증은 다음 단위. |
| [043d312](https://github.com/AnGyeongKK/ITip-server/commit/043d312) | PASS | 실제 PostgreSQL/Flyway vector 검증·테스트 DB 격리·localhost Compose 확인. 외부 GCS/AI 미검증. |
| [dee7334](https://github.com/AnGyeongKK/ITip-server/commit/dee7334) | PASS | JSON null·오류 HTTP 상태·요청 ID와 MDC 정리. 집중 테스트 22개 성공. |
| [a058ff5](https://github.com/AnGyeongKK/ITip-server/commit/a058ff5) | PASS | 자막 검증·경계조건 확인. 실제 집중 테스트 7개 성공(커밋본문 8개는 오기). GCS연결은 후속 단위. |
| [d177cbe](https://github.com/AnGyeongKK/ITip-server/commit/d177cbe) | PASS | 상태 제약·최대 3회·runToken·진행 작업의 부분 UNIQUE 인덱스. 모델 테스트 14개 성공. |
| [319c289](https://github.com/AnGyeongKK/ITip-server/commit/319c289) | FIX | P2: SDK timeout/408 재시도 판정 누락. cf7723d에서 수정. |
| [cf7723d](https://github.com/AnGyeongKK/ITip-server/commit/cf7723d) | PASS | SDK `isRetryable`·408 보존, 403 재시도 차단. 집중 테스트 6개 성공. |
| [cc1f15d](https://github.com/AnGyeongKK/ITip-server/commit/cc1f15d) | PASS | draft 완료 전환·개인 기록·V3/JPA·벡터 차원. 이 시점 전체 55개 성공. |
| [16193b6](https://github.com/AnGyeongKK/ITip-server/commit/16193b6) | PASS | 동시 선점·lease 인계·오래된 실행 차단·최대 3회. 실제 DB 테스트 4개 성공. |
| [f19461d](https://github.com/AnGyeongKK/ITip-server/commit/f19461d) | PASS | 배치 API의 멱등·소유권·연결 작업 상태·null·counts. 실제 DB/HTTP 테스트 11개 성공. |
| [11f28a6](https://github.com/AnGyeongKK/ITip-server/commit/11f28a6) | FIX | P2: 구간 상세가 다른 주제의 원문을 근거로 허용. ee744e6에서 해당 구간의 원문으로 제한. |
| [ee744e6](https://github.com/AnGyeongKK/ITip-server/commit/ee744e6) | PASS | 구간 밖 상세 근거 3종 차단·전체 요약의 원문 참조 유지. 테스트 9개 성공. |
| [5bb146f](https://github.com/AnGyeongKK/ITip-server/commit/5bb146f) | PASS | 작업 API 3개의 소유권·멱등·동시 접수·GCS 트랜잭션 경계. 전체 106개 성공. 삭제 후 재복구 예외는 bebbc8b에서 추가 검증. |
| [bebbc8b](https://github.com/AnGyeongKK/ITip-server/commit/bebbc8b) | PASS | 과거 실패 작업으로 삭제된 완료 기록을 복구하지 못하게 차단. 작업 API 테스트 21개 성공. |
| [273fd7f](https://github.com/AnGyeongKK/ITip-server/commit/273fd7f) | PASS | 원문·Unicode·시간·구간 연결과 재현 가능한 chunk ID. 집중 테스트 3개 성공. |
| [698e0d5](https://github.com/AnGyeongKK/ITip-server/commit/698e0d5) | PASS | 저장 원자성·소유권·generation·벡터·수동 재시도. 실제 DB 테스트 21개, 전체 131개 성공. |
| [a39f94f](https://github.com/AnGyeongKK/ITip-server/commit/a39f94f) | PASS | Spring AI JSON 변환·임베딩 순서/모델·오류 분류. 21개 추가, 전체 152개 성공. 실제 공급자 미검증. |
| [f88ddc6](https://github.com/AnGyeongKK/ITip-server/commit/f88ddc6) | PASS | 준비 항목 만료를 사용자별 별도 트랜잭션으로 처리. 실제 DB 테스트 3개 성공. |
| [85598e5](https://github.com/AnGyeongKK/ITip-server/commit/85598e5) | PASS | 실행 흐름·heartbeat·lease·단계 재사용·실패/로그. Worker 시나리오 31개, 전체 186개 성공. |
| [b4f1f16](https://github.com/AnGyeongKK/ITip-server/commit/b4f1f16) | PASS | Docker 빌드·비root 실행·컨텍스트 제외·독립 시작 순서. 최신 이미지/임시 DB 실행 확인. |
| [f2db5b3](https://github.com/AnGyeongKK/ITip-server/commit/f2db5b3) | PASS | 리뷰 기록의 SHA 21개·문서 링크·XML 수치와 미확인 범위 일치 확인. |

## 최종 검증

- 전체 소스 추가 검토: API 접수·재시도, 잠금·lease/runToken, Worker 완료 저장, 자막·AI 응답 검증, GCS와 V2/V3 SQL에서 추가 차단 결함을 찾지 못했다.
- `Code review: skipped (ce-code-review unavailable)` — 전체 리뷰 절차를 시도했지만 현재 실행 지침상 독립 merge/report 단계를 실행할 수 없어 완료 receipt를 얻지 못했다. 이 결과를 완료 리뷰로 계산하지 않는다. 위 커밋별 독립 리뷰와 전체 소스 수동 검토가 실제 수행 범위다. 실행 ID: `20261108-105848-792f1ad8`.
- Java 21 `./gradlew test build --no-daemon`: **186 tests**, 실패·오류·skip 0. API 61 / Worker 99 / common 6 / domain 20.
- 실제 PostgreSQL/pgvector Testcontainers에서 중복 접수·동시 선점·lease 인계·자동/수동 재시도·완료 원자성과 checkpoint 재사용을 확인했다.
- Worker 31개 시나리오는 실제 파서·Spring AI 어댑터·검증기·PostgreSQL을 연결한다. GCS·모델 통신만 테스트 경계에서 대체한다. DB 완료 저장 실패는 저장 경계에 오류를 주입한 경우이며 실제 DB 장애 실험은 아니다.
- 최신 API·Worker 이미지 빌드 및 임시 DB에서 각각 시작: Flyway V1~V3, 배치 생성 201·수집 실패 보고 200·배치 실패 조회 200·서버 UUID 요청 ID·UID/GID 10001 확인. 테스트 컨테이너·네트워크만 정리했다.
- `git diff --check`, 민감 파일 추적 제외, 변경 소스의 키 패턴 검사 통과. 이 검사는 모든 종류의 비밀값 탐지를 보장하지 않는다.
- `a058ff5` 본문 테스트 수 8개는 오기이며 실제 성공 수는 **7개**다.
- [Draft PR #10](https://github.com/AnGyeongKK/ITip-server/pull/10) 생성 후 [API 호출 구조](yousum-api-pipelines.html)에 실제 함수·PR·커밋을 연결했다. API 선택 38개·폭/테마 배치 60조합을 브라우저로 확인했으며 실행 오류·가로 넘침이 없었다. 후속 설계 32개는 이번 구현으로 표시하지 않는다.

## 외부 연동 확인 전 조건

- 실제 GCS 인증·객체 접근, 실제 AI 공급자의 인증·HTTP 타임아웃·SDK 재시도·분석 품질은 확인하지 못했다.
- Worker의 `ChatModel`·`EmbeddingModel` Bean은 AI 파트가 공급자·모델 선택 후 연결해야 한다. 현재는 공통 어댑터만 있고 특정 공급자 starter나 키 자동 설정은 없다. Bean 누락은 `AI_NOT_CONFIGURED`다.
- 영상 가져오기·업로드 URL 발급·분석 결과 GET·보관함·검색·Python 채팅·알림 API는 다른 기능 범위다. 이 PR의 6개 API와 완료 데이터 저장을 이 기능들과 연결해야 전체 앱 시연이 된다.
- API의 시연 사용자는 서버의 `DEMO_USER_ID`로 고정되어 있다. 앱별 사용자 인증·운영 공개 배포를 검증한 버전이 아니다.

실행·JSON 계약은 [로컬 실행](local-run.md), [API](analysis-api.md), [Worker](analysis-worker.md), [AI](analysis-ai-contract.md), [담당 연결](analysis-integration.md)을 참조한다.
