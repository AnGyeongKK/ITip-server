# React Native 시연 앱 전달 방법

## 무엇을 전달하나요?

**Android 앱은 APK로 전달하고, 노트북에서는 백엔드를 Docker Compose로 실행한다.** React Native의 네이티브 화면·yt-dlp 모듈은 Android 기기나 에뮬레이터에서 실행한다. Docker Hub를 사용하고 싶으면 APK를 내려받는 작은 서버 이미지를 배포한다.

```text
프론트엔드: React Native + Android 네이티브 모듈 → 서명된 APK 빌드
                                                ↓ APK 또는 Docker Hub 이미지 전달
시연 노트북: Docker Compose → PostgreSQL / API :8080 / Worker / Chat :8082
                                                ↑ USB + adb reverse
Android 기기: APK 설치 → 앱 실행 → API·Chat 호출 / YouTube 수집 / GCS 직접 업로드
```

추천은 **APK 직접 전달**이다. Docker 방식도 APK 빌드·설치 단계는 필요하며, 컨테이너가 Android 화면을 실행하는 것은 아니다. 이 저장소에는 프론트엔드 소스가 없어 아래 파일은 팀원이 적용할 템플릿이다.

## 1. 프론트엔드에서 APK 만들기

먼저 [Mac·Windows 공통 배포 규칙](container-platforms.md)을 확인한다. **다운로드 서버 이미지는 arm64·amd64를 모두 제공하고, APK는 실제 기기·에뮬레이터의 Android ABI를 지원해야 한다.** 아래 명령은 macOS/WSL Bash 기준이다. Windows의 빌드·USB 연결 차이도 해당 문서에 적었다.

1. 앱의 API 주소를 `http://127.0.0.1:8080`, Chat 주소를 `http://127.0.0.1:8082`로 설정한다. 이 문서는 **USB + adb reverse** 기준이다. 주소 설정 방식은 프론트엔드 프로젝트에 맞게 구현한다.
2. Android 시연 빌드에서 위 로컬 HTTP 통신을 허용한다. `INTERNET` 권한과 Network Security Config를 확인하고, HTTP 허용 설정은 시연용 variant에 한정한다. 정식 배포는 HTTPS로 연결한다.
3. JS 번들이 포함되어 **Metro를 끈 상태에서도 켜지는 APK**를 만든다. release 서명 설정 후, React Native CLI의 일반적인 명령은 다음과 같다.

```sh
# 프론트엔드 저장소에서 실행. package-lock.json을 쓰는 프로젝트 예시.
npm ci
cd android
./gradlew :app:assembleRelease
```

일반적인 결과는 `android/app/build/outputs/apk/release/app-release.apk`다. flavor가 있으면 task·경로가 달라진다. `.aab`는 이 문서의 `adb install` 입력이 아니다. Android 빌드 JDK는 프론트엔드의 Gradle·Android Gradle Plugin 요구사항에 맞춘다. 백엔드 Java 21을 그대로 강제하지 않는다.

- **서명된 APK**를 전달하고 실제 기기에 한 번 설치해 확인한다. 서명키·비밀번호는 작성자 로컬에만 둔다. 업데이트에는 같은 서명키와 증가한 versionCode를 사용한다.
- 실제 폰용 `arm64-v8a`를 포함한다. 에뮬레이터 시연 시 해당 AVD의 ABI도 지원해야 한다. yt-dlp 연동 네이티브 라이브러리도 확인한다.
- Expo 프로젝트라도 사용자 네이티브 수집 모듈이 들어간 자체 APK가 필요하다. Expo Go만 전달해서는 이 모듈을 실행할 수 없다.
- 앱에 서버용 GCS 인증 파일·GPT API 키·내부 AI 토큰을 넣지 않는다. API 주소는 비밀값이 아니지만 **APK 내부 설정은 추출 가능**하다.

## 2. Docker Hub로 전달하고 싶을 때

이미지를 직접 보내는 대신 Docker Hub에 올리고 이름·버전만 전달한다. Docker Desktop의 Images는 로컬 이미지 목록이고, Docker Hub는 팀원이 받을 수 있는 이미지 저장소다.

