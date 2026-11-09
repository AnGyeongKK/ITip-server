# 로컬 실행

## API 호출 구조 보기

[`yousum-api-pipelines.html`](yousum-api-pipelines.html)을 브라우저로 연다. API를 선택하면 전체 서버 경로·트랜잭션·함수·PR #10·#11·#12·관련 커밋을 볼 수 있다. 구현 14개와 후속 설계 24개를 구분하며, 결과·구간 GET은 저장 DB만 읽는 경로로 표시한다. 실제 GCS·모델 연동은 별도로 확인해야 한다.

편집 원본은 [`visualizations/yousum-api-pipelines.fragment.html`](visualizations/yousum-api-pipelines.fragment.html)이다. 배포용 HTML은 이 원본에 테마·미리보기 실행 환경을 포함한 파일이며, 수정 후 두 파일의 동작을 함께 확인한다.

## 준비

실제 Java 소스와 테스트 위치는 다음과 같다. `ITip/`는 Gradle 집계 루트이며 최상위 `ITip/src/`는 사용하지 않는다. IntelliJ도 `ITip/settings.gradle`에 등록된 모듈을 기준으로 불러온다.

| 모듈 | 소스·설정 | 테스트 |
| --- | --- | --- |
| API | `ITip/apps/api-app/src/main` | `ITip/apps/api-app/src/test` |
| Worker | `ITip/apps/worker-app/src/main` | `ITip/apps/worker-app/src/test` |
| Chat | `ITip/apps/chat-app/src/main` | `ITip/apps/chat-app/src/test` |
| 공통 응답·예외 | `ITip/libs/common/src/main` | `ITip/libs/common/src/test` |
| Entity·Repository·공유 결과 | `ITip/libs/domain/src/main` | `ITip/libs/domain/src/test` |

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
| `CHAT_PORT` | Chat HTTP 포트. 기본 8082 |
| `DEMO_USER_ID` | API·Chat에서 동일하게 설정하는 데모 소유자. 기본 demo-user |

`DB_NAME`·`DB_PORT`를 변경하면 앱의 `DB_URL`도 같은 DB를 가리키게 설정한다. 기존 volume이 있으면 환경변수 변경만으로 DB 계정·비밀번호가 바뀌지 않는다. 개인 데이터가 있는 volume을 임의로 삭제하지 않는다.

## 실행 순서

```sh
docker compose up -d db
./gradlew :apps:api-app:bootRun
```

API의 Flyway 적용 완료 후 다른 터미널에서 Worker를 실행한다.

```sh
./gradlew :apps:worker-app:bootRun
# 별도 터미널
./gradlew :apps:chat-app:bootRun
```

스키마는 API의 Flyway가 생성·변경하고 세 앱은 `ddl-auto=validate`로 검사한다. Worker·Chat은 마이그레이션을 실행하지 않는다. 운영 Supabase를 로컬 테스트에 사용하지 않는다.

