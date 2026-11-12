# 스트리밍 예시와 실패 분류

`chat.*.actual.json` 및 `review.chat.*.actual.json`의 request에는 당시 실제 호출의 공통 Chat Completions 인자를 저장했고 response는 전체 구조화 출력이다. 이 과거 기록 8건과 `chat.request.json`에는 SDK 호출 시 별도로 추가했던 `stream=true`, `stream_options={"include_usage":true}`가 빠져 있다. 기존 기록을 보존하므로, 이 요청 JSON으로 스트리밍을 재현할 때는 아래 두 인자를 추가해야 한다.

```python
stream_request = {**record["request"], "stream": True, "stream_options": {"include_usage": True}}
with client.chat.completions.create(**stream_request) as chunks:
    ...
```

현재 실행기는 완성한 요청 객체를 저장과 SDK 호출에 함께 사용한다. `--stream --request-only`도 이 두 인자를 포함한다. 이 수정은 mock SDK 호출과 요청 생성으로 검증했으며 수정 후 공급자를 새로 호출하지 않았다. providerChunks는 SDK가 실제로 받은 ChatCompletionChunk의 model_dump JSON이다. 원시 HTTP 헤더·API 키·전송 바이트는 기록하지 않았으며, SDK가 파싱한 공급자 chunk를 그대로 저장했다. answerDeltas는 JSON 파서에서 answer만 해제한 본문이다.

일반 텍스트만 스트리밍한 시험이 아니다. 실제 호출에 `response_format.type=json_schema`, `json_schema.strict=true`, 전체 `chat.spring-v1.json`을 넣은 상태에서 스트리밍했다. JSON 구문·citations·evidenceStatus는 answer 본문으로 내보내지 않는다. `invocation/answer_stream.py`가 첫 answer 문자열만 escape·Unicode 단위로 해제하고, 종료 후 전체 JSON의 중복 필드·잘림·본문 일치를 검사한다. `validation/contracts.py`가 실제로 전송한 contextId/sectionIds 쌍을 검증한다.

Python 예시는 메모리에서 delta를 모아 최종 본문과 비교하는 실험이다. Spring은 이미 구현한 AnswerJsonStream 및 부분 저장 경로에 이 규칙을 적용한다. 앱의 공개 meta/delta/sources/done·ApiResponse는 모델이 생성하지 않는다.

검토 수정본에서는 기존 파일 충돌을 API 호출 전에 검사하고, 호출 실패에도 지정한 아티팩트에 SDK 조각·부분 본문·안전한 오류 코드를 보존한다. 종료 뒤 usage 전용 chunk는 허용하되 추가 생성 choice는 거절한다. 최종 answer의 UTF-16 길이 제한은 조각 수신 중에도 검사한다.

`analysis.request.json`은 이번 검토에서 성공한 recommended-v1 요청이며 최소2구간 조건을 포함한다. 기본 spring-v1 요청은 실행기의 `--request-only`로 별도 구성할 수 있다. 기본/권장 Schema 선택은 명시적이고 제품 합의 전 자동 전환하지 않는다.

| 관측 | 모델 evidenceStatus와 별개인 처리 |
|---|---|
| 공급자 stop + 완전한 JSON + SUPPORTED + 유효 인용 | Spring에서 최종 권한 확인·저장 후 STOP 완료 |
| 공급자 stop + INSUFFICIENT_EVIDENCE + citations=[] | 근거 부족 안내로 정상 완료 |
| 잘못된 contextId 또는 해당 context에 없는 sectionId | CHAT_INVALID_AI_RESPONSE, 정상 완료 금지 |
| finish_reason=length | CHAT_ANSWER_TOO_LONG, 부분 본문이 있으면 INCOMPLETE, 없으면 FAILED |
| refusal 또는 content_filter | CHAT_AI_REFUSED, 거절 응답을 사용자 본문으로 전달하지 않음 |
| finish metadata 없는 EOF·통신 실패·timeout | CHAT_STREAM_FAILED, 이미 저장한 부분 본문 보존 |
| JSON 손상·중복 키·본문 불일치 | CHAT_INVALID_AI_RESPONSE |

실패 상태·오류 이벤트·재요청 정책은 Spring이 담당한다. 모델이 본문에 STOP·완료라고 쓰거나 evidenceStatus=SUPPORTED를 반환해도 공급자의 정상 종료 metadata가 없으면 완료가 아니다. SDK 자동 재시도는0회다.

정상·근거 부족의 실제 공급자 이벤트는 actual 파일에서 확인할 수 있다. `failure-cases.json`의 잘린 응답·잘못된 인용·거절은 **합성 fixture**이며 오프라인 검사로 확인했다. 실제 API의 거절·통신 장애를 이번 시험에서 유발한 결과로 표시하지 않는다. Byte/escape 분할 시험 역시 합성 chunk다.
