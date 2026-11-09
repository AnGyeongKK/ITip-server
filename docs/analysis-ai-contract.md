# 분석 결과 JSON 연결

기존 [개발 논의 05·06](https://www.notion.so/40c46c2fafdf8032a762cbe43121ce41)의 `schemaVersion:1.0` 초안을 Worker의 내부 DTO로 옮겼다. 이후 확인한 [ITip / AI 실험](https://www.notion.so/40f46c2fafdf80b29264e6ba26342725)과 [결과 조회 명세·마인드맵 보완](https://www.notion.so/40c46c2fafdf81b8aeebcea510c56abf)을 아래에 대조했다. 테스트 JSON은 실제 분석 품질을 검증한 결과가 아니다.

## AI 파트와 서버의 책임

```text
AI: 요약·sections의 title/subtopics·mindmap 생성
  → Worker: 원문 근거 검증·sectionId/시간 매핑
  → DB: 검증 결과·검색 데이터·개인 기록 저장 후 완료
  → 결과 GET: 저장된 결과와 영상·기록 조회
  → 앱: rootTitle → 주제 → subtopics를 그래프로 배치
```

AI 문서의 마인드맵 예시는 `sections[].sectionKey/title/subtopics`와 `mindmap.rootTitle/sectionKeys`를 사용한다. 현재 내부 타입과 이 부분은 맞는다. 전체 원시 JSON Schema·프롬프트가 확인된 것은 아니므로 전체 분석 계약까지 일치한다고 판단하지 않는다. 그래프 좌표·색상·연결선은 앱의 표현이며 서버가 마인드맵을 다시 생성하지 않는다.

## 현재 반영 상태와 합의할 차이

| 항목 | 현재 코드 | AI 문서 또는 다음 구현 |
| --- | --- | --- |
| AI 결과 | `AnalysisOutput`의 summary·sections·mindmap | 마인드맵 예시는 호환. 전체 Schema·필수 필드·origin·근거 형식은 실제 원시 응답으로 대조 필요 |
| 서버 변환 | `AnalysisOutputValidator`가 UUID sectionId·실제 시간·키워드 ID 매핑 | 같은 sectionId를 요약·마인드맵·상세에서 공유 |
| 저장·완료 | `WorkerResultStore`가 draft·구간·벡터·기록 저장 | 완료 전에 저장된 draft와 공개 결과를 구분 |
| 공개 조회 | `AnalysisResultController.get()`·`getSection()` 제공 | GET은 저장된 데이터만 조회. 관련 영상 추천은 별도 후속 범위 |
| 자막 입력 | `generate()`가 원본 `CaptionDocument` 전체를 JSON으로 전달 | AI 실험은 10~20초 묶음의 ID·텍스트 중심. 집계 ID ↔ 원본 segmentId·시간 대응과 전체 입력 계약 합의 필요 |
| 구간 수 | sections·mindmap 참조가 비어 있으면 거절. 1개는 허용 | AI 실험은 0개 또는 2개 이상 조건. 0개의 의미·작업 상태·앱 표시 합의 전 현재 검증 유지 |
| 분석 모델 | 실제 `ChatModel` Bean·공급자 starter 없음 | AI 문서 선택은 `gpt-6-luna`, effort `low`. 이 선택을 서버 설정·연동에서 확인할 단계가 남음 |
| 임베딩 | 실제 `EmbeddingModel` Bean 없음 | 분석용 텍스트 생성 모델과 별도로 선택. Spring Chat의 질문 임베딩과 같은 모델·버전·차원 및 입력 규칙 사용 |
| 분석 호출 방식 | 현재 어댑터는 Spring AI 모델 Bean을 직접 호출 | 외부 AI 서버 HTTP 연동은 WebClient로 결정. 분석 endpoint·Schema·프롬프트는 AI 파트와 맞추며 Python 채팅과 별도 계약. 이번 GET에는 외부 호출 없음 |

위 차이는 자동 적용할 정책이 아니다. 특히 sections 0개를 임의의 빈 성공 결과로 바꾸거나 집계 ID를 원본 ID처럼 취급하지 않는다. 모델·프롬프트·Schema의 실제 연결과 한국어·최대 60분 영상의 검증은 아직 완료되지 않았다.

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
GCS 자막 → CaptionParser → s000001, s000002 …
→ 분석 JSON의 sectionKey·sourceSegmentIds
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

`SpringAiAnalysisClient`는 외부 공급자별 모듈이 제공하는 Spring AI `ChatModel`·`EmbeddingModel` Bean을 받는다. 현재 의존성은 `spring-ai-model:2.0.2`와 공통 예외 타입이 있는 `spring-ai-retry:2.0.2`뿐이다. 공급자 starter, 실제 모델 Bean, API 키, 기본 공급자 주소·모델은 포함하지 않는다. AI 문서의 분석 모델 선택과 별도로 실제 프롬프트·Schema 이식, 공급자 연결·품질 검증이 남아 있다. Worker의 스케줄·processor·DB 저장 연결도 이 어댑터의 책임이 아니다.

| 메서드 | 입출력과 확인 범위 |
| --- | --- |
| `checkConfigured()` | 두 모델 Bean이 각각 하나로 선택되는지 확인. 외부 API 호출·인증·모델 가용성은 확인하지 않음. 여러 Bean을 제공하면 `@Primary` 등으로 하나를 선택해야 함 |
| `generate(CaptionDocument)` → `AnalysisOutput` | `BeanOutputConverter<AnalysisOutput>`의 JSON Schema 형식과 `1.0`·한국어·원문 근거·origin 지시를 system message에 포함. 자막 JSON은 별도 user message에 전달하며 원문의 중괄호를 템플릿 변수로 다시 해석하지 않음 |
| `embed(List<CaptionChunker.Chunk>)` → `Embeddings(String model, List<float[]> vectors)` | `chunk.text` 원문만 전달. 배치 응답의 `Embedding.index`로 입력 조각 순서를 복원. 개수·누락·중복·범위를 확인하고 모든 배치가 같은 모델인지 확인 |

JSON 응답은 Markdown·thinking 태그 제거 없이 변환하며 뒤따르는 별도 JSON도 거절한다. 빈 응답·변환 실패는 `AI_INVALID_RESPONSE`로 처리한다. 변환된 `AnalysisOutput`의 `schemaVersion`, 필수 구조, 실제 원문·sectionKey 참조 등은 호출자가 기존 `AnalysisOutputValidator`로 검증한 뒤 저장해야 한다. 임베딩의 finite 값·동일 차원·0 벡터 여부는 기존 `WorkerResultStore.saveIndex()`가 확인한다. 어댑터는 DB에 접근하지 않는다.

임베딩 모델 식별자는 실제 `EmbeddingResponse.getMetadata().getModel()`을 우선한다. 메타데이터가 비어 있을 때만 운영자가 명시한 `analysis.ai.embedding-model-id` 또는 `ANALYSIS_EMBEDDING_MODEL_ID`를 사용한다. 둘 다 없으면 `AI_NOT_CONFIGURED`로 실패하며 이름을 추측하지 않는다. 이 설정은 실제 모델을 선택하는 SDK 옵션이 아니라 저장할 모델 식별자이므로 실제 Bean의 모델과 일치해야 한다.

`analysis.ai.embedding-batch-size` 또는 `ANALYSIS_EMBEDDING_BATCH_SIZE`는 요청 한 번에 포함할 최대 조각 수이며 기본값은 `32`다. 양수만 허용한다. `32`는 요청을 무제한으로 보내지 않기 위한 로컬 기술 기본값이며 서비스의 최종 제한·공급자 토큰 한도·분석 품질 정책을 뜻하지 않는다. 공급자의 개별 입력 토큰 한도는 실제 모델과 조각 설정을 확정할 때 별도로 확인한다.

| `AnalysisAiException.code()` | `retryable()` | 처리 기준 |
| --- | --- | --- |
| `AI_NOT_CONFIGURED` | `false` | 모델 Bean 누락·선택 불가, 잘못된 배치 설정, 응답과 설정 모두에 모델 식별자 없음 |
| `AI_INVALID_INPUT` | `false` | 빈 자막 입력, 빈 조각 목록·원문 |
| `AI_INVALID_RESPONSE` | `false` | JSON 변환·기본 응답 구조·임베딩 index·배치 간 모델 일치 실패. 같은 요청의 자동 재생성을 하지 않음 |
| `AI_UNAVAILABLE` | `true` | `TransientAiException`, 네트워크 I/O 실패, HTTP 408·429·5xx |
| `AI_REQUEST_REJECTED` | `false` | `NonTransientAiException`, 재시도 대상이 아닌 HTTP 응답 |
| `AI_REQUEST_FAILED` | `false` | 분류되지 않은 공급자 runtime 실패. 무조건 재시도하지 않음 |

어댑터는 재시도 루프와 HTTP timeout을 만들지 않는다. 기존 job 시도 상한이 최초 포함 3회이므로 실제 연동 시 모델 Bean 제공자가 SDK 총 시도를 1회(내부 자동 재시도 0회)로 설정하고 HTTP connect/read/전체 호출 시간 상한을 설정·검증해야 한다. 이는 SDK의 개별 호출 기준이며 한 job 단계에서 필요한 임베딩 배치 호출 수를 1개로 제한한다는 뜻은 아니다. 이 모듈의 mock 테스트는 공급자 설정이나 외부 요청 종료 시간을 보장하지 않는다.

원문·전체 prompt·서명 URL·토큰·공급자 응답을 로그에 남기지 않는다. 어댑터 실패는 code와 retryable만 전달하며 민감한 내용을 포함할 수 있는 원래 예외 메시지·cause를 노출하지 않는다. 실제 모델의 내용 차단·응답 품질·요금·rate limit·인증·네트워크는 검증하지 않았다.

SDK 기준은 [Spring AI Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html), [Structured Output Converters](https://docs.spring.io/spring-ai/reference/api/structured-output/converters.html), [Embeddings Model API](https://docs.spring.io/spring-ai/reference/api/embeddings.html)와 Maven Central의 `2.0.2` source JAR다. Spring AI `2.0.x`는 Spring Boot `4.0.x`·`4.1.x`를 지원한다. reference의 일부 예시와 실제 타입이 다르면 해석된 버전의 SDK를 따른다.
