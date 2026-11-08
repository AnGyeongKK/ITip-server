# 로컬 실행

## 준비

- Java 21과 Docker Desktop을 실행한다. IntelliJ의 Project SDK·Gradle JVM도 21로 맞춘다.
- `ITip/`에서 명령을 실행한다. 비밀값은 각자 로컬 환경변수로 설정하며 `.env` 파일을 생성·공유·커밋하지 않는다.
- `DB_PASSWORD`를 로컬에서 설정한 뒤 `docker compose up -d db`로 개발 DB를 실행한다. 데이터는 Docker volume에 유지된다.

| 환경변수 | 용도·기본값 |
| --- | --- |
| `DB_PASSWORD` | 개발 DB 비밀번호. 필수이며 실제 값은 문서에 기록하지 않음 |
| `DB_URL` | 앱 연결 주소. 기본 `jdbc:postgresql://localhost:15432/itip` |
| `DB_USER`, `DB_NAME`, `DB_PORT` | DB 사용자·Compose DB명·노출 포트. 기본 `itip`, `itip`, `15432` |
| `DB_POOL_MAX` | 프로세스별 최대 DB 연결 수. 기본 5 |
| `WORKER_PORT` | Worker HTTP 포트. 기본 8081 |

`DB_NAME`·`DB_PORT`를 변경하면 앱의 `DB_URL`도 같은 DB를 가리키게 설정한다. 기존 volume이 있으면 환경변수 변경만으로 DB 계정·비밀번호가 바뀌지 않는다. 개인 데이터가 있는 volume을 임의로 삭제하지 않는다.

## 실행 순서

```sh
docker compose up -d db
./gradlew :apps:api-app:bootRun
```

API의 Flyway 적용 완료 후 다른 터미널에서 Worker를 실행한다.

```sh
./gradlew :apps:worker-app:bootRun
```

스키마는 Flyway가 생성·변경하고 두 앱은 `ddl-auto=validate`로 검사한다. Worker는 마이그레이션을 실행하지 않는다. 운영 Supabase를 로컬 테스트에 사용하지 않는다.

## 테스트

```sh
./gradlew test
./gradlew build
```

통합 테스트는 Testcontainers가 별도 PostgreSQL/pgvector 컨테이너와 임시 인증정보를 생성·정리한다. 개발 DB를 비우거나 운영 데이터에 접근하지 않는다. Docker가 꺼져 있으면 통합 테스트가 실패하므로 Docker를 실행한다.
