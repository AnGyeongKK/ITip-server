# 채팅 구현 검토

## 현재: Spring AI로 검색·답변 직접 처리 (PR #21)

- 기준 dev `0551364`에서 시작했고 이전 커밋을 유지한 채 직접 호출로 전환했다. 모델·키는 사용자가 나중에 설정하며 실제 키 발급·외부 모델 호출은 하지 않았다.
- `4c1f198` 공통 공급자 설정, `1d65804` 프롬프트/예산/구조화 스트림, `bb57cd1` HTTP408 재전송 차단, `07e108f` API 연결 커밋은 각각 독립 리뷰 PASS다.
- 실제 실패 1건: SDK maxRetries0 아래 Spring AI 기본 HTTP transport가408을 재전송해 요청2회가 됐다. 같은 SDK4.80.0 공식 transport로 바꾸고 401/408/429/503에서 단일 시도를 확인했다.
- Java21 `./gradlew build`: **312개**, 실패·오류·건너뜀0. API107·Worker100·Chat78·common6·domain19·ai2. 실제 SDK→로컬 HTTP, 실제 pgvector·앱 포트의 조기 본문/부분 저장·인용/권한·삭제 경합을 포함한다.
- 토큰 예산으로 제외한 원문 인용, 잘린 JSON·출처, 공급자 종료 없는 EOF·LENGTH·거절을 성공으로 처리하지 않는지 확인했다. 본문만 전송하고 완료 저장 뒤 done을 보낸다.
- Compose 렌더링에서 Worker/Chat의 모델·예산 전달, 일반 API의 키 제외, Python 변수 제거를 확인했다.
- HTML의 38개 API 선택·Spring 검색/GPT 직접 연결, 독립 파일의 CHAT-03/04 전환을 확인했다. 원문/독립 파일 일치·기존 스타일 wrapper 유지·고정 코드 파일33개·JS 문법·문서 파일 링크를 검사했다.
- 현재 계약은 [Spring 직접 생성](chat-generation-contract.md)이다. 분석용 ChatModel 실제 연결, 답변/임베딩 모델·encoding 선택·실제 품질/시간/비용·모바일 네트워크 시연은 별도 확인 대상이다.

아래는 이전 PR·설계 시점 기록이다. Python 경유·당시 테스트 수를 현재 구현 상태로 해석하지 않는다.

## Spring 질문 임베딩·pgvector 검색 전환

