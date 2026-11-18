# 분석 결과 JSON 연결

현재 생성 경로는 [Luna-low 분할 분석 (#42)](analysis-split-generation.md)이다. 단계별 생성·서버 조립을 추가했으며 아래 최종 필드·저장/공개 ID 계약은 유지한다.

기존 [개발 논의 05·06](https://www.notion.so/40c46c2fafdf8032a762cbe43121ce41)의 `schemaVersion:1.0` 초안을 Worker의 내부 DTO로 옮겼다. 이후 확인한 [ITip / AI 실험](https://www.notion.so/40f46c2fafdf80b29264e6ba26342725)과 [결과 조회 명세·마인드맵 보완](https://www.notion.so/40c46c2fafdf81b8aeebcea510c56abf)을 아래에 대조했다. 테스트 JSON은 실제 분석 품질을 검증한 결과가 아니다.

## AI 파트와 서버의 책임

```text
AI: 구간 계획·상세·전체 요약·rootTitle 생성
  → 서버: 구간 경계·sectionKey·mindmap.sectionKeys·전체 JSON 조립
  → Worker: 원문 근거 검증·sectionId/시간 매핑
  → DB: 검증 결과·검색 데이터·개인 기록 저장 후 완료
  → 결과 GET: 저장된 결과와 영상·기록 조회
  → 앱: rootTitle → 주제 → subtopics를 그래프로 배치
```

AI 문서의 마인드맵 예시는 `sections[].sectionKey/title/subtopics`와 `mindmap.rootTitle/sectionKeys`를 사용한다. 현재 내부 타입과 이 부분은 맞는다. 전체 Schema와 단계 프롬프트를 Worker 리소스에 연결했다. 구조 호환성과 실제 내용 품질의 확인 범위는 분할 분석 문서에서 구분한다. 그래프 좌표·색상·연결선은 앱의 표현이며 서버가 마인드맵을 다시 생성하지 않는다.

## 현재 반영 상태와 합의할 차이

| 항목 | 현재 코드 | AI 문서 또는 다음 구현 |
| --- | --- | --- |
| AI 결과 | `AnalysisOutput`의 summary·sections·mindmap 유지 | 전체 Schema와 단계별 출처 enum 검사 후 서버 조립 |
| 서버 변환 | `AnalysisOutputValidator`가 UUID sectionId·실제 시간·키워드 ID 매핑 | 같은 sectionId를 요약·마인드맵·상세에서 공유 |
| 저장·완료 | `WorkerResultStore`가 draft·구간·벡터·기록 저장 | 완료 전에 저장된 draft와 공개 결과를 구분 |
| 공개 조회 | `AnalysisResultController.get()`·`getSection()` 제공 | GET은 저장된 데이터만 조회. 관련 영상 추천은 별도 후속 범위 |
| 자막 입력 | 실제 길이·제목과10~20초 묶음 사용 | 계획은 전체, 상세는 해당 구간, 요약은 상세+근거 원문. 최종 결과는 원본 segmentId로 펼침 |
| 구간 수 | 현재 분할 계약은1~15개, 비어 있으면 거절 | 과거0개/최소2개 실험과 구분. 현재 정책을 임의 완화하지 않음 |
| 분석 모델 | 명시적 활성화 시 전용 OpenAI ChatModel Bean 생성 | `gpt-6-luna / low`, strict 단계 Schema. 기본 비활성 |
| 임베딩 | libs/ai의 실제 OpenAI Bean 설정 구현, 기본 비활성 | 분석용 텍스트 생성 모델과 별도로 선택. Spring Chat의 질문 임베딩과 같은 모델·버전·차원 및 입력 규칙 사용 |
| 분석 호출 방식 | Spring AI로 계획→상세→요약 호출 | 서버 조립/검증 뒤 저장. 공개 결과 GET에 외부 호출 없음 |

위 차이는 자동 적용할 정책이 아니다. 특히 sections 0개를 임의의 빈 성공 결과로 바꾸거나 집계 ID를 원본 ID처럼 취급하지 않는다. 모델·프롬프트·Schema 연결은 자체 작성 입력의 실제3단계 호출로 확인했다. 실제7영상·반복 안정성·최대60분 영상의 Spring 검증은 남아 있다.

## 결과·구간 조회 구현 (#5)

공유 저장 타입은 `libs/domain`의 `domain.analysis.result.ResultDocument`다. Worker·API가 같은 JSON을 사용하고 API는 Worker에 의존하지 않는다. `AnalysisOutput`은 Worker 입력 DTO로 유지하고 공개 DTO는 `api-app`에 둔다. 저장 JSON 필드·타입·DB 스키마는 이동 전과 같다.

```text
AnalysisResultController.get(analysisId) / getSection(analysisId, sectionId)
  → AnalysisResultQueryService.get(...) / getSection(...) [readOnly·REPEATABLE_READ]
    → completed(ownerId, analysisId)
    → 본인 결과·완료 여부·현재 개인 기록 확인
    → 저장 JSON 역직렬화 + Video·Record 메타데이터 결합
    → 공개 AnalysisResultResponse / AnalysisSectionResponse → ApiResponse.success()
```

- 마인드맵의 `sectionIds` 배열 순서로 `sections[].sectionId`를 찾아 제목·subtopics를 그린다. 하위 항목은 문자열 목록이며 별도 DB 노드 모델이 필요하지 않다.
- 공개 `sections[]`는 기존 API 명세의 목록 필드만 반환하고, 저장된 `detail` 전체를 그대로 노출하지 않는다. 구간 상세 GET은 해당 분석의 sectionId인지 확인하고 같은 시간·제목을 사용한다.
- 공개 `sections[]`는 startMs·endMs·sectionId 순으로 정렬한다. 마인드맵·강조 구간 배열은 AI의 표시 순서를 유지한다.
- 두 GET은 `analysis_results.result_json`을 화면 콘텐츠의 기준으로 사용한다. 구간 상세도 이 문서에서 선택해 이중 저장된 상세의 차이를 응답에 섞지 않는다. `analysis_sections`는 Worker 검색 조각·시간 참조 검증용이다.
- 시연 소유자는 기존 `DEMO_USER_ID` 설정을 사용한다. 없는/타인 결과·삭제된 완료 기록·다른 분석의 구간은 404다. 형식이 잘못된 UUID는 400이다.
- 본인 미완료 결과는 409 `ANALYSIS_NOT_READY`다. 이를 위해 작업 API에서 숨긴 draft ID를 공개하지 않는다.
- 조회가 AI를 재호출하거나 ID·내용·완료 시각을 바꾸지 않는지, 참조 ID가 실제 구간에 존재하는지 확인한다. 검증된 저장 형식 자체가 손상되면 서버 오류로 처리하고 GET에서 자동 재분석하지 않는다.

## 입력과 결과의 연결

```text
GCS 자막 → CaptionParser → 원본 s000001, s000002 …
→ AnalysisCaptionGroups → 분석 묶음 g000001 … → 분할 생성
→ 원본 ID로 펼친 분석 JSON의 sectionKey·sourceSegmentIds
→ AnalysisOutputValidator
→ 서버 sectionId·실제 시간으로 변환 → 결과 draft 저장
→ 원문 검색 조각·임베딩 저장 → 완료·개인 기록 확정
```

| 모델 출력 | 서버에서 확인·변환할 내용 |
| --- | --- |
| `schemaVersion` | `1.0`만 허용 |
| `summary` | oneLine, keyPoints, practicalPoints, mentionedTools, keywords, highlightSectionKeys |
| `sections[]` | sectionKey, title, startSegmentId, endSegmentId, sourceSegmentIds, summary, subtopics, keywords, detail |
| `detail` | takeaway, explanation, applicationTips, recommendedTools, usageKeywords, examples |
| `mindmap` | rootTitle, 비어 있지 않은 sectionKeys |
| 문장·추천 도구의 `origin` | SOURCE 또는 AI_SUGGESTION. SOURCE는 입력 자막 근거가 필요 |
| 원문 참조 | 존재하는 자막 ID만 허용. 구간의 시작·끝 순서와 구간 밖 참조를 확인. 상세의 팁·도구·예시도 자기 주제 구간의 자막만 참조 |

전체 필드 타입은 `worker/analysis/output/AnalysisOutput.java`, 저장 형식은 `ResultDocument.java`를 따른다. 구조 필드는 생략하거나 null로 보내지 않으며 해당 항목이 없으면 빈 배열을 사용한다. sections와 마인드맵 주제는 비어 있을 수 없다.

```json
{
  "sectionKey": "topic-a",
  "startSegmentId": "s000001",
  "endSegmentId": "s000002",
  "sourceSegmentIds": ["s000001", "s000002"]
}
```

위 JSON은 구간 참조 필드만 발췌한 예시다. 서버가 UUID sectionId를 생성하고 실제 자막의 시간으로 startMs·endMs를 계산한다. 자동 자막이 겹치면 해당 범위의 최대 종료 시각을 사용한다. highlightSectionIds·mindmap.sectionIds·구간 상세는 같은 실제 sectionId를 참조한다.

키워드 ID는 `yousum.keyword.v1:` 접두사와 NFKC·소문자 정규화 이름으로 만드는 UUID다. 같은 이름은 요약·구간·다른 분석에서도 같은 ID를 사용한다. 표시 이름은 원래 표기를 유지하며 같은 ID의 중복은 제거한다. 추후 키워드 저장 모델을 추가할 때 이 규칙과 기존 ID를 함께 검토한다.

외부 모델의 원문 응답을 바로 앱에 반환하지 않는다. Worker는 검증된 결과를 저장하고, 공개 결과 API는 저장 모델을 응답 DTO로 변환한다. 실제 채팅 서버 연결은 별도 기능이다.

검색 조각은 요약문을 복사하지 않고 검증한 자막 원문에서 만든다. `CaptionChunker`는 설정한 Unicode 문자 수로 나누며, 현재 조각 사이의 겹침은 없다. 긴 자막 한 구간을 나눠도 원래 구간 시간을 유지하고 글자 수로 재생 시각을 추측하지 않는다. 검색 조각에는 실제 원문 구간 ID와 시간이 겹치는 화면 sectionId를 함께 저장한다. 조각 크기·겹침·임베딩 설정은 실제 영상의 검색 품질을 보고 AI 파트와 조정한다.

## Spring AI 모델 어댑터

`SpringAiAnalysisClient.generate(captions, durationMs, title)`는 `SplitAnalysisGenerator`에 위임한다. 상세 단계 cap은4,096이고 영상 합산 출력·추론 예산은16,000이다. 고정 System·입력 JSON·단계 Schema·종료/사용량·예산·실행 방법은 [분할 분석 문서](analysis-split-generation.md)에 있다. 기존 `handoff/` 단일 호출 자료는 이전 실험 기록이며 현재 전달 진입점은 `handoff/split/README.md`다.

`checkConfigured()`는 유일한 분석/임베딩 모델과 tokenizer 설정을 확인하며 외부 API 인증/가용성을 시험하지 않는다. 실제 모델·키·context 용량은 명시해야 하고 공급자는 기본 비활성이다. 분석과 채팅은 별도 모델 Bean이다.

`embed(List<CaptionChunker.Chunk>)`는 기존 원문 chunk만 보내며 입력 index 순서 복원·배치 모델 일치·식별자 fallback 정책을 유지한다. 임베딩에는 분석 요약·AI 제안을 사용하지 않는다. 임베딩 배치 크기는 `ANALYSIS_EMBEDDING_BATCH_SIZE` 기본32이며 응답 metadata.model이 없을 때만 `ANALYSIS_EMBEDDING_MODEL_ID`를 사용한다. 실제 임베딩 모델·차원은 Worker·Chat 공통 설정을 따른다.

분할 결과는 Markdown/thinking 태그 제거 없이 파싱하고, 중복 JSON 키·trailing token·null·누락/추가 필드·없는 출처·잘린 응답을 거절한다. 원본 ID 변환 후에도 구간 순서·누락·겹침·구간 밖 인용을 검증한다. 모델용 인용 최대5개는 원본 ID로 펼친 뒤 재적용하지 않는다. 시간·UUID는 기존 서버 검증기에서 결정한다.

| 실패 | 기존 코드·재시도 |
| --- | --- |
| 모델/tokenizer 설정 누락 | `AI_NOT_CONFIGURED`, false |
| 입력·입력 예산 오류 | `AI_INVALID_INPUT`, false |
| JSON·Schema·출처·LENGTH·사용량·합산 예산 오류 | `AI_INVALID_RESPONSE`, false |
| 공급자 거절/비일시 오류 | `AI_REQUEST_REJECTED`, false |
| 일시 공급자·I/O 오류 | `AI_UNAVAILABLE`, true |
| 그 외 공급자 runtime 오류 | `AI_REQUEST_FAILED`, false |

실제 분석 모델은 connect3초/write8초/read120초/호출 전체120초이며 SDK·transport 재시도0회다. 임베딩은 기존 공통 SDK 설정을 유지한다. Worker DB 재시도와 중첩된 호출별 자동 재시도를 추가하지 않는다. 원문·prompt·서명 URL·키·원래 공급자 exception body/cause는 로그/오류에 포함하지 않는다.

실제 Spring AI·OpenAI 연동은 자체 작성한 작은 입력의3단계 호출로 확인했다. Python 실영상7개 최초 실험, 실패 상세 한 건의4,096 진단, PostgreSQL·HTTP 대역 검사를 구분하며 전체7영상/60분/Spring+GCS+기기 흐름·내용 정확도는 미검증이다. 공개 가능한 실제 요청/응답은 `handoff/split/examples/spring-live.actual.json`을 참고한다.
