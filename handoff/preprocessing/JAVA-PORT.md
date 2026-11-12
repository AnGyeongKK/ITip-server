# CaptionDocument → 분석용 groups → 원본 ID 복원

기준: Spring `CaptionParser`는 `s000001` 형식의 원본 ID를 부여하고 시작 시각 순서를 검사한다. 겹치는 원문과 시간을 보존한다. `normalize.py`는 그 **검증된 CaptionDocument**를 입력으로 받는다. 업로드의 language는 현재 Spring 기준 `ko`이며 번역·트랙 선택은 모바일에서 처리한다.

Java 이식 순서:

1. 원본 리스트를 수정하지 않고 segmentId → 원본 index/시각 표를 만든다. 빈 입력·중복 ID·시간 역전·60분 초과·영상 끝 초과는 계약 오류다.
2. 공백을 합치고 `[음악]` 같은 태그만 있는 줄은 분석 텍스트에서 제외한다. 제외 ID는 별도로 남긴다. 모든 원문이 제외되면 EMPTY_ANALYSIS_INPUT으로 호출을 중단한다. 일반 잡담의 의미 판별은 이 전처리로 보장되지 않는다.
3. 병합 판단에만 현재 자막 끝을 다음 유효 자막 시작으로 자른다. 원본은 보존한다. 텍스트를 버리거나 새 타임스탬프를 추정하지 않는다.
4. 버퍼 시작부터 10초 이상이고 마지막 문장이 `.?!`로 끝나면 flush한다. 20초 이상이면 구두점과 관계없이 flush한다. 끝에 남은 짧은 버퍼도 flush한다.
5. 단일 원본 자막이 20초보다 길면 텍스트를 임의 분할해 시각을 추정하지 않고 한 묶음으로 유지한다. 마지막 자막을 더한 뒤 임계값을 검사하므로 20초는 엄격한 최대 길이가 아니다.
6. 분석용 groupId는 `g000001`처럼 원본과 다른 namespace를 사용한다. 매 group에 originalSegmentIds·startMs·endMs를 보관한다. endMs는 포함 원본의 최대 종료 시각이다.
7. 모델에는 영상 길이 durationMs와 groupId·text를 보내고 대응표는 서버에 둔다. 모델의 start/end group은 첫/마지막 원본 ID로 복원하고 sourceSegmentIds는 원본 ID 목록으로 펼친다. 순서를 유지하며 중복을 제거한다.
8. 모델 그룹 기준과 원본 기준으로 두 번 경계·출처를 검사한다. 원본 복원 후 Spring AnalysisOutputValidator에 전달한다. 원본 ID마다 시각은 서버 CaptionDocument에서 가져온다.

모델 그룹 단계에서는 첫 그룹부터 마지막 그룹까지 빠짐없이 한 번씩 덮는지도 검사한다. 원본 단계에서는 분석에서 제외한 음악 태그가 있으므로 전체 원본을 억지로 인용하도록 만들지 않는다. 대응표가 참조하는 원본은 사본으로 고정해 호출자 변경이 매핑을 바꾸지 않게 한다.

모델의 근거 ID 상한5개는 **group**의 출력 토큰 제약이다. 한 group이 많은 원본을 포함하면 복원된 원본 근거는 5개를 넘을 수 있다. 이를 잘라 근거를 잃지 않는다. 현재 Java DTO의 List에는 이 상한이 없다.

기존 실험 `ai_runtime.py`는 업로드의 끝 시각을 영상 길이로 보정했지만 현재 Spring CaptionParser는 초과 값을 거절한다. 전달용 전처리는 **Spring과 같이 거절**한다. 보정 도입은 모바일·백엔드 합의 전 자동 적용하지 않는다.

분석용 group은 LLM 입력 토큰과 주제 분할을 위한 단위다. RAG의 검색 chunk는 원본 자막에서 독립 생성한다. group의 요약·AI 제안문을 원문 임베딩으로 대체하지 않는다. Spring CaptionChunker의 현재 무겹침 문자 분할은 별도 품질 검증 대상이다.

`examples/analysis.actual.json`은 실제 모델 호출의 입력·대응표·전체 출력·원본 ID로 복원한 springOutput을 함께 포함한다. `analysis.mapping-demo.json`은 두 개 이상의 원본이 한 group에 합쳐지는 오프라인 변환 예시다.
