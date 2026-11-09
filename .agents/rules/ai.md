# AI 설계 규칙

- 작업 전 [AI 결과 계약과 현재 차이](../../docs/analysis-ai-contract.md), [담당 연결](../../docs/analysis-integration.md)을 읽는다. AI 실험 원문과 공개 API 명세 링크는 계약 문서에서 확인한다. 현재 코드·AI 실험 결과·구현 예정·미합의 항목을 구분한다.
- AI 파트가 요약·주제·하위 항목·마인드맵 관계를 생성하고, 서버는 검증·ID/시간 매핑·저장·조회를 담당한다. 앱은 그래프 배치·연결선·확대/축소를 구현한다. 결과 GET에서 생성·임베딩을 다시 실행하지 않는다.
- 마인드맵은 `rootTitle → sectionIds의 주제 → sections[].subtopics`로 구성한다. AI의 `sectionKey`를 서버 UUID `sectionId`로 바꾸고 요약·마인드맵·상세에서 같은 ID를 사용한다. 앱 전용 좌표·그래프 라이브러리 타입을 서버 계약에 미리 추가하지 않는다.
- AI 입력·출력 DTO, 검증 후 저장 형식, 공개 API 응답을 구분한다. 공유 저장 타입 `ResultDocument`는 `libs/domain`의 `domain.analysis.result`에, 원시 `AnalysisOutput`은 Worker에, 공개 응답은 API에 둔다. API가 Worker 모듈에 의존하지 않으며 미래 사용처만으로 계층을 늘리지 않는다.
- 근거 ID는 실제 입력 자막에 존재해야 하고, 시간은 원본에서 계산한다. `SOURCE`와 `AI_SUGGESTION`을 구분한다. 입력을 묶을 때 집계 ID와 원본 구간·시간의 대응을 보존하고 RAG 검색 데이터는 자막 원문으로 만든다.
- 외부 AI 대기와 SSE 연결 전체를 DB 트랜잭션으로 묶지 않는다. 결과·검색 데이터·개인 기록이 준비되어야 완료를 확정한다. 조회·검색 시 현재 소유권과 개인 기록의 삭제 여부를 확인한다.
- 모델 직접 호출은 Spring AI와 공급자 SDK를 사용한다. 별도 HTTP API가 필요한 경우 WebClient를 사용한다. 영상 분석 DTO와 채팅 모델 출력 Schema는 별개이며, 공급자 종료·타임아웃·오류를 구분한다. 저장 결과 GET에는 HTTP Client나 AI 호출을 추가하지 않는다. 교체를 예상한 인터페이스·범용 어댑터를 미리 만들지 않는다.
- AI 문서의 모델 선택은 서버 Bean 연결 완료를 뜻하지 않는다. 전체 Schema·프롬프트·원시 응답·구간 수·입력 묶음 방식의 차이를 확인한 뒤 코드와 계약을 함께 변경한다. 현재 검증을 실제 AI에 맞춘다는 이유로 임의 완화하지 않는다.
- 실제 공급자의 타임아웃·SDK 시도 횟수를 확인해 작업 재시도와 중첩시키지 않는다. 프롬프트·자막·서명 URL·인증정보를 로그나 Git에 남기지 않는다. 테스트 고정 응답과 실제 AI 통신·품질 검증을 구분한다.

## 채팅 RAG 책임

- [직접 생성 계약](../../docs/chat-generation-contract.md)과 [AI 파트 결정](https://www.notion.so/41346c2fafdf8017b61af4e271aa67a6)의 상단 현재 설계를 따른다. Python HTTP2.0은 이전 설계다. 실행용 Python 서버를 새로 연결하지 않는다.
- Worker는 자막 원문 chunk·sectionId·시간·임베딩을 저장한다. Chat은 질문 임베딩 → pgvector 검색 → 프롬프트 구성 → Spring AI GPT 직접 호출 → 인용·종료 검증·저장을 담당한다. AI 파트는 프롬프트 파일·모델 옵션·출력 Schema·평가 사례를 제공한다.
- 사용자·기록 존재·완료·허용 recordId·모델/차원은 거리 정렬과 topK 이전 SQL 조건이다. 임베딩 뒤 현재 범위를 다시 확인하며 요약·AI 제안을 원문으로 대체하지 않는다.
- 고정 system 지시문은 prompts/rag-system.txt에서 읽고 question/contexts/history는 JSON UserMessage로 분리한다. 원문·대화 지시를 SystemMessage에 합치거나 템플릿으로 해석하지 않는다.
- 모델에 맞춘 tokenizer·입출력/전체 context 예산을 명시한다. 오래된 대화 쌍 → 후순위 원문을 통째로 제외하고 실제 전송한 context만 인용 후보로 유지한다.
- 모델의 answer/citations/evidenceStatus와 공급자 STOP/LENGTH/refusal/EOF를 구분한다. answer만 delta로 전달하고, 완전한 JSON·실제 인용 후보·현재 권한을 검증해 DB 커밋 후 done을 보낸다. 잘린 결과·길이 제한·거절을 완료로 위장하지 않는다.
- 분석·임베딩·답변 모델은 별도 역할이다. Worker·Chat은 libs/ai의 같은 임베딩 모델·버전·차원·입력 규칙을 사용한다. 모델명을 임의 기본값으로 채우지 않고 모델/차원 변경 시 기존 벡터 재색인을 검토한다.
- 공급자 설정은 기본 비활성이다. 키는 환경변수로 주입하며 명시적 활성화·모델·차원·tokenizer/예산이 필요하다. SDK와 하위 HTTP transport 재시도는 0회이며, Worker의 DB 작업 재시도와 Chat의 사용자 재요청을 구분한다.
- 고급 필터·재정렬·하이브리드 검색은 [품질 개선 계획](../../docs/chat-contract.md#답변-품질-개선-계획)에 따라 측정 후 도입한다. 필수 권한·범위·모델 필터는 초기부터 적용한다.
