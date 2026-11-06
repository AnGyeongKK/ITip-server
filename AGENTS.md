# YouSum 프로젝트 규칙

## 프로젝트 범위

- Spring Boot 프로젝트는 `ITip/`에 있다. Gradle 명령은 이 디렉터리에서 실행한다.
- Java 21, Spring Boot 4.1.2, Gradle Wrapper, Spring MVC, JPA를 기준으로 개발한다.
- 현재 시연용 MVP는 영상·재생목록 가져오기, 분석·결과, 기록·폴더, 검색, AI 대화, 기기·알림이다. 로그인·결제·요금제·이용 한도는 구현 범위에서 제외한다.
- 기획·API 변경이 필요하면 영향과 제안을 설명하고 합의한 뒤 반영한다. Figma는 읽기 전용으로 사용한다.

## 폴더 구조와 실행 경계

현재 코드는 `ITip/src/`의 단일 앱이다. 아래는 앞으로 구성할 Gradle 멀티모듈 구조이며, 이 문서 작성으로 모듈·Dockerfile이 생성된 것은 아니다. 모듈 전환 작업에서 기존 코드를 이동하고 빌드 설정을 맞춘다.

```text
gkk/
├── AGENTS.md
├── .agents/rules/
│   ├── github.md                 # GitHub 협업·민감 정보 제외
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

| 실행 앱 | 책임 | 호출·배포 방식 |
| --- | --- | --- |
| `api-app` | 링크 확인, 업로드 URL, 분석 접수·상태·결과, 홈·기록·폴더·검색, 기기 등록 | `API_BASE_URL`, 독립 Cloud Run 서비스 |
| `worker-app` | 업로드 검증, AI 분석·임베딩, 상태·결과 저장, 분석 종료 알림 | Cloud Tasks의 인증된 HTTP 호출, 독립 비공개 Cloud Run 서비스 |
| `chat-app` | 채팅방·메시지 저장·조회, RAG 답변, POST SSE | `CHAT_BASE_URL`, 독립 Cloud Run 서비스 |

- 세 앱은 각각 Spring Boot 진입점·실행 JAR·컨테이너를 갖는다. 앱 요청이 다른 컨테이너를 직접 생성하는 구조가 아니다.
- 모두 Spring MVC를 사용한다. API는 JPA 중심, Worker·Chat은 필요한 DB 접근과 Spring AI를 사용한다. Virtual Threads는 초기 비활성, Redis는 보류한다.
- 앱은 `common`·`domain` 라이브러리를 참조할 수 있지만 다른 실행 앱 모듈을 의존하지 않는다. 라이브러리는 독립 서버로 배포하지 않는다. 도메인 변경은 해당 서비스 메서드를 통해 연결한다.
- API·Worker·Chat은 같은 PostgreSQL을 사용하지만 JVM 메모리·락·트랜잭션은 공유하지 않는다. 중복 실행·동시 변경은 DB 제약과 트랜잭션으로 제어한다.
- 자막 수집은 Android 네이티브 담당이다. 앱 → GCS 직접 업로드 → API 분석 접수 → Cloud Tasks → Worker 순서로 처리한다. 서버에서 yt-dlp 수집을 구현하지 않는다.
- Worker는 앱에 공개하지 않는다. 로그인 기능이 MVP에서 제외되어도 Cloud Run IAM·OIDC로 내부 호출을 보호한다.
- Chat은 같은 요청의 SSE로 답변한다. 토큰 조각마다 API 서버에 저장 요청을 보내지 않는다. AI 대기·스트리밍 전체를 DB 트랜잭션으로 감싸지 않는다.
- Worker는 처리 결과·상태를 확정하기 전에 성공 응답을 보내고 별도 스레드에서 계속 분석하지 않는다. DB 저장과 Cloud Tasks 등록 실패의 복구 방식은 별도 구현 합의가 필요하다.
- 정기 정리 작업은 필요해질 때 Cloud Scheduler가 Worker를 호출하도록 추가한다. 로그인 제외 MVP에 세션 정리·회원 탈퇴 작업을 먼저 구현하지 않는다.

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
| 컨트롤러·DTO·오류·페이지네이션·업로드·채팅 스트림 | [API 요청·응답 규칙](.agents/rules/api.md) |
| PR 본문 작성 | [PR 템플릿](.github/pull_request_template.md) |

새 규칙은 해당 주제의 상세 파일에 작성하고 이 표에 연결한다. 같은 규칙을 여러 파일에 길게 중복 작성하지 않는다.

## 실행·검증

`ITip/`에서 실행한다.

```sh
./gradlew test      # 테스트
./gradlew build     # 테스트를 포함한 빌드
./gradlew bootRun   # 로컬 실행
```

- 코드 변경 범위에 맞는 검증을 실행한다. 문서·ignore 규칙만 변경했다면 링크·규칙 적용 여부를 확인한다.
- DB를 사용하는 실행·테스트는 로컬 PostgreSQL과 필요한 환경변수 설정을 먼저 확인한다. 운영 Supabase를 개발 테스트에 사용하지 않는다.
- 외부 설정이 없어 검증이 막히면 필요한 변수 이름과 원인을 보고한다. 비밀값을 출력하거나 요청하지 않는다.
- 위 명령은 현재 단일 앱 기준이다. 멀티모듈 전환 후 실행 명령을 각 앱의 `bootRun`·`bootJar` 경로로 갱신하고, 실제 존재하는 Gradle task를 확인한다.
