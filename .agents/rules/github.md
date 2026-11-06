# GitHub 협업 규칙

## 작업 단위와 브랜치

- 기본 단위는 이슈 하나, 작업 브랜치 하나, PR 하나다. 이슈에는 목적·작업 범위·완료 조건과 담당자를 적는다.
- 이슈 생성 시 [.github/ISSUE_TEMPLATE/](../../.github/ISSUE_TEMPLATE/)의 기능 요청·버그 신고·일반 작업 양식을 사용한다. 일반 작업의 제목에는 선택한 작업 유형을 `chore: 설명`처럼 붙인다.
- 이슈를 생성한 뒤 최신 `dev`에서 작업 브랜치를 만든다.
- `main`은 배포 기준, `dev`는 개발 통합 브랜치다. 두 브랜치에는 직접 push하거나 강제 push하지 않고 PR로 반영한다.
- 브랜치 이름은 `타입/이슈번호-작업명`으로 작성한다. 작업명은 영문 소문자와 하이픈을 사용한다.

```text
feat/12-caption-upload
fix/18-analysis-status
docs/23-api-documentation
chore/25-local-docker
```

## 커밋과 PR 제목

직접 작성하는 커밋과 작업 PR 제목은 다음 형식을 사용한다. 타입은 영어, 설명은 한국어로 작성한다.

```text
타입: 변경 내용 (#이슈번호)

feat: 자막 업로드 URL 발급 API 구현 (#12)
test: 자막 파일 크기 초과 검증 추가 (#12)
fix: 분석 실패 상태가 조회되지 않는 오류 수정 (#18)
```

| 타입 | 용도 |
| --- | --- |
| `feat` | 기능 추가 |
| `fix` | 오류 수정 |
| `refactor` | 동작을 유지하는 코드 구조 변경 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 변경 |
| `chore` | 빌드·의존성·개발 환경·CI 설정 |

- 한 커밋에는 하나의 목적을 가진 변경을 담는다. 관련 없는 변경은 별도 이슈로 나눈다.
- `수정`, `작업 중`, `최종`처럼 변경 내용을 알 수 없는 메시지는 사용하지 않는다.
- Squash 병합 시 최종 커밋에도 관련 이슈 번호를 유지한다. GitHub가 붙이는 PR 번호와 이슈 번호를 혼동하지 않는다.

## PR 작성·리뷰·병합

1. 작업 브랜치에서 `dev`를 대상으로 PR을 만든다. [.github/pull_request_template.md](../../.github/pull_request_template.md)를 사용한다.
2. 해결한 문제와 변경 후 동작, 파일별 구현 내용, 실제 검증 결과를 작성한다.
3. API 요청·응답, DB·Flyway, 환경변수 변경이 있으면 파일명·필드명·변수 이름과 영향을 적는다. 필요한 JSON 예시에는 가짜 값만 사용한다.
4. 다른 팀원 1명의 승인과 변경 범위에 필요한 검증 통과 후 병합한다. CI가 있다면 필수 검사도 통과해야 한다.
5. 작업 브랜치 → `dev`는 `Squash and merge`를 사용한다. 이슈 완료 조건을 확인한 담당자가 이슈를 닫고 작업 브랜치를 삭제한다.
6. 배포 시 `dev` → `main` PR을 만들고 `Create a merge commit`으로 병합한다. `main`·`dev`는 유지한다.

- 관련 이슈는 PR 본문에 `Refs #12`처럼 기록한다.
- 기본 브랜치는 `main`을 기준으로 한다. `dev` 대상 PR의 `Closes`·`Fixes` 키워드에 이슈 자동 종료를 의존하지 않는다.
- 배포 PR에는 포함된 기능 이슈와 배포 전 확인 결과를 함께 적는다.
- 완료되지 않은 작업은 Draft PR로 공유한다. 미실행·실패한 검증은 이유를 적고 해결한 뒤 병합한다.

