# Luna-low 분할 분석 (#42)

Worker의 분석 기본 경로를 구간 계획 → 구간별 상세 → 전체 요약으로 바꾼다. 별도 Python 서버를 호출하지 않는다. 최종 `AnalysisOutput`, 저장 JSON, 공개 API·SSE·DB 스키마는 유지한다. 모델 선택은 `gpt-6-luna / low`이며 공급자는 명시적으로 켜기 전까지 비활성이다.

## 실행 경로

```text
CaptionParser + 실제 Video.durationMs/title
  → AnalysisCaptionGroups: 10~20초 묶음 + 원본 ID·시간 대응표
  → 계획 모델: 이전 구간의 끝 ID·제목 + 마지막 제목
  → 서버: 증가 순서 검증, sectionKey·모든 시작·마지막 끝 확정
  → 상세 모델: 해당 구간 원문만 전달, 최대3개 병렬
  → 요약 모델: 검증한 상세 + 모든 인용 원문 + 숫자 원문
  → 서버: mindmap.sectionKeys·최종 JSON 조립, 전체 Schema 검증
  → 원본 segmentId로 펼침 → AnalysisOutputValidator
  → 기존 draft·원문 검색 청크·임베딩·완료 저장
```

모든 모델 대기는 기존처럼 DB 트랜잭션 밖이다. API 작업 단계는 전체 생성 동안 기존 `SUMMARIZING`을 유지한다. 부분 상세만으로 draft·기록·`COMPLETED`를 만들지 않는다. 인덱싱 실패 후 재시도 시에는 저장한 완성 draft를 재사용하고 분석 호출을 반복하지 않는다.

## 자막 묶음과 ID

- 공백을 정규화하고 대괄호 비언어 자막만 분석 입력에서 제외한다. 전부 제외되면 빈 성공 결과를 만들지 않는다.
- 문장 끝의 묶음 길이가10초 이상이거나 묶음 길이가20초 이상이면 닫는다. 마지막 짧은 묶음도 유지한다. 긴 원본 자막은 텍스트를 쪼개 가짜 시간을 만들지 않는다.
- 자동 자막의 끝이 다음 자막 시작을 넘으면 묶음 결정에만 clip을 사용한다. 원본 시간과 묶음의 최대 종료 시간은 보존한다.
- 모델은 `g000001` 같은 분석 묶음 ID만 본다. 구간·팁·사례·도구의 출처 배열은 해당 단계에 실제 보낸 ID의 enum이다. AI_SUGGESTION도 맥락 근거1개 이상을 붙인다.
- 최종 저장 전에 인용 묶음을 원본 `s000001` 등의 목록으로 펼친다. 원본 ID로 펼치면5개를 넘을 수 있어 모델용 최대5개를 재적용하지 않는다.
- 제외한 비언어 자막은 근거로 인용하지 않는다. 구간 경계에는 포함해 원본 범위가 연속되도록 보존한다. 중간 비언어 자막은 다음 구간 시작에, 앞·뒤 비언어 자막은 첫·마지막 구간에 포함한다.
- `CaptionChunker`와 임베딩에는 계속 원본 자막을 사용한다. 분석 묶음을 RAG 청크로 대체하지 않는다.

## 단계별 계약·검증

프롬프트는 `ITip/apps/worker-app/src/main/resources/prompts/analysis-split/`에 있다. 고정 System과 매번 구성한 JSON UserMessage를 분리한다. 자막·제목 안의 지시문은 데이터로 취급한다.

`AnalysisSchemas`는 기존 전체 `analysis.spring-v1.json`에서 상세와 요약 Schema를 추출한다. 모든 필수값·null/빈 배열·origin·배열 개수 규칙을 유지하고, 입력별 ID enum과 비어 있지 않은 문자열 제약을 추가한다. 이 계약이 사용하는 object/array/string·required·추가 필드 금지·enum·배열 개수·pattern·로컬 `$ref`를 응답에도 검사한다. Schema에 새로운 종류의 제약을 추가하면 이 검사도 함께 검토한다.

| 단계 | 모델 출력 | 서버가 결정하는 값 |
| --- | --- | --- |
| 계획 | `earlierSections[].title/endSegmentId`, `lastSectionTitle` | sectionKey, 시작 ID, 마지막 끝 ID |
| 상세 | `sourceSegmentIds/summary/subtopics/keywords/detail/mentionedTools` | 계획에서 확정한 제목·경계·sectionKey 결합 |
| 요약 | `summary`, `rootTitle` | 모든 실제 구간의 시간순 `mindmap.sectionKeys`, schemaVersion, 최종 JSON |

끝 ID의 중복·역순·마지막 ID 선점은 거절하며 정렬·삭제로 보정하지 않는다. 최소1·최대15구간 정책을 유지한다. 전체 요약은 인용 원문과 숫자 문자가 있는 원문을 함께 받지만, 이 선택 규칙이 모든 중요 근거·고유명사·사실의 전수 확인을 보장하지는 않는다.

`AnalysisOutputValidator`는 원본 ID로 변환한 결과에서 구간 누락·겹침·역순·구간 밖 출처를 다시 거절한다. 시간은 기존 원본의 시작·범위 최대 끝으로 계산하고 UUID sectionId는 기존 서버 변환을 따른다.

## 설정

환경 파일이나 키 값은 공유하지 않는다. 로컬 실행/Compose에서 다음 변수 이름을 사용한다.

