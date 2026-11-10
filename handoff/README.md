# Spring AI 이식 전달물 · spring-handoff-v1

2026-11-10 검토 수정본(v1.1). 변경 내용·현재 검증 결과는 [REVIEW.md](REVIEW.md), 이전 호출 기록은 `examples/verification.json`, 재검토 기록은 `examples/review-verification.json`을 따른다.

Python은 실험용이다. Spring Worker가 전처리·분석·검증·원문 임베딩을, Spring Chat이 검색·답변·저장·SSE를 수행한다. 모델은 앱의 ApiResponse·SSE 이벤트를 만들지 않는다.

## 기준과 파일

기준은 **ITip-server ced9ac382c73f54658e00e24ecf6b7513758bbec**의 [분석 계약](https://github.com/AnGyeongKK/ITip-server/blob/ced9ac3/docs/analysis-ai-contract.md)·[채팅 계약](https://github.com/AnGyeongKK/ITip-server/blob/ced9ac3/docs/chat-generation-contract.md)과 Java CaptionDocument·AnalysisOutput·AnalysisOutputValidator·ChatAnswerGenerator·AnswerJsonStream이다. 전체 문서·Java 코드를 읽었고 원본 blob SHA는 `contracts/verification.json`에 기록했다. Spring 실행은 하지 않았다.

저장소 반영 시 `dev`의 `9f8a288a8dd66fb5c8b0ff0544ee0b21390db637`과 비교했다. 두 계약 문서는 내용이 같다. 최신 연동 기준은 저장소의 [분석 계약](../docs/analysis-ai-contract.md)·[채팅 계약](../docs/chat-generation-contract.md)이며, `contracts/`는 실험 당시 스냅샷이다. 스냅샷 안의 상대 링크는 위 고정 GitHub 원문을 기준으로 읽는다.

| 경로 | 용도 |
|---|---|
| prompts/analysis.spring-v1.system.md | Spring AnalysisOutput용 System 지시문 |
| prompts/chat.spring-v1.system.md | contexts/history 입력과 answer/citations/evidenceStatus 출력 |
| schemas/*.spring-v1.json | 모든 필드·필수값·타입·enum을 포함한 strict JSON Schema |
| schemas/analysis.recommended-v1.json | 호환 기본값과 분리한 최소2구간 권장안. 제품 합의 전 자동 적용하지 않음 |
| preprocessing/normalize.py, JAVA-PORT.md | 10~20초 병합·대응표·원본 ID 복원·Java 이식 순서 |
| validation/contracts.py | 구조·실제 전송 ID·구간 밖 근거·공급자 종료 검증 |
| invocation/run.py, answer_stream.py | 실제 Chat Completions 호출·토큰 예산·JSON 본문 추출 |
| invocation/settings.json, requirements.lock | 모델·옵션·SDK 및 재현 의존성 |
| examples/*.actual.json | 공개용 자체 작성 입력의 실제 전체 요청·응답·사용량·SDK 원시 chunk |
| examples/failure-cases.json | 합성한 잘못된 인용·잘림·거절 처리 예시. 실제 공급자 실패를 관측한 자료는 아님 |
| examples/evaluation.json, tests/ | 질문별 기대 근거·상태와 오프라인 계약 검사 |
| prompts/*experiment*, schemas/*experiment* | 기존 실험 버전 보존용. Spring 최종 버전과 구별 |

## 재현

레포 루트에서 Python 3.13으로 실행한다. 아래 검사는 저장된 공개 예시를 사용하며 API 키나 모델 API 호출이 필요하지 않다. 의존성 설치와 tiktoken의 최초 tokenizer 다운로드에는 네트워크가 필요하다.

```bash
python3.13 -m venv handoff/.venv
handoff/.venv/bin/pip install --require-hashes -r handoff/invocation/requirements.lock
handoff/.venv/bin/python -m unittest discover -s handoff/tests -v
handoff/.venv/bin/python handoff/validation/verify_examples.py

# OPENAI_API_KEY를 각자 환경변수로 설정한 뒤 실행. 실제 키를 파일·명령 인자·Git에 기록하지 않는다.
handoff/.venv/bin/python handoff/invocation/run.py analysis handoff/examples/analysis.input.json /tmp/analysis-new.json
# 최소2구간 대안은 명시적으로 선택한다.
handoff/.venv/bin/python handoff/invocation/run.py analysis handoff/examples/analysis.input.json /tmp/analysis-min2-new.json --profile recommended-v1
handoff/.venv/bin/python handoff/invocation/run.py chat handoff/examples/chat.supported.input.json /tmp/chat-new.json --stream
# 호출 없이 실제 요청 JSON 구성 확인
handoff/.venv/bin/python handoff/invocation/run.py chat handoff/examples/chat.supported.input.json /tmp/chat-request.json --stream --request-only
```

이미 있는 결과 파일은 덮어쓰지 않는다. 새 경로로 재현한다. 고정 System과 동적 JSON UserMessage를 분리하고, Schema는 DTO에서 재생성하지 말고 파일 그대로 공급자 strict 옵션에 전달한다. 분석 Schema의 sections 필드 순서는 기존 실험처럼 summary보다 앞이다. 채팅 첫 필드는 answer이며 스트리밍 파서가 이 순서를 전제로 한다.

## Java 이식 순서

1. CaptionDocument를 입력받아 group 대응표를 만드는 전처리를 이식한다. 원본 CaptionDocument는 RAG·출처 검증용으로 그대로 보관한다.
2. 분석 System+JSON UserMessage+전체 Schema를 Worker 모델에 전달한다. gpt-6-luna / low / max_completion_tokens=16000, 내부 자동 재시도0회다.
3. group 출력 검증 → 원본 segmentId 복원 → 현재 Spring 검증 → sectionKey/UUID·시간 매핑 순서로 처리한다.
   모델 group 단계에서는 입력의 처음·중간·마지막이 빠지지 않도록 전체 범위도 확인한다. 원본 단계는 제외된 음악 태그와 시간 겹침을 보존하는 현재 Spring 규칙을 따른다.
4. Chat의 현재 contexts/history를 사용해 System과 채팅 Schema를 적용한다. gpt-6-luna / low / 출력2048로 실제 호출했다. 공개 이벤트는 Spring이 생성한다.
5. provider STOP 확인·JSON/출처 검증·권한 재검사·DB 완료 저장 뒤 sources/done을 보낸다. 모델의 SUPPORTED는 공급자 정상 종료를 대신하지 않는다.

## 계약 차이와 합의할 사항

| 항목 | 기존 실험 | 전달용 Spring 버전·남은 결정 |
|---|---|---|
| 분석 최상위 | contentStatus 추가 | 현재 DTO의 schemaVersion/summary/sections/mindmap만 출력 |
| 구간 수 | 0개 또는 2개 이상 | 현재 Spring처럼 1개 이상 허용. 1개 조기 종료 방지 품질은 재검증 필요 |
| 0개 | NOT_ANALYZABLE | 빈 성공으로 변환하지 않음. 의미 없는 자막의 상태·작업 종료·앱 표시는 합의 필요 |
| 실무 팁 | title+text+origin+근거 | Java TextPoint의 text/origin/근거. title 필드는 제외. UI 제목 유지 여부는 합의 필요 |
| 상세 구간 밖 근거 | 이전 참조 실행기에서 경고 통과 | Spring처럼 거절. 새 프롬프트가 자기 구간의 근거만 요구. 기존 42분 응답을 그대로 넣으면 실패할 수 있음 |
| 분석 ID | s0001 형태 묶음 | g000001 묶음 → s000001 원본 대응표로 복원 |
| 채팅 | evidence/sourceIds/content/finishReason | contexts/contextId+sectionId/answer/evidenceStatus로 변경 |
| 채팅 근거 | origin별 결과도 입력 | 현재 Spring은 자막 원문만 contexts에 전달. AI 제안 혼입 금지 |
| 한 chunk의 복수 sectionIds | 이전 사례에서 다루지 않음 | 현재 계약 후보 내에서만 인용. 원문 내부의 세부 구간 매핑이 없으면 장면 수준 정확성을 보장하지 못함 |

구간0/1·팁 제목·정확한 구간 인용처럼 필드 의미·품질이 바뀌는 점은 자동 확정한 제품 정책이 아니다. Spring 호환 버전을 제공하고 기존 실험 버전도 보존했다.

현재 전달 버전의 실제 시험에서도 최소1구간 설정은 한 구간만 생성한 뒤 존재하지 않는 section-02를 마인드맵에서 참조하는 실패가 관측됐다(`analysis.final-schema-r2.actual.json`). 검증기가 이를 AI_INVALID_RESPONSE로 거절했다. `analysis.recommended.actual.json`은 최소2구간 대안의 실제 호출이다. 작은 가상 샘플이므로 대안의 전체 품질이 검증됐다고 일반화하지 않는다. 최초 실패한 한 호출은 진단 저장 코드 추가 전이라 원문이 남아 있지 않고 실행 실패만 관측됐다.

## 입력 예산·임베딩

모델에 영상 길이 durationMs를 전달한다. 전처리는 원본 사본을 보관하고, 설명란 URL 제거·1500자 제한·태그 합계300자 제한을 적용한다. 태그 이름은 중간에서 자르지 않는다. 최소2구간 대안은 분석 입력 묶음이 하나뿐일 때 호출 전에 거절한다.

Chat은 계약과 같이 history 최대5쌍/32000 UTF-16 문자, contexts 최대20개/원문24000 UTF-16 문자다. 기본 검색 topK8은 백엔드 설정이다. 토큰 추정은 o200k_base로 system·JSON·Schema+128을 세고 8000을 넘으면 오래된 대화 쌍부터, 그다음 후순위 context를 통째로 뺀다. 전송 contextId를 C1부터 다시 매기고 대응표를 기록한다. 질문이나 원문 중간을 자르지 않는다. 필수 입력만으로도 초과하면 CHAT_PROMPT_TOO_LARGE다. 제외된 context는 인용할 수 없다.

분석 입력 예산30000/출력16000은 실험 설정이다. 초과 시 자동으로 원문을 잘라 분석하지 않고 실패시킨다. 모델 전체 context 용량과 Spring CHAT_CONTEXT_TOKENS는 계정·공급자 문서로 확인한 값이 없어 미정으로 남겼다. 실험 성공만으로 모델 최대 context를 추정하지 않는다.

참조 코드의 timeout=120은 Python SDK 요청 옵션이다. Spring의 연결3초·읽기 공백30초·전체120초·MVC140초·lease160초를 이 Python 시험에서 재현한 것으로 표시하지 않는다. 공급자 정상 종료·본문 검증을 확인한 범위와 Spring 운영 시간 제한은 구분한다.

임베딩은 기존 제안 text-embedding-3-large / 1024차원이 있으나 한국어 검색 비교·실제 임베딩 호출은 미실시다. 현재 Spring의 문서=자막 chunk.text, 질문=question 무접두사 규칙을 기록했다. 모델·버전·차원·chunk 크기/겹침·topK/임계값은 합의·평가 대상이다.

## 검증 범위

현재 계약·회귀 검사는 44개다. 실행기는 결과 파일을 호출 전에 배타적으로 생성해 기존 파일을 보호한다. 실패 시에도 요청·SDK 조각·부분 본문·안전한 오류 코드를 보존한다. 중단된 실행은 최소 IN_PROGRESS 기록을 남긴다. 이 파일들은 개발용 실험 아티팩트이며, 서비스 로그에 원문을 남기라는 뜻이 아니다.

게시 전 독립 리뷰에서 과거 채팅 요청 기록의 스트리밍 옵션 누락을 확인했다. 당시 기록은 수정하지 않고 [재현 보완 방법](examples/STREAMING.md)을 명시했다. 현재 코드는 저장 요청과 SDK 전송 요청의 일치를 검사한다. 게시 검증은 `examples/repository-verification.json`에 기록하며, 기존 `review-verification.json`의 체크섬과 42개 검사는 이전 검토 시점의 기록이다. 이 수정 후 새로운 실제 모델 호출은 하지 않았다.

`examples/verification.json`에 이번 Spring 전달 버전의 실제 호출·오프라인 검사 결과를 기록한다. 공개 샘플은 직접 작성한 가상 자막·질문이며 실제 API가 생성한 전체 응답이다. 다른 사람의 원본 자막·실제 사용자 대화·개인정보·키를 포함하지 않는다.

이전 버전의 7개 영상 반복시험·실제42분·합성60분·채팅12건 결과는 기존 실험 형식의 결과다. 이번 Spring Schema/프롬프트가 같은 품질을 유지한다는 증거로 합산하지 않는다. Spring AI 실행·DB·권한 필터·실제 검색 품질·모바일 SSE·실제60분 영상은 아직 이 전달물로 검증하지 않았다. 추천 영상은 후순위로 보류한다.