## DB 구조 변경

- DB 구조 변경 SQL과 Flyway 마이그레이션은 관련 코드와 같은 PR에 포함해 Git으로 버전 관리한다.
- 현재 단일 앱의 마이그레이션 경로는 `ITip/src/main/resources/db/migration/`이다. 멀티모듈 전환 시 실행 앱별 적용 경로와 마이그레이션 실행 주체를 정한다.
- 파일명은 `V1__create_records.sql`, `V2__add_summary.sql`처럼 `V버전__설명.sql` 형식을 사용한다. 버전과 설명 사이에는 밑줄 두 개를 쓴다.
- 공유 DB에 적용한 버전 파일은 수정하거나 삭제하지 않고 새 버전 파일로 변경을 추가한다. 병합 전 최신 `dev`와 버전 번호 중복·적용 순서를 확인한다.
- PR에는 스키마 변경 내용과 로컬 또는 CI의 별도 테스트 DB에서 확인한 적용 결과를 적는다. 운영 DB에 검증용으로 적용하지 않는다.
- DB 구조는 Flyway로 변경하며 Hibernate의 `ddl-auto=update/create/create-drop`과 함께 관리하지 않는다. 엔티티와 구조 일치 검사는 `ddl-auto=validate`를 사용한다.
- 실제 사용자 데이터, 운영 DB 덤프·백업, 인증정보는 커밋하지 않는다. 덤프·백업은 Git 추적에서 제외한 `dumps/`, `backups/` 등에 보관한다.

## 민감 정보와 Git 추적

- `.env`, `.env.*`, `*.env`, `*.env.*`는 모두 Git에서 제외한다. `.env.example`, `.env.sample`도 허용하지 않는다.
- `.env` 파일 자체를 팀원·이슈·PR·채팅·첨부파일로 공유하지 않는다. 실제 값은 각자 로컬에서 설정한다.
- API 키, 접근·갱신 토큰, DB 비밀번호, 개인키, 서비스 계정 JSON, 인증 저장소를 커밋하거나 PR 본문·로그·스크린샷에 노출하지 않는다.
- 비밀 파일은 `.secrets/`, `secrets/`, `credentials/` 같은 추적 제외 디렉터리에 보관한다. 다른 경로를 사용한다면 staging 전에 `.gitignore`를 먼저 보완한다.
- 환경변수 이름·용도·필수 여부는 문서로 공유할 수 있다. 추적하는 설정 파일은 `${DB_PASSWORD}` 같은 환경변수 참조를 사용하고 실제 값을 넣지 않는다.
- DB 구조 변경 SQL과 Flyway 마이그레이션은 위 규칙에 따라 추적하되 실제 인증정보·운영 데이터는 넣지 않는다.
- `.gitignore`는 파일명·경로만 검사하며, 일반 소스 파일 안에 들어간 비밀값을 자동으로 찾아주지 않는다.

커밋 전에 다음을 확인한다. diff는 로컬에서 확인하고 비밀값을 채팅이나 PR에 복사하지 않는다.

```sh
git status --short
git diff --cached --name-only
git diff --cached --check
git diff --cached
git check-ignore -v --no-index -- .env .env.local .env.example
git ls-files -ci --exclude-standard
```

- 변경 파일을 확인한 후 필요한 경로만 명시적으로 stage한다. 민감 파일을 `git add -f`로 강제 추가하지 않는다.
- `git ls-files -ci --exclude-standard`에 민감 파일이 나오면 이미 추적 중인 상태다. `.gitignore` 추가만으로 추적이 해제되지 않으므로 로컬 원본을 보존하며 인덱스에서 제외한 뒤 다시 확인한다.
- 비밀값이 이미 커밋된 사실을 발견하면 push를 중단하고 파일 경로만 보고한다. 키 폐기·재발급과 이력 정리는 담당자와 처리하며 임의로 공유 이력을 강제 재작성하지 않는다.
