# YouSum 프로젝트 규칙

## 프로젝트 범위

- Spring Boot 프로젝트는 `ITip/`에 있다. Gradle 명령은 이 디렉터리에서 실행한다.
- Java 21, Spring Boot 4.1.2, Gradle Wrapper, Spring MVC, JPA를 기준으로 개발한다.
- 현재 시연용 MVP는 영상·재생목록 가져오기, 분석·결과, 기록·폴더, 검색, AI 대화, 기기·알림이다. 로그인·결제·요금제·이용 한도는 구현 범위에서 제외한다.
- 데모는 로컬 PostgreSQL(pgvector)·DB 작업 조회·Spring Worker로 실행한다. 파일은 실제 GCS의 presigned URL로 업로드한다. GPT·RAG 채팅은 AI 파트의 Python 서버와 연동한다.
- 기획·API 변경이 필요하면 영향과 제안을 설명하고 합의한 뒤 반영한다. Figma는 읽기 전용으로 사용한다.

## 폴더 구조와 실행 경계

API·Worker와 `common`·`domain`은 Gradle 멀티모듈로 구성하며 API·Worker는 각각 Dockerfile을 사용한다. 아래 Chat 앱은 채팅 기능 구현 시 추가할 목표 구조다. 실제 실행 파일은 Gradle task와 Docker 빌드로 확인한다.

```text
gkk/
├── AGENTS.md
├── .agents/rules/
│   ├── github.md                 # GitHub 협업·민감 정보 제외
│   ├── architecture.md           # 폴더 구조·책임·공통화 기준
│   └── api.md                    # HTTP·JSON·업로드·SSE 공통 규칙
├── .github/pull_request_template.md
└── ITip/
    ├── settings.gradle           # 앱·라이브러리 모듈 등록
    ├── build.gradle              # Java·의존성 공통 설정
    ├── gradlew / gradle/          # 공용 Gradle Wrapper
    ├── apps/
    │   ├── api-app/               # build.gradle, Dockerfile, src/
    │   ├── worker-app/            # build.gradle, Dockerfile, src/
    │   └── chat-app/              # build.gradle, Dockerfile, src/
    └── libs/
        ├── common/                # ApiResponse·오류 등 공통 타입
        └── domain/                # 공유 엔티티·Repository·도메인 서비스
```

| 실행 앱 | 책임 | 데모 실행 방식 |
| --- | --- | --- |
| `api-app` | 링크·업로드 URL, 분석 접수·조회, 기록·폴더·검색 | 로컬 Spring Boot, `API_BASE_URL` |
| `worker-app` | GCS 자막 검증, 분석 연동, 상태·결과 저장 | 로컬 Spring Boot, PostgreSQL 대기 작업 조회 |
| `chat-app` | 채팅방·대화 저장, Python AI 서버 연동, POST SSE | 로컬 Spring Boot, `CHAT_BASE_URL` |

- 세 Spring 앱은 각각 실행한다. Python AI 서버는 AI 파트가 별도로 구현한다. 채팅 모델 호출·RAG는 Python 담당, 백엔드는 요청 범위 검증·서버 연동·대화 저장·SSE 전달을 담당한다. 내부 연동 형식은 AI 파트와 합의한다.
- Spring MVC·JPA를 사용한다. Virtual Threads는 초기 비활성, Redis는 보류한다. 실행 앱 간 Gradle 의존은 만들지 않고 공유 코드는 `common`·`domain`에 둔다.
- 같은 PostgreSQL을 사용하며 중복 작업은 DB에서 제어한다. 로컬 Worker는 `@Scheduled`로 대기 작업을 선점하고, AI 처리 중에는 긴 DB 트랜잭션을 유지하지 않는다.
- 자막은 Android → GCS presigned URL 업로드 → API 접수 → DB 대기 작업 → Worker 순서다. 서버 yt-dlp 수집은 구현하지 않는다.
- 데모의 분석 완료는 상태 조회로 표시한다. Cloud Run·Cloud Tasks·Cloud Scheduler·Cloud Logging과 FCM 푸시 연동은 운영 단계에서 추가한다. 운영 Worker 호출에는 IAM·OIDC 검증을 적용한다.
- 외부 API 경로·JSON·SSE 형식은 유지한다. Python 이벤트를 기존 SSE 형식으로 전달·변환하고, 최종 답변 저장 후 `done`을 보낸다.

## 공통 작업 규칙

- 한국어로 소통하고 코드 식별자·명령어·경로는 원문으로 작성한다.
- 수정 전 관련 코드와 현재 변경사항을 확인한다. 사용자와 팀원의 기존 변경을 되돌리지 않는다.
- 요청한 기능에 필요한 변경만 하고, 관련 없는 리팩터링이나 의존성 추가를 함께 하지 않는다.
- `.env` 파일과 실제 API 키·토큰·비밀번호·인증키는 Git에 추가하거나 공유하지 않는다. `.env.example`도 예외로 두지 않는다. 환경변수 이름과 용도만 문서에 기록한다.
- DB 구조 변경 SQL과 Flyway 마이그레이션은 관련 코드와 함께 Git으로 버전 관리한다. 작성·검증 기준은 [GitHub 협업 규칙](.agents/rules/github.md)의 DB 구조 변경 절을 따른다.
- 완료 보고에는 변경한 파일, 달라진 동작, 실제 확인한 명령과 결과, 남은 문제를 짧게 적는다. 실행하지 않은 검증을 통과했다고 보고하지 않는다.

## 상세 규칙

작업에 해당하는 파일을 먼저 읽고 따른다.

| 작업 | 참조 파일 |
| --- | --- |
| 이슈·브랜치·커밋·push·PR·리뷰·병합 | [GitHub 협업 규칙](.agents/rules/github.md) |
| 기능 구현·폴더·모듈·책임 분리·공통화·리팩터링 | [아키텍처·설계 규칙](.agents/rules/architecture.md) |
| 커밋 직후 독립 코드 리뷰 | [커밋 리뷰 에이전트](.agents/reviewers/commit-reviewer.md) |
| 컨트롤러·DTO·오류·페이지네이션·업로드·채팅 스트림 | [API 요청·응답 규칙](.agents/rules/api.md) |
| PR 본문 작성 | [PR 템플릿](.github/pull_request_template.md) |

새 규칙은 해당 주제의 상세 파일에 작성하고 이 표에 연결한다. 같은 규칙을 여러 파일에 길게 중복 작성하지 않는다.

## 실행·검증

`ITip/`에서 실행한다.

```sh
./gradlew test                       # 모듈별 테스트
./gradlew build                      # 테스트를 포함한 전체 빌드
./gradlew :apps:api-app:bootRun        # API 실행
./gradlew :apps:worker-app:bootRun     # Worker 실행
```

- 코드 변경 범위에 맞는 검증을 실행한다. 문서·ignore 규칙만 변경했다면 링크·규칙 적용 여부를 확인한다.
- DB를 사용하는 실행·테스트는 로컬 PostgreSQL과 필요한 환경변수 설정을 먼저 확인한다. 운영 Supabase를 개발 테스트에 사용하지 않는다.
- 외부 설정이 없어 검증이 막히면 필요한 변수 이름과 원인을 보고한다. 비밀값을 출력하거나 요청하지 않는다.
- 각 앱의 `bootJar`로 독립 실행 JAR를 만든다. 로컬 실행 전 PostgreSQL과 연결 설정을 준비한다.
