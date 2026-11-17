# 현재 전달본: Luna-low 분할 분석

사용자가 선택한 경로는 구간 계획 → 구간별 상세 → 전체 요약이다. 마인드맵 구간 목록과 JSON은 서버 코드로 조립한다. 실제 실행 경로는 Java Worker의 `SplitAnalysisGenerator`다. 이 디렉터리의 Python은 실험·이식 참조이며 실행용 Python 서버가 아니다.

## 파일

- `pipeline.py`: 단계별 전체 Schema, 경계·입력 선택·원문 근거·합산 예산·조립의 Python 참조. 상세 cap4,096이다.
- `plan.system.md`, `detail.system.md`, `summary.system.md`: 실제 Worker에 복사한 고정 System. 입력은 매번 JSON UserMessage다.
- `test_pipeline.py`: 공개 가능한 자체 작성 입력으로 기존 계약·출처·경계·예산을 검사한다.
- `examples/example.input.json`, `example.output.json`: 직접 작성한 전체 계약 예시. 실영상 결과가 아니다.
- `examples/spring-live.actual.json`: 자체 작성 자막의 실제 Spring AI→OpenAI3단계 요청·전체 Schema·원시 JSON 문자열·종료·usage·최종 결과다. 키·HTTP 인증 헤더는 없다.
- `evaluation.json`: 기존 실영상7개 실험과 실패 구간4,096 진단을 구분한 집계. raw 실영상 자막은 포함하지 않는다.

최종 전체 계약은 기존 `../schemas/analysis.spring-v1.json`(schemaVersion1.0)이다. 단계별 Schema는 입력 ID에 따라 `pipeline.py`와 Java `AnalysisSchemas`에서 생성한다. 원래 `handoff/manifest.json`, 단일 호출 실험·실제 응답은 이전 전달본의 기록으로 유지하며 덮어쓰지 않는다.

## Java 이식·실행

Java 이식은 이 PR의 Worker에 반영했다. [실제 실행 순서·ID 대응·설정·검증 한계](../../docs/analysis-split-generation.md)를 따른다. 상세에 해당 구간 원문만 보내고 최종 요약에는 검증된 상세와 실제 인용·수치 원문을 함께 보낸다. source group을 원본 segmentId 목록으로 펼친 후 기존 저장/조회 경로를 사용한다. 검색·임베딩은 원본 자막을 그대로 사용한다.

영상 합산 출력·추론 예산은16,000, 단계 cap은 계획1,024/상세4,096/요약3,072다. 상세 최대3개 병렬, SDK/transport 재시도0회다. 예산이 부족하면 단계 cap은 낮아질 수 있다. 부분 실패를 완료로 만들거나 원래 결과를 자동 보정하지 않는다. 현재 부분 재시도/재개는 구현하지 않았다.

Python 검사(기존 `../invocation/requirements.lock`의 jsonschema 의존성 필요):

```sh
python -m unittest discover -s handoff/split -p 'test_*.py' -v
```

Worker 검사·실행 설정은 위 서버 문서에서 확인한다. `gpt-6-luna / low`를 선택했으며 공급자는 기본 비활성이고 키·모델·tokenizer·context 용량을 명시해야 한다. 공개 API·DB·채팅 SSE 형식은 변경하지 않는다.

실영상 최초 실험은6/7이며 실패한 상세 한 건만4,096으로 재시험해 통과했다. 이를7/7이나 운영 검증 완료로 바꾸어 표시하지 않는다. 실제 Spring AI3회 연동은 직접 작성한 작은 입력에 한정된다. 전체 사실 정확도·새 영상·반복 안정성·긴 영상·실제 기기/GCS 흐름 검증은 남아 있다.
