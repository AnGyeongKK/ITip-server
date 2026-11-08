# 분석 결과·구간 상세 조회 (#5)

## 목표와 범위

저장된 완료 결과를 요약·키워드·마인드맵과 구간 상세 화면에 제공한다. 외부 AI HTTP 연동은 WebClient로 결정했으며, 이번 GET은 DB만 읽는다. 관련 영상 추천(ANALYSIS-06)은 AI 추천 계약 확정 후 별도로 구현한다.

- 근거: [결과 명세](https://www.notion.so/40c46c2fafdf81b8aeebcea510c56abf), [구간 명세](https://www.notion.so/40c46c2fafdf8119bad7ff51231eacf8), [AI 계약](../analysis-ai-contract.md).
- 로그인 제외 MVP의 서버 설정 DEMO_USER_ID를 그대로 사용한다.
- 기존 사용자 변경인 `.agents/rules/api.md`, `.github/pull_request_template.md`는 수정·커밋하지 않는다.

## 구현 단위

1. AI 책임·현재 구현 차이와 WebClient 결정을 문서에 남긴다.
2. 저장 형식 ResultDocument를 domain으로 옮기고 Worker 호출자·기존 테스트를 함께 변경한다. JSON 필드·DB 구조는 유지한다.
3. `GET /api/v1/analyses/{analysisId}`를 구현한다. 소유권 → 완료 → 개인 기록 → JSON/영상 결합 순서로 읽고 공개 sections에는 detail을 제외한다.
4. `GET /api/v1/analyses/{analysisId}/sections/{sectionId}`를 구현한다. 같은 분석의 section만 반환하고 서버가 YouTube 시간 링크를 만든다.
5. 전체 빌드·리뷰 후 dev 대상 PR을 만들고 API 호출 구조 HTML에 실제 함수·커밋·PR을 연결한다.

각 단위는 작은 커밋으로 남기고 독립 커밋 리뷰 후 다음 단위로 진행한다. 코드 변경 뒤 실제 PostgreSQL MockMvc 테스트를 먼저 실패시킨 후 구현한다. 단순 타입 이동은 기존 Worker 테스트로 저장·역직렬화 동작을 확인한다.

## 확인할 동작

- 정상 결과의 UUID·키워드·주제 순서·SOURCE/AI_SUGGESTION·밀리초·UTC 시각을 유지한다. 미확인 영상 메타데이터는 null이다.
- 미완료 본인 결과는 409 ANALYSIS_NOT_READY, 없음·타인·삭제된 기록·다른 분석의 구간은 404다. UUID 오류는 400이다.
- JSON 손상·지원하지 않는 버전은 500이며 자막·원문·저장 JSON을 오류에 노출하지 않는다.
- 반복 GET에서 결과·작업·기록을 변경하지 않으며 AI 생성·검색 색인을 실행하지 않는다.
- 두 앱 간 의존, 새 테이블·범용 조회 프레임워크·미사용 HTTP Client는 추가하지 않는다.
- 실제 GCS·AI 연결 및 품질은 이번 고정 데이터 테스트로 검증됐다고 표시하지 않는다.

## 완료 기준

두 GET의 명세 응답과 오류가 실제 PostgreSQL 테스트를 통과하고, 전체 Gradle build·독립 리뷰·PR·시각화가 준비된다. 추천 API가 남으므로 이 PR에서 #5 전체 완료나 자동 종료를 선언하지 않는다.