[`examples/mobile-apk/`](examples/mobile-apk/)의 `Dockerfile`, `.dockerignore`, `default.conf`, `index.html`을 프론트엔드 저장소의 `delivery/`에 복사한다. 빌드된 APK만 `delivery/itip-demo.apk`로 복사한다.

```text
프론트엔드 저장소/
├── android/...
└── delivery/
    ├── Dockerfile
    ├── .dockerignore
    ├── default.conf
    ├── index.html
    └── itip-demo.apk        # 빌드 산출물. Git에는 추가하지 않는다.
```

```sh
# 프론트엔드 저장소 루트. 아래 namespace는 본인 Docker Hub 계정/조직으로 변경.
cp android/app/build/outputs/apk/release/app-release.apk delivery/itip-demo.apk
docker login
# 공통 배포 규칙에 따라 itip-multiarch builder를 최초 한 번 준비한 상태
docker buildx build --builder itip-multiarch --platform linux/arm64,linux/amd64 \
  --file delivery/Dockerfile \
  --tag YOUR_DOCKERHUB_ID/itip-mobile-apk:demo-001 \
  --push delivery
```

`--push`가 빌드와 Docker Hub 업로드를 수행한다. Apple Silicon Mac과 Intel/AMD 노트북을 위해 두 Linux 아키텍처를 지정했다. 이것은 **다운로드 서버의 CPU 종류**이며 APK의 Android ABI와 별개다. Compose에 `platform: linux/amd64`를 고정하지 않는다. 전달 전에 다음 명령으로 두 플랫폼이 포함됐는지 확인한다.

```sh
docker buildx imagetools inspect YOUR_DOCKERHUB_ID/itip-mobile-apk:demo-001
```

- 새 APK는 `demo-002`처럼 **새 버전 태그**로 올린다. 시연자가 받은 태그를 나중에 덮어쓰지 않는다.
- 이미지에는 APK·다운로드 페이지·Nginx만 담긴다. `.dockerignore`가 이 파일만 허용한다. 서명키·소스·`.env` 파일을 COPY하거나 build-arg로 비밀값을 넘기지 않는다.
- 프론트엔드의 `.gitignore`에 `delivery/itip-demo.apk`를 추가한다. APK·이미지를 공개할지는 팀 공개 범위에 맞춘다. private 이미지면 받는 사람도 자신의 Docker Hub 권한으로 `docker login`한다.

## 3. 시연 노트북에서 받기·설치하기

백엔드 저장소의 `ITip/`에서 실행한다. Docker Hub를 통한 전달을 선택했을 때만 앞의 세 줄이 필요하다.

```sh
export MOBILE_APK_IMAGE=YOUR_DOCKERHUB_ID/itip-mobile-apk:demo-001
docker compose -f compose.mobile.yaml pull
docker compose -f compose.mobile.yaml up -d
curl --fail --output /tmp/itip-demo.apk http://127.0.0.1:8090/itip-demo.apk

adb devices
adb install -r /tmp/itip-demo.apk
adb reverse tcp:8080 tcp:8080
adb reverse tcp:8082 tcp:8082
```

APK를 직접 받았다면 `adb install -r <받은 APK 경로>`부터 실행한다. Android SDK Platform-Tools의 `adb` 설치와 기기의 USB 디버깅 승인이 필요하다. 여러 기기가 연결되면 각 명령에 `adb -s <기기 ID>`를 사용한다. 케이블 재연결 시 reverse 설정을 다시 확인한다.

Docker를 켠 것만으로 앱이 설치되지는 않는다. 설치 후 Android에서 앱을 직접 실행하고 노트북 화면 공유는 Android Studio Device Mirroring 등을 사용한다. 실기기 테스트를 우선한다.