| 변수 | 값·역할 |
| --- | --- |
| `ANALYSIS_OPENAI_ENABLED` | 기본false. 분석 공급자 활성화 |
| `OPENAI_API_KEY`, `OPENAI_BASE_URL` | Worker 전용 주입. 기본 endpoint는 `https://api.openai.com/v1` |
| `ANALYSIS_OPENAI_MODEL` | `gpt-6-luna`를 명시. 임의 fallback 모델 없음 |
| `ANALYSIS_TOKEN_ENCODING` | `O200K_BASE`를 명시. 토큰 추정용 |
| `ANALYSIS_CONTEXT_TOKENS` | 모델 context 용량을 명시. 확인한 Luna 용량은1,050,000이며 모델 변경 시 재확인 |
| `ANALYSIS_INPUT_TOKEN_BUDGET` | 기본30,000 추정토큰/호출. 전체 입력을 줄이지 않고 초과 시 중단 |
| `ANALYSIS_TOTAL_OUTPUT_TOKENS` | 기본16,000. 영상 내 실제 출력·추론 합산 예산 |
| `ANALYSIS_PLAN_OUTPUT_TOKENS` | 기본1,024 |
| `ANALYSIS_DETAIL_OUTPUT_TOKENS` | 기본4,096. 기존 실험2,048에서 상향 |
| `ANALYSIS_SUMMARY_OUTPUT_TOKENS` | 기본3,072 |
| `ANALYSIS_DETAIL_CONCURRENCY` | 기본3, 허용1~3. 상세 배치의 병렬 호출 수 |

요청은 Spring AI2.0.2의 `OpenAiChatModel`, Chat Completions strict JSON Schema, `reasoning_effort=low`를 쓴다. 입력 예산+가장 큰 단계 출력 cap+여유256은 명시한 context 용량 이내여야 한다. 입력 추정은 System+UserMessage+Schema+여유128의 O200K_BASE 추정이며 공급자 실제 토큰 수와 완전히 같다는 의미는 아니다.

상세 배치를 시작하기 전에 요약 예산을 예약한다. 상세별 실제 cap은 `min(상세 설정, (남은 영상 예산-요약 예약)/현재 배치 수)`로 내려갈 수 있다. 따라서4,096은 항상 보장하는 최소치가 아니다. 정상 응답의 실제 completion_tokens(추론 포함)를 차감하며 사용량 누락·cap 초과도 완료로 인정하지 않는다.

SDK·HTTP transport 자동 재시도는0회, 호출별 connect3초/write8초/read120초/전체120초다. 영상 전체 시간은 여러 호출의 순서와 병렬 대기를 포함한다. 한 단계/배치가 실패하면 이후 단계는 실행하지 않는다. Worker의 기존 일시 오류 작업 재시도와 구분한다. **부분 상세의 DB checkpoint·구간만 재시도하는 기능은 없다.** 완성 draft를 저장하기 전의 일시 오류 재시도는 분할 분석 전체를 다시 실행할 수 있다.

STOP·완전한 JSON·Schema·원문 범위·사용량을 확인한 뒤에만 결과를 반환한다. LENGTH는 잘린 응답으로 거절하며 자동 상한 확대·재호출하지 않는다. 거절은 `AI_REQUEST_REJECTED`, 구조/길이/예산 실패는 기존 `AI_INVALID_RESPONSE`, 입력 예산은 `AI_INVALID_INPUT`, 네트워크·일시 공급자 오류는 기존 `AI_UNAVAILABLE` 분류를 따른다. 원문·API 키·공급자 오류 body 대신 안전한 단계·정규화 종료 사유만 기록한다.

## 확인한 것과 남은 것

| 검증 | 실제 확인 범위 |
| --- | --- |
| Python 실영상7개, 최초 시도 | 기존 일괄4/7, 분할6/7.69개 API 호출, 합계 추정$0.062248, 영상 중앙값44.825초. 상세 cap은2,048 |
| 실패 상세4,096 진단 | 기존 실패 구간 한 건만 cap을 변경해 실제 호출. STOP, 검증 통과,10.827초, completion961토큰. 새로운7영상 성공률이 아님 |
| 실제 Spring AI·OpenAI 가상 자막 | 직접 작성한 짧은 자막으로 계획/상세/요약3회 STOP, 최종 검증 통과. 실제 입력·Schema·응답·usage는 `handoff/split/examples/spring-live.actual.json` |
| 로컬 HTTP 대역 | 실제 Spring AI·SDK 요청의 model/low/strict Schema/단계 cap·usage 디코딩 검사 |
| PostgreSQL Worker | 분할 경로의 외부 대기·heartbeat·draft/index 재사용·실패 완료 방지 검사. GCS/모델 통신은 대역 |

`handoff/split/evaluation.json`은 공개 가능한 집계다. 원래 실험/전달 파일은 보존한다.69개 실제 영상 요청·자막·원시 출력은 로컬에만 두며 공개하지 않는다. 통과 수는 구조·출처·범위 검증이고 전체 내용 정확도나 운영 안정성 점수가 아니다. Spring으로 실제7영상·60분 영상·모바일→GCS→최종 저장 전체 흐름은 아직 검증하지 않았다.

`ITip/`에서:

```sh
./gradlew :apps:worker-app:test
./gradlew build
# 본인의 키가 환경에 있고 실제 유료 호출을 의도할 때만 수동 실행
ITIP_LIVE_AI=true ./gradlew :apps:worker-app:test --tests '*SplitAnalysisLiveTest'
```

키 값은 명령에 직접 쓰지 않는다. 수동 실제 호출 테스트는 평소/CI에는 비활성이다. 결과는 `ITip/apps/worker-app/build/reports/analysis-live-smoke.json`에 저장한다.
