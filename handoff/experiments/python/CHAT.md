# YouSum 답변 생성 전달물

작성: 2026-11-09. 답변 생성의 첫 구현이며 서비스 연동 전이다.

## 담당 범위와 명세

백엔드 A가 권한을 확인하고 전처리·검색한 근거를 전달한다. AI 파트는 그 근거로 답변을 생성하는 모델·프롬프트·출력 규칙을 검증한다.

- [백엔드 A04~A07](https://app.notion.com/p/284eeb44a8de83669cb101170f55486e)
- [CHAT-04 질문 전송·답변 SSE 스트리밍](https://app.notion.com/p/eaceeb44a8de836a9cf4817c750c3cf8)
- [CHAT-03 저장 메시지 조회](https://app.notion.com/p/a43eeb44a8de82608d4701afedfc527a)

모델은 기존 P0의 `gpt-6-luna`, effort `low`로 시작한다. `chat_prompt.md`와 `chat.schema.json`을 함께 버전 관리한다. 모델 변경 시 같은 입력과 기대 출처로 비교한다.

## 명세 필드와 모델 출력의 연결

| 모델 출력 | CHAT-03/04 연결 |
|---|---|
| content | 사용자에게 표시하고 저장할 답변 본문 |
| sourceIds | 현재 검색 근거에서 선택한 ID. 서버가 sources 메타데이터로 변환 |
| finishReason=STOP | 정상 답변 완료 |
| finishReason=INSUFFICIENT_EVIDENCE | 근거 부족 안내, sources=[], usedRecordCount=0 |

`sourceIds`는 모델 내부 출력용이다. 모바일 API 필드를 바꾸는 제안이 아니다. 모델이 recordId·analysisId·sectionId·영상 ID·시각·분석일·URL을 만들지 않도록 서버가 입력 근거의 메타데이터로 채운다. `usedRecordCount`는 사용된 sources의 고유 recordId 수다.

서버의 status·메시지 ID·scopeRecordCount·SSE sequence는 모델이 생성하지 않는다. `STOP`은 **모델 응답 종료 사유가 stop이고 출력 검증에 통과했을 때만** 정상 답변으로 인정한다. 모델 거절·출력 잘림·형식 오류·유효하지 않은 출처는 근거 부족으로 바꿔 숨기지 않고 생성 실패로 처리한다.

## 실험용 입력

`chat_cases.json`은 백엔드가 전달할 데이터를 모사한 가상 자료다. 아래 envelope는 로컬 실행용이며 공개 API에 추가할 필드가 아니다.

```json
{
  "question": "이 영상에서 작업 시간이 얼마나 줄었대?",
  "history": [],
  "evidence": [
    {
      "sourceId": "src-1",
      "recordId": "301",
      "analysisId": "201",
      "sectionId": "701",
      "title": "반복 작업 자동화",
      "videoId": "fixture0001",
      "startMs": 1000,
      "endMs": 20000,
      "availability": "AVAILABLE",
      "analyzedAt": "2026-11-04T00:00:00Z",
      "origin": "SOURCE",
      "text": "팀 내부 실험에서 처리 시간이 40분에서 10분으로 줄었습니다."
    }
  ]
}
```

history는 user/assistant의 role·content 배열이며 생략할 수 있다. 이전 대화는 질문 해석에만 사용한다. 현재 검색에서 빠진 기록이나 과거 답변의 출처를 사실 근거로 되살리지 않는다. 삭제·권한 확인은 백엔드 책임이며 로컬 검사만으로 소유권이 검증되지는 않는다.

`origin`은 실험 입력에서 명시한다. 원본 자막 등 영상 근거는 SOURCE, 분석 시 덧붙인 팁·예시는 AI_SUGGESTION이다. 여러 종류의 내용이 섞여 있으면 백엔드 어댑터에서 구분한 뒤 전달해야 한다. AI_SUGGESTION을 영상의 사실 근거로 인용하지 못하도록 프롬프트와 참조 검증 양쪽에 규칙을 둔다.

## 실행

워크스페이스 루트에서 실행한다. 실제 키는 기존 `ai/.env`의 OPENAI_API_KEY를 사용한다. 결과와 사용량은 로컬 파일에 저장하고 키를 출력하지 않는다.

```bash
# 외부 호출 없는 검증
uv run --with jsonschema python -m unittest discover -s ai -p test_yousum_chat.py -v

# 가상 근거 9건의 실제 모델 호출. 매번 새로운 결과 디렉터리를 지정한다.
uv run --env-file ai/.env ai/yousum_chat.py round ai/chat_cases.json ai/chat_runs/initial-r1

# 단일 입력 실행
uv run --env-file ai/.env ai/yousum_chat.py run /tmp/chat-input.json /tmp/chat-result.json

# 모델에 보낼 메시지 확인: 외부 호출 없음
uv run ai/yousum_chat.py request /tmp/chat-input.json

# 모델의 원본 JSON을 명세 답변 필드로 변환·검증: 외부 호출 없음
uv run ai/yousum_chat.py validate /tmp/chat-input.json /tmp/chat-model-output.json
```

첫 실험의 max_completion_tokens=4096, 요청 timeout=150초는 로컬 측정용 설정이다. 확정된 제품 답변 길이·채팅 일일 한도가 아니다. 숨은 SDK 재시도는 끄고 케이스당 한 번 호출한다. 실패한 호출은 임의로 자동 재생성하지 않는다.

## 스트리밍 연동

Python 실행기는 완성 응답과 실제 API 스트리밍을 모두 지원한다. `generate_stream()`이 JSON escape를 해제한 본문 delta를 전달하고 마지막에 검증한 결과를 반환한다. `chat_stream.py`는 조각 사이에 잘린 escape·한글·서로게이트 쌍을 처리한다. Spring AI·SSE 서버는 백엔드에서 연결하며 모델 JSON 원문을 모바일 delta로 전달하면 안 된다.

서비스에서는 이 동작을 백엔드 A의 Chat 앱에 연결한다. 취소 시 Python 참조 구현은 generator.close()로 HTTP 스트림을 닫는다. 최종 검증 실패 시 이미 전달한 일부 본문이 있어도 정상 done을 보내지 않는다. 명세 순서는 meta → delta 반복 → sources → DB 저장 완료 → done이다. 서버는 저장 전에 권한·삭제 상태와 출처를 다시 확인한다.

## 검증의 범위

2026-11-09 실행 결과:

| 확인 | 결과 |
|---|---|
| 오프라인 계약 검증 | 9/9 통과 |
| 첫 프롬프트 실제 모델 실행 | 가상 케이스 9/9 통과. 자막을 직접 요구하는 문구를 발견해 보완 |
| 보완 후 실제 모델 실행 | 가상 케이스 9/9 통과, 응답 시간 중앙값 1.61초 |
| 보완 후 토큰 합계 | 입력 8,506 / 출력 534, 9회 합계 |
| 스트리밍 확장 후 실제 API | 가상 사례 9건 + 실제 자막에서 선택한 근거 3건, 총 12/12 통과. 모든 delta 합계와 최종 본문 일치 |

현재 프롬프트 버전은 `b51dfb24`, 결과는 `chat_runs/app-guidance-r1/`에 있다. 각 결과는 원본 모델 출력·변환된 답변·사용량을 포함한다. 실행 디렉터리에는 당시 프롬프트·스키마·케이스도 보관한다. 가상 영상 ID는 실제 YouTube 링크가 아니다.

자동 검증은 구조·출처 ID·출처 중복·origin·근거 부족 상태의 일관성을 검사한다. 본문의 모든 주장이 실제 인용 근거로 뒷받침되는지까지 증명하지는 않는다.

가상 케이스는 수치 보존, 무관한 근거, 빈 근거, 복수 기록 비교, 과거 답변의 사실 재사용 차단, 후속 질문, 자막 속 지시, AI 제안, 무관한 출처 제외를 포함한다. 검색 품질과 실제 사용자 질문 분포에 대한 평가는 아니다.

다음 연동 확인 대상은 실제 검색 결과·현재 권한으로 정제된 대화 이력 입력, Spring AI의 동일 스키마 호출, content 스트리밍·출처 검증·중단 처리다. 추천 영상 방식은 사용자 결정에 따라 보류한다.

출력 스키마 호출은 [OpenAI Structured Outputs 공식 문서](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=chat)를 참고했다.
