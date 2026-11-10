# 아키텍처·설계 규칙

## 적용 범위

- 개발 환경·MVP 범위는 [AGENTS.md](../../AGENTS.md)를 따른다.
- 아래는 현재 모듈 경계다. 필요한 기능을 구현할 때 패키지를 만들며, Dockerfile 등 실행 파일의 존재를 문서만으로 판단하지 않는다.

## 폴더 구조·실행 경계

```text
ITip/
├── apps/
│   ├── api-app/       # 일반 API: video·upload·analysis·record·folder·search·home
│   ├── worker-app/    # 분석 실행: scheduler·analysis·infrastructure
│   └── chat-app/      # 채팅: conversation·retrieval·generation·stream
└── libs/
    ├── common/        # response·exception·pagination 등 실제 공통 타입
    ├── domain/        # 도메인별 Entity·Repository·상태·공유 업무 규칙
    └── ai/            # 두 앱 공통 임베딩 공급자·SDK 연결·HTTP 오류 정책
```

- `api-app`은 실행 역할이다. 그 안은 기능 도메인별로 나누고, 도메인 아래에 `controller`, `service`, `dto/request`, `dto/response`를 둔다. 작은 기능에 불필요한 계층·빈 폴더를 미리 만들지 않는다.
- Java 루트 패키지는 `com.gkk.itip`이다. 각 앱의 실행 클래스를 루트에 두고 `api`, `worker`, `chat`, `domain`, `common`은 그 아래에 배치한다. 멀티모듈 전환 시 기존 패키지와 스캔 범위도 함께 맞춘다.
- 앱마다 `build.gradle`, `Dockerfile`, `src/main/resources/application.yaml`, `src/test/java`를 둔다. 앱은 각각 `bootJar`로 실행하고 라이브러리는 일반 JAR로 포함한다.
- 앱은 `common`·`domain`에 의존한다. `domain`은 필요할 때 `common`을 사용하되, 라이브러리가 실행 앱에 의존하거나 앱끼리 Gradle 의존성을 만들지 않는다.
- Worker·Chat은 `libs/ai`의 같은 임베딩 공급자 설정을 사용한다. Chat의 답변 모델은 `chat/config`·`generation`에 두고 분석 모델·임베딩 모델과 역할을 구분한다. API는 이 라이브러리에 의존하지 않는다.
- 같은 이름의 `analysis`도 API에서는 접수·조회, Worker에서는 실행·완료를 담당한다. 두 프로세스는 같은 PostgreSQL의 작업 테이블로 연결하며, API가 Worker의 Service를 직접 호출하지 않는다.
- Flyway SQL은 `libs/domain/src/main/resources/db/migration` 한곳에서 관리한다. 초기에는 API 앱이 마이그레이션을 실행하고 Worker·Chat은 비활성화한다. 마이그레이션 완료 후 나머지 앱을 실행하며 JPA는 `ddl-auto: validate`로 확인한다.

## 책임 배치

| 대상 | 책임 |
| --- | --- |
| Controller | HTTP 입력·형식 검증·응답. Header 등 요청 정보는 여기서 읽고 필요한 값만 Service에 전달 |
| Service | 처리 순서·소유권·업무 조건·트랜잭션. HTTP 요청 객체를 직접 조회하지 않음 |
| Entity | 자신이 가진 값과 허용된 상태 변경을 표현. 임의 setter로 여러 곳에서 상태를 변경하지 않음 |
| Repository | DB 조회·저장. HTTP 응답 생성·GCS·AI 호출을 처리하지 않음 |
| DTO | 앱 요청·응답과 외부 연동 형식을 구분. Entity를 그대로 응답하지 않으며 간단한 변환은 직접 작성 |
| 외부 Client | GCS·AI 공급자의 통신·응답 변환. 앱 전용은 해당 도메인에 두고 실제 두 앱에서 공유하는 SDK 설정은 libs/ai에 둠. domain에 SDK 세부 구현을 노출하지 않음 |
| 공통 라이브러리 | 여러 곳에서 실제로 공유하는 타입·정책만 포함. 업무 규칙은 `domain`, 범용 타입은 `common`에 배치 |