| 연결 방식 | 앱에서 호출할 서버 주소 |
| --- | --- |
| USB + adb reverse (위 명령) | `http://127.0.0.1:8080`, `http://127.0.0.1:8082` |
| Android Studio 에뮬레이터, reverse 없이 | `http://10.0.2.2:8080`, `http://10.0.2.2:8082`로 빌드·설정 변경 |
| 실기기 Wi-Fi | 노트북 LAN IP와 방화벽·바인딩 설정 필요. 현재 Compose는 로컬에만 포트를 열므로 위 USB 방식 사용 |

앱은 Docker 서비스명 `api`, `chat`, `db`를 사용할 수 없다. APK의 서버 주소는 Compose 환경변수를 바꿔도 바뀌지 않는다. 프론트엔드가 별도 런타임 설정 기능을 만들지 않았다면 주소 변경 후 APK·이미지를 다시 빌드한다.

## 4. 백엔드 실행과 시연 확인

백엔드 담당자는 [로컬 실행 문서의 Compose 실행](local-run.md#compose로-서버-3개-함께-실행)을 따른다. 이미지를 준비하고 로컬 `DB_PASSWORD`를 설정한 뒤 실행한다.

```sh
# ITip/에서 실행. DB → API(Flyway 완료·포트 준비) → Worker·Chat 순서.
docker compose -f compose.yaml -f compose.demo.yaml up -d
docker compose -f compose.yaml -f compose.demo.yaml ps
```

API·Chat은 호스트 8080·8082에 노출되고 Worker는 내부에서 DB 작업을 조회한다. Metro 기본 8081과 충돌하지 않는다. 현재 `dev`의 Chat은 채팅방·기록 관리까지 지원하며, Python 질문 생성·SSE 연결은 후속 기능이다. 연결할 때는 Docker Desktop의 호스트 주소 `host.docker.internal`을 사용하고 Python이 컨테이너에서 접근 가능한 주소로 리슨하게 한다. 내부 API 형식·주소·인증은 AI 담당과 맞춘다. [채팅 계약](chat-contract.md)을 참고한다.

| 시연 전 확인 | 기대 동작 |
| --- | --- |
| Metro 종료 후 앱 실행 | JS 로딩 서버 없이 화면 표시 |
| API·Chat 요청 | USB reverse 적용 후 각 주소로 응답 수신 |
| 네이티브 수집 | 기기에서 YouTube 요청, 동시 수집 1개·중복 방지·제한 시 중단·재시도 제한 |
| 자막 업로드 | 서버에서 받은 presigned URL에 지정한 method·Content-Type으로 GCS 직접 업로드 후 API 접수 |
| 분석 상태·결과 | 상태 조회로 완료 표시. 자막 없음·지원하지 않는 자막 언어·60분 초과 등 오류 표시 |
| 채팅 | 현재는 방·기록 조회 확인. 질문 생성 기능 병합 후 POST SSE의 delta와 done/error 처리 확인 |

**Compose 기동과 실제 분석·채팅 시연 성공은 별개다.** 현재 Worker에는 모델 Bean 연결, GCS에는 각자의 인증 설정이 필요하며, 모델 미설정 시 작업은 `AI_NOT_CONFIGURED`로 실패한다. Chat에는 질문 생성 기능 병합과 실제 Python AI 서버 연결이 필요하다. APK 배포 이미지를 추가해도 이 연결은 생성되지 않는다.

팀원은 **이미지 태그(또는 APK), 이미지 지원 플랫폼·실행 확인한 노트북 CPU, 소스 커밋, 앱 versionName/versionCode, Android 최소 버전·ABI, API·Chat 주소, Metro 없이 실행 확인 결과**만 전달한다. `.env`·서명키·API 키·인증 파일은 전달 대상이 아니다.

## 공식 문서

- [React Native Android 배포·서명](https://reactnative.dev/docs/signed-apk-android)
- [React Native 기기 실행·adb reverse](https://reactnative.dev/docs/running-on-device)
- [Android 에뮬레이터 호스트 주소](https://developer.android.com/studio/run/emulator-networking-address)
- [Docker 멀티 플랫폼 이미지](https://docs.docker.com/build/building/multi-platform/)
- [Compose 시작 순서와 healthcheck](https://docs.docker.com/compose/how-tos/startup-order/)