영상 분석을 완료하려면 GCS 인증과 **`ChatModel`·`EmbeddingModel` Bean**이 필요하다. 현재 저장소는 공급자 공통 Spring AI 어댑터까지 포함하며 특정 공급자 starter·모델·API 키 자동 설정은 포함하지 않는다. [AI 문서와 현재 코드의 차이](analysis-ai-contract.md#현재-반영-상태와-합의할-차이)를 확인한 뒤 Worker에 두 Bean과 HTTP 타임아웃·SDK 총 시도 1회(내부 자동 재시도 0회)를 설정한다. 키 환경변수만 추가해도 모델 Bean이 생성되는 구조는 아니다. 설정 전에도 앱·DB 조회는 시작되지만 분석 작업은 `AI_NOT_CONFIGURED`로 실패한다. Python 채팅 서버 연동은 별도 기능이다.

자막·AI 출력 JSON, 환경변수, 단계별 재시도와 함수 흐름은 [분석 Worker 안내](analysis-worker.md)를 따른다. Worker는 기본 2초마다 작업을 조회한다. `WORKER_SCHEDULING_ENABLED=false`는 수동 점검용이며 시연에서는 기본값 `true`로 실행한다.

## API·Worker를 별도 컨테이너로 실행

`ITip/`를 Docker 빌드 컨텍스트로 사용한다. 각 Dockerfile은 Java 21 JRE와 해당 앱의 실행 JAR만 포함하며 root 사용자로 실행하지 않는다. `.dockerignore`는 JAR·Dockerfile만 허용하므로 소스·환경변수 파일·인증 파일은 빌드 컨텍스트에 보내지 않는다.

```sh
./gradlew :apps:api-app:bootJar :apps:worker-app:bootJar
docker build -f apps/api-app/Dockerfile -t itip-api:local .
docker build -f apps/worker-app/Dockerfile -t itip-worker:local .

docker compose up -d db
docker run --rm --name itip-api --network itip-local_default \
  --publish 127.0.0.1:8080:8080 \
  --env DB_PASSWORD --env DB_USER \
  --env DB_URL="jdbc:postgresql://db:5432/${DB_NAME:-itip}" \
  itip-api:local
```

API가 시작되고 Flyway 적용이 끝나면 다른 터미널에서 Worker를 실행한다. 같은 Docker 네트워크에서는 DB 주소가 `db:5432`이며 호스트의 `localhost:15432`와 다르다.

```sh
docker run --rm --name itip-worker --network itip-local_default \
  --publish 127.0.0.1:8081:8081 \
  --env DB_PASSWORD --env DB_USER \
  --env DB_URL="jdbc:postgresql://db:5432/${DB_NAME:-itip}" \
  itip-worker:local
```

코드를 수정하면 `bootJar`와 두 이미지 빌드를 다시 실행한다. 실행 JAR 이름이 바뀌면 `--build-arg JAR_FILE=...`로 해당 경로를 전달한다.

Docker Hub 인증 문제로 기본 이미지를 받지 못할 때에는 공식 이미지의 공개 미러를 빌드 인수로 지정할 수 있다.

```sh
docker build -f apps/api-app/Dockerfile -t itip-api:local \
  --build-arg JAVA_IMAGE=public.ecr.aws/docker/library/eclipse-temurin:21-jre .
```

GCS 인증은 이미지에 넣지 않는다. 기존 로컬 인증 파일을 읽기 전용으로 마운트하고 컨테이너의 `GOOGLE_APPLICATION_CREDENTIALS`가 그 경로를 가리키게 설정한다. 파일 소유권 때문에 읽지 못하면 로컬 사용자 UID/GID로 실행하는 방식을 사용한다. 실제 인증 파일·환경변수 파일을 저장소나 팀 채팅으로 공유하지 않는다.

## Compose로 서버 3개 함께 실행

기존 `compose.yaml`은 DB만 실행한다. `compose.demo.yaml`을 함께 지정하면 API·Worker·Chat도 실행한다. Android 설치·APK 이미지 전달은 [프론트엔드 전달 문서](frontend-demo-handoff.md)를 참고한다.

Java 21 환경에서 실행 JAR와 이미지를 먼저 준비한다. 이 Compose는 JAR를 빌드하지 않으며 로컬 이미지 또는 팀이 배포한 이미지 태그를 실행한다.

아래 `docker build`는 기본적으로 현재 CPU용 로컬 이미지를 만든다. **ARM Mac ↔ Intel/AMD Windows 간 공유에는 [두 플랫폼 빌드·배포 규칙](container-platforms.md)을 따른다.** 공유 이미지는 `linux/arm64,linux/amd64`로 배포하고 Compose는 실행 기기에 맞는 이미지를 선택한다. Windows는 Docker Desktop의 Linux containers/WSL 2 환경을 사용한다.

```sh
# ITip/에서 실행
./gradlew :apps:api-app:bootJar :apps:worker-app:bootJar :apps:chat-app:bootJar
docker build -f apps/api-app/Dockerfile -t itip-api:local .
docker build -f apps/worker-app/Dockerfile -t itip-worker:local .
docker build -f apps/chat-app/Dockerfile -t itip-chat:local .

# DB_PASSWORD는 앞의 준비 절에 따라 각자 로컬 shell에 설정
docker compose -f compose.yaml -f compose.demo.yaml up -d
docker compose -f compose.yaml -f compose.demo.yaml ps
docker compose -f compose.yaml -f compose.demo.yaml logs --tail=50 api worker chat
```

DB healthcheck → API Flyway·JPA 초기화 후 TCP 포트 준비 → Worker·Chat 순서다. API healthcheck는 GCS·AI 정상 여부를 검사하지 않는다. Worker·Chat의 실제 시작 실패는 `ps`·`logs`에서 확인한다. 새 버전에서 마이그레이션이 추가되면 실행 중인 Worker·Chat을 `stop worker chat`으로 중단한 뒤 `up -d`로 API부터 교체하고 다시 `start worker chat`한다. 기존 컨테이너에 depends_on이 마이그레이션 동기화를 자동 보장하지는 않는다.

| 변수 | 용도·기본값 |
| --- | --- |
| `API_IMAGE`, `WORKER_IMAGE`, `CHAT_IMAGE` | 기본 `itip-api:local`, `itip-worker:local`, `itip-chat:local`. 원격 배포 시 버전 태그 지정 후 같은 Compose 명령의 `pull` 실행 |
| `API_PORT`, `CHAT_PORT` | 호스트 포트 8080·8082. 앱의 내부 포트는 고정. 변경하면 Android reverse·앱 주소도 확인 |
| `YOUTUBE_API_KEY` | API 컨테이너에만 전달. 신규 영상·공개 재생목록의 실제 메타데이터 조회에 필요 |
| `CHAT_AI_BASE_URL`, `CHAT_AI_TOKEN` | 후속 Python 연결용 전달 변수. 현재 `dev`의 Chat에는 질문 생성 기능이 없어 설정만으로 활성화되지 않음 |

포트는 `127.0.0.1`에만 바인딩한다. Worker 포트는 호스트에 열지 않는다. Docker Desktop에서 호스트 Python에 연결할 때는 `localhost` 대신 `host.docker.internal`을 사용한다. `.env` 파일은 생성·배포하지 않는다. `docker compose config`는 치환된 비밀값을 출력할 수 있으므로 공유용으로 실행하지 말고 형식만 확인할 때는 `config --quiet`을 사용한다.

GCS 파일이 필요하면 각자 준비한 인증 파일의 **절대경로**를 로컬 `GCS_CREDENTIALS_PATH`로 설정한다. 아래 선택 overlay가 API·Worker에 읽기 전용 마운트한다. 인증 파일은 이미지에 들어가지 않는다.

```sh
export LOCAL_UID="$(id -u)"
export LOCAL_GID="$(id -g)"
# GCS_CREDENTIALS_PATH는 본인 로컬 인증 파일로 설정한 상태
docker compose -f compose.yaml -f compose.demo.yaml -f compose.gcs.yaml up -d
```

이 overlay는 파일 접근 권한을 위해 앱을 현재 호스트 UID/GID로 실행한다. 인증 파일이 없으면 시작 전에 실패한다. 버킷 접근·서명 권한과 Worker의 모델 Bean 설정은 별도로 필요하다. GCS overlay로 실행했다면 이후 `ps`·`logs`·`down`에도 같은 세 파일을 지정한다. `down`은 volume을 유지하며 **데이터 보존을 위해 `down -v`를 사용하지 않는다.**

## 테스트

저장된 완료 결과는 아래 GET으로 조회한다. 로그인 제외 MVP이므로 서버의 `DEMO_USER_ID` 설정을 사용하며 임의 사용자 헤더로 소유자를 바꾸지 않는다. 새 환경변수·SQL은 필요 없다.

```sh
curl 'http://localhost:8080/api/v1/analyses/<analysisId>'
curl 'http://localhost:8080/api/v1/analyses/<analysisId>/sections/<sectionId>'
```

`<analysisId>`·`<sectionId>`는 서버에서 받은 실제 UUID로 바꾼다.

첫 응답의 `sections[].sectionId`를 상세 경로에 사용한다. `mindmap.sectionIds` 순서로 주제를 배치하고 `subtopics`를 하위 항목으로 그린다. 두 API는 AI를 재호출하지 않으며 구간 상세에 원본 링크·영상 카드·전체 한 줄 요약을 함께 제공한다. 오류와 저장 경계는 [AI 결과 계약](analysis-ai-contract.md#결과구간-조회-구현-5)을 따른다. 추천 영상 API는 아직 후속 범위다.

```sh
./gradlew test
./gradlew build
```

통합 테스트는 Testcontainers가 별도 PostgreSQL/pgvector 컨테이너와 임시 인증정보를 생성·정리한다. 개발 DB를 비우거나 운영 데이터에 접근하지 않는다. Docker가 꺼져 있으면 통합 테스트가 실패하므로 Docker를 실행한다.