## 단순 구현·공통화 기준

- **먼저 기능을 단순하게 구현하고, 실제 반복과 변경 이유를 확인한 뒤 분리한다.** 미래 사용처를 예상해 인터페이스·계층·모듈을 늘리지 않는다.
- 한 클래스의 중복은 우선 메서드로 묶는다. 여러 클래스에서 같은 정책·책임이 반복되면 역할이 드러나는 클래스로 추출한다. 사용 횟수만으로 기계적으로 공통화하지 않는다.
- 먼저 해당 도메인 안에서 공유한다. 여러 도메인·실행 앱에서 같은 의미로 재사용할 때 공통 라이브러리로 옮긴다. 모양이 비슷해도 변경 이유가 다르면 각각 유지한다.
- Service는 구체 클래스로 시작한다. 교체할 구현이나 테스트 대역이 실제로 필요할 때 인터페이스를 도입한다. 관례만으로 `Service/ServiceImpl` 쌍을 만들지 않는다.
- `CommonUtil`, `BaseService`, `BaseController`, 범용 CRUD 추상화를 미리 만들지 않는다. Util은 상태·DB·외부 통신이 없는 작은 공통 함수에 한정하고 역할이 드러나는 이름을 사용한다.
- 상속은 실제 공통 속성이 있을 때만 사용한다. `BaseTimeEntity`에는 필요한 공통 감사 필드만 두고 모든 Entity에 소유자·삭제 상태 등을 일괄 강제하지 않는다.
- 반복이 없어도 HTTP·외부 통신·트랜잭션처럼 책임과 실행 경계가 다른 부분은 분리한다.

## 구현·리뷰 기준

- 생성자 주입을 사용한다. Singleton Service의 필드에 요청별 사용자·작업·답변을 보관하지 않는다. DTO·실행 입력은 불변 데이터로 전달한다.
- 입력 형식은 DTO 검증, 소유권·업무 조건은 Service, 상태 변경 조건은 Entity에서 확인한다. 공통 Policy가 실제로 필요해지면 그때 추출한다.
- 트랜잭션은 DB 변경 단위로 짧게 유지한다. GCS·AI 응답 대기와 SSE 연결 전체를 묶지 않는다. 프록시 적용이 필요한 메서드는 다른 Bean을 통해 호출하도록 분리한다.
- 중복 요청·작업 선점은 DB 제약·잠금·조건부 갱신으로 보장한다. Java 메모리 잠금이나 AOP만으로 여러 프로세스의 중복을 막으려 하지 않는다.
- Header 바인딩 등 Spring 기본 기능을 먼저 사용한다. 반복되는 요청 정책이 생기면 Filter·ArgumentResolver 등을 선택한다. 현재 로그인 제외 MVP에 Cookie 유틸·인증 기반을 미리 추가하지 않는다.
- 필요한 로그부터 작성하고 반복되는 추적·실행 시간 기록이 확인되면 Filter·AOP로 추출한다. 업무 조건·상태 전환·재시도 정책은 Service에서 흐름이 보이도록 유지한다.
- Worker·SSE의 완료 로그는 실제 종료 시점에 기록한다. 비동기 스레드의 추적 정보 전달·정리를 고려하고 토큰·원문·서명 URL 전체는 로그에서 제외한다.
- 같은 의미의 제한값·타임아웃을 여러 곳에 복사하지 않는다. 정책이 공유되면 설정으로 관리하고, 외부 연동의 타임아웃·재시도 책임이 여러 계층에서 중첩되지 않게 한다.
- 상태 전환·중복 접수·권한 범위·외부 실패 등 중요한 동작을 우선 테스트한다. 코드 구조를 따라가는 형식적인 테스트나 테스트만을 위한 과한 계층은 만들지 않는다.
- HTTP·JSON·SSE·오류·페이지네이션의 세부 형식은 [API 요청·응답 규칙](api.md)을 따른다.
