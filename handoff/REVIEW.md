# 전달 코드 검토·수정 기록 · 2026-11-10

대상은 Spring 계약 `ced9ac3`에 맞춰 만든 현재 handoff 코드다. 기존 실험 스냅샷과 과거 실제 응답은 보존했다.

| 확인한 문제 | 수정 |
|---|---|
| 결과 파일 존재 여부를 API 호출 뒤 확인해 같은 명령 재실행 시 불필요한 호출 발생 | 호출 전 확인하고 출력 파일을 배타적으로 생성. 기존 파일이 있으면 모델을 호출하지 않음 |
| 채팅의 잘못된 출처·잘림·통신 오류에서 진단 아티팩트가 남지 않음 | 요청·공급자 chunk·부분 본문·응답·안전한 오류 코드·retryable을 지정 파일에 보존. 콘솔에는 원문·공급자 오류 메시지를 출력하지 않음 |
| 공급자 종료 뒤 추가 생성 조각을 수용 | 정상 종료 뒤 생성 choice를 거절. 종료 뒤 usage 전용 chunk는 허용 |
| 공급자 STOP 뒤 JSON이 잘려도 재시도 가능한 스트림 오류처럼 분류 | STOP+손상 JSON은 CHAT_INVALID_AI_RESPONSE, 종료 metadata 없는 EOF·통신 실패는 CHAT_STREAM_FAILED로 구분 |
| 분석 호출 오류에 채팅용 코드 사용 | 분석은 AI_INVALID_RESPONSE / AI_UNAVAILABLE / AI_REQUEST_REJECTED 등 분석 코드로 분리 |
| 요청한 최소2구간 Schema를 별도로 다시 검사하지 않음 | 실제 요청한 profile의 Schema로 검증. 입력 묶음이 하나뿐이면 최소2구간 profile은 호출 전에 거절 |
| 구간 ID가 유효해도 분석 입력의 처음·중간·마지막 일부가 누락될 수 있음 | 모델 group 단계에서 전체 입력을 빠짐없이 덮도록 검사. Spring DTO 자체의 현재 허용 범위와 구분해 opt-in 검사로 제공 |
| 원문 대응표의 원본 객체가 호출자 변경의 영향을 받음 | 원본 CaptionDocument 사본을 보관해 대응표의 입력을 고정 |
| 분석 모델에 영상 길이를 전달하지 않음 | durationMs를 UserMessage에 포함. 서버 시각을 모델이 생성하게 하지는 않음 |
| 설명란·태그의 기존 실험 입력 제한이 누락 | 설명란 URL 제거·1500자 제한, 태그는 이름을 중간에서 자르지 않고 합계300자 이하로 제한 |
| 일부 잘못된 list/object·UUID 입력과 비유한 수 처리 부족 | 호출 전 타입 검사, 중복 JSON 필드·NaN·Infinity·실수 overflow를 거절 |
| 저장 응답만 검사하고 실제 공급자 조각과 비교하지 않음 | SDK chunk를 재생해 응답·delta 본문·요청 입력·Schema 체크섬까지 재검사. python -O에서도 검사 유지 |

## 확인한 결과

- 기존 테스트16개 + 회귀 테스트26개, **42/42 통과**.
- 기존 실제 채팅6건과 성공 분석2건을 재검사하고, 과거 잘못된 분석1건이 계속 거절되는지 확인했다.
- 수정한 분석(명시적 recommended-v1)1건과 채팅2건을 OpenAI API로 새로 호출했다. 분석15.973초·정상 채팅2.342초·근거 부족3.061초이며 셋 모두 검증 통과했다.
- 새 호출의 요청·전체 응답·실제 SDK chunk는 `examples/review.*.actual.json`에 있다. 모든 공개 예시는 직접 작성한 가상 자료다.
- 오류·네트워크 중단·잘린 JSON·종료 뒤 조각·토큰 제외·UTF-16 제한은 mock/합성 입력으로 확인했다. 실제 공급자 장애를 유발했다고 표시하지 않는다.

재현:

```bash
.venv/bin/python -m unittest discover -s handoff/tests -v
.venv/bin/python handoff/validation/verify_examples.py
```

## 남은 합의·검증

Spring 호환 기본값인 최소1구간은 유지했다. 최소2구간은 명시적 권장 대안이며, 0구간/NOT_ANALYZABLE 처리·실무 팁 title·복수 sectionIds의 세부 위치 대응은 여전히 합의 대상이다. 실제 Spring 실행·DB·검색 품질·모바일 SSE·실제60분 영상 검증을 이번 코드 검토만으로 완료 처리하지 않았다.

이번 버전은 Python 실험·Java 이식용 참조 코드다. 별도 Python 서버나 앱용 SSE 이벤트 생성기를 추가하지 않았다.