- 기준 `dev`: PR #19 병합 `0551364`. 공개 앱 요청·SSE 필드는 유지하고 Python 내부 요청만 schema2.0 contexts로 변경한다. DB 마이그레이션은 없다.
- `ef0b4e5` 리뷰: 공급자 영구 오류도 재시도 가능으로 표시하는 P2 발견. `753fb94`에서 원인별 분류를 수정했고 신규 테스트의 실패→통과와 독립 재리뷰 PASS를 확인했다.
- `4ab3ffe` 리뷰 PASS: 임베딩 후 현재 범위/history 확인, 실제 전달한 근거만 인용, 짧은 TX·완료 후 done 유지. 후속 Compose에서 모델 fallback·topK 전달을 확인한다.
- Java21 `./gradlew build`: **294개**, 실패·오류·건너뜀0. API107·Worker99·Chat63·common6·domain19. 로컬 pgvector, HTTP fixture, 실제 HTTP 첫 delta·부분 저장·삭제 경합을 포함한다.
- API 호출 HTML은 PR #21·실제 검색 함수로 연결한다. 브라우저에서 원문의 38개 API 전환·Chat 검색 화살표·Python DB 연결 제거를 확인했고, 독립 HTML의 CHAT-03/04 전환도 확인했다. 원문/독립 파일 일치·기존 스타일 wrapper 유지·고정 커밋의 코드 링크30개·JS 문법 검사를 통과했다.
- 실제 공급자 Bean·Python2.0/GPT 실통신·의미 검색/답변 품질은 미검증이다. 이후 기준 질문 세트로 근거·답변·지연·비용을 측정하고 필요할 때 고급 RAG를 도입한다. [품질 개선 계획](chat-contract.md#답변-품질-개선-계획)을 따른다.

아래는 각 이전 PR 시점의 검증 기록이다.

## 채팅방·대화 기록 (#6)

| 커밋 | 검토 결과 |
| --- | --- |
| 7e9cd32 | PASS · 독립 Chat 모듈·공통 HTTP 처리 |
| c4c1be6 | PASS · 메시지·출처·동시 생성 DB 제약 |
| 96cac97 | PASS · 메시지 커서·요청 ID 복구·삭제 출처 |
| 93fdf2c | FIX · 검색어 내부 공백 변경 |
| db83ec8 | PASS · 내부 공백 보존·PostgreSQL 시각 정밀도. 두 실패 재현 후 수정 |
| f918a00 | PASS · 방 목록·상세·삭제 |
| adac9d7 | PASS · 실행·API 안내 |

전체 검토: `39361fb..adac9d7`, 10개 관점의 독립 리뷰와 별도 검증. 실행·소유권·API·DB·실패 처리·불필요한 추상화를 확인했다. 리뷰 실행 ID는 `20261109-021906-507abe39`, 결과 `complete / Ready with fixes`다.

확인된 P2 한 건은 삭제한 방의 생성 키 재전송 예외 설명 누락이다. `docs/chat-contract.md`에 404·복원 없음으로 명시하고 실제 HTTP 생성→삭제→재전송 테스트를 추가했다. 구현 동작은 변경하지 않았다.

단순화 검토는 프로덕션 코드 1408줄 상한·cutoff 5000의 결합 리뷰로 수행했고 추가 수정 제안은 없었다. 증명에 사용한 테스트는 약화하지 않았다.

전체 `./gradlew build`는 232개 테스트로 통과했다. 마지막 삭제 키 테스트를 포함한 ConversationReadDeleteApiTest 7개도 통과했다. Chat Docker 이미지는 공식 공개 미러로 빌드했다. Docker Hub 기본 주소는 기존 인증 만료로 실패했으며 인증 설정은 변경하지 않았다.

리뷰 에이전트는 빌드 실행 증거와 직접 실행을 구분했다. 다른 모델에 보내는 외부 검토는 더티 파일을 제외한 범위 지정이 불가능해 수행하지 않았고, 커밋 범위만 읽는 독립 공격적 검토로 대체했다. 실제 GCS·Python·GPT·컨테이너 실행은 이 검토에서 검증하지 않았다.

범위 조건 재평가와 최소 폴더 연결 구조는 구현 제안이다. 사용자 확인으로 확정됐다고 기록하지 않으며 폴더 담당과 병합 전 맞춘다. 일반 DB 구조 SQL만 추적하고 비밀 파일·실제 값은 포함하지 않는다.

## Python·SSE (#7)

| 커밋 | 검토 결과 |
| --- | --- |
| fd018f1 | PASS · 질문 원자 접수·동시 생성·만료·재요청 |
| fda03ff | PASS · WebClient·MVC SSE·출처·최종 저장 |
| 66ff1cc | PASS · JSON 오류 분류·끊김·idle·동시 요청 실패 검증 |
| 76059ca | PASS · 완료 이력·현재 범위의 후속 질문 문맥 |
| 7ecdad0 | PASS · 실패 상태도 저장할 수 없는 DB 장애 경계 |
| ce1a171 | PASS · 실제 함수 호출·TX·실행 안내 |
| ad82441 | PASS · 실제 HTTP 증분 SSE flush |
| 30c547a | PASS · 출처 확인 후 삭제 경합 잠금 |
| 67b1558 | PASS · heartbeat·완료와 만료 복구의 동시 실행 |
| 2e052c1 | PASS · 로컬 AI 환경변수와 테스트 분리 |

Java21 최종 `./gradlew build`는 258개 테스트, 실패·오류·skip0으로 통과했다. 가짜 AI 주소·토큰 환경변수가 있는 조건에서도 같은 전체 빌드를 확인했다. 정상 HTTP 응답은 실제 로컬 HTTP 서버와 PostgreSQL을 사용했고, 앱 출력 IOException·DB 장애 경계는 대역으로 재현했다. 실제 30초 idle timeout을 실행했다. 실제 모바일 끊김·Python/GPT 응답 품질·RAG·GCS는 검증하지 않았다.

전체 검토: `5a130cf..ce1a171`, 실행 ID `20261109-030525-5d1ce60f`, 결과 `complete / Ready with fixes`. 아래 두 지적을 각각 작은 수정 커밋과 독립 재검토로 반영했다.

| 지적 | 수정·확인 |
| --- | --- |
| #1 P1 · Spring7 callback flush가 실제 HTTP에 전달되지 않음 | ad82441. 실제 포트 테스트 실패→통과. Docker Chat에서도 upstream 완료 전 첫 delta·GENERATING 부분 저장을 확인하고 완료 후 본문 일치를 확인 |
| #2 P2 · 출처 확인 후 commit 전 기록 삭제 가능 | 30c547a. FOR SHARE·일관된 잠금 순서. 실제 SQL 직후 DELETE가 잠금 대기하고 완료 후 삭제되며 출처는 보존됨 |

리뷰의 heartbeat·만료 복구 검증 공백도 테스트로 추가 확인했다. 단순화 검토는 정확한 #7 diff의 프로덕션 코드 676줄 상한·cutoff5000의 결합 리뷰로 수행했고 추가 수정 제안은 없었다. 외부 모델 검토는 #6과 같은 범위 제외 제약으로 수행하지 않았고 독립 로컬 검토를 사용했다. 현재 코드 지적 미반영 항목은 없다.

컨테이너 검증은 임시 전용 PostgreSQL과 합성 완료 분석·로컬 HTTP AI fixture를 사용했다. Chat JAR로 방 생성201·목록200·POST SSE200·첫 조각 전송·최종 GET 일치를 확인했다. 이 별도 실행에서는 V1~V5 DDL을 직접 적용했으며 Flyway 적용 이력 자체의 검증은 기존 통합 테스트에서 수행했다. 임시 컨테이너·네트워크는 삭제했다.

## 최신 dev 통합 검증

- 기준: `dev`의 `44e1d24`에 원본 PR #13 병합 커밋 `5269b2c`를 통합했다. 기존 YouTube 메타데이터 등록과 Docker 설정을 유지했으며 새 DB 마이그레이션은 없다.
- 충돌은 `AGENTS.md` 문서 링크 한 곳이었다. 채팅 계약·코드 읽기 링크와 Docker 안내를 모두 유지했다. Java 코드는 원본 PR #13과 동일하게 통합됐다.
- Java 21 `./gradlew build`: **283개**, 실패·오류·건너뜀 0개. 위의 258개는 원본 PR #13 시점의 기록이다.
- `ChatHttpStreamingTest`는 실제 HTTP에서 첫 delta 수신 시 DB가 `GENERATING`이고 부분 본문이 저장됐는지 확인한 뒤 upstream 완료를 허용했다. 중복 접수·출처·삭제/완료·lease 경합·부분 저장 실패 테스트도 통과했다.
- 통합 JAR의 Compose 실행: API Flyway V1~V5, Chat 목록 200, Python 주소 미설정 시 POST SSE 경로의 JSON 503과 미접수(메시지 0행), Worker 포트 비공개, 읽기 전용 GCS 더미 파일을 확인했다. 검증용 리소스만 정리했다.
- 프론트엔드 전달·실행 문서를 현재 POST SSE 지원 범위로 맞췄다. 실제 Python·GPT·RAG·모바일 연결은 아직 검증하지 않았으며 이슈 #7의 실제 시연 완료와 구분한다.

이슈 #7의 실제 Python 답변 시연 조건은 아직 충족하지 않았다. endpoint·내부 JSON/SSE 형식·인증을 AI 파트와 맞춘 뒤 실제 서버에서 추가 확인해야 한다. 내부 계약은 `docs/python-chat-contract.md`의 초안이다.
