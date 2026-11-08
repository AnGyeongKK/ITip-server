# 분석 결과 JSON 연결

기존 [개발 논의 05·06](https://www.notion.so/40c46c2fafdf8032a762cbe43121ce41)의 `schemaVersion:1.0` 초안을 Worker의 내부 DTO로 옮겼다. 실제 모델·프롬프트·분할·임베딩 설정은 AI 파트와 확인한다. 이 문서의 테스트 JSON은 실제 분석 품질을 검증한 결과가 아니다.

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
| 원문 참조 | 존재하는 자막 ID만 허용. 구간의 시작·끝 순서와 구간 밖 참조를 확인 |

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
