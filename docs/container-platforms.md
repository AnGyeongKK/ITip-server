# Mac·Windows 공통 Docker 배포 규칙

## CPU와 운영체제를 따로 확인

| 실행 기기 | CPU 아키텍처 | 이번 프로젝트의 컨테이너 대상 |
| --- | --- | --- |
| Apple Silicon Mac (M 계열) | ARM64 | `linux/arm64` |
| Intel Mac | x86-64 | `linux/amd64` |
| Intel·AMD CPU Windows PC | x86-64 | `linux/amd64` |
| ARM CPU Windows PC | ARM64 | `linux/arm64` (Docker Desktop 지원 환경 확인) |

`amd64`는 AMD 제조사 전용이 아니라 Intel도 사용하는 x86-64 아키텍처 이름이다. Mac이라고 모두 ARM, Windows라고 모두 amd64는 아니다. Docker Desktop은 Mac·Windows에서도 Linux VM을 통해 **Linux 컨테이너**를 실행한다. Windows 담당자는 Docker Desktop의 WSL 2/Linux containers 환경을 사용한다.

```sh
# Docker 엔진의 OS/CPU 확인. linux/aarch64 또는 linux/arm64는 ARM64.
docker info --format '{{.OSType}}/{{.Architecture}}'
# 로컬 이미지 확인
docker image inspect itip-api:local --format '{{.Os}}/{{.Architecture}}'
```

## 팀 공유 이미지는 두 플랫폼으로 빌드

로컬에서 혼자 실행할 때는 `docker build`로 현재 CPU용 이미지를 만들어도 된다. **다른 CPU의 팀원에게 전달하는 이미지는 아래 방식으로 빌드·push한다.** Compose 파일과 이미지 이름만 공유해도 Docker가 노트북에 맞는 이미지를 받도록 하는 목적이다.

아래는 macOS 또는 Windows WSL의 **Bash** 명령이다. builder 생성은 최초 한 번만 하고, 이미 있으면 inspect부터 실행한다. 기존 기본 builder를 바꾸지 않도록 각 빌드에서 이름을 지정한다.

```sh
docker buildx create --name itip-multiarch --driver docker-container
docker buildx inspect itip-multiarch --bootstrap
docker login
```

프론트엔드는 [APK 전달 문서](frontend-demo-handoff.md)의 빌드 명령에 `--builder itip-multiarch`를 사용한다. 백엔드는 Java 21로 JAR를 준비한 뒤 `ITip/`에서 실행한다. 계정·태그는 실제 배포 대상을 지정한다. 로그인 비밀번호나 토큰을 명령줄에 넣지 않는다.

```sh
./gradlew :apps:api-app:bootJar :apps:worker-app:bootJar :apps:chat-app:bootJar
export DOCKERHUB_NAMESPACE=YOUR_DOCKERHUB_ID
export DEMO_IMAGE_TAG=demo-001
for app in api worker chat; do
  docker buildx build --builder itip-multiarch \
    --platform linux/amd64,linux/arm64 \
    --file "apps/${app}-app/Dockerfile" \
    --tag "${DOCKERHUB_NAMESPACE}/itip-${app}:${DEMO_IMAGE_TAG}" \
    --push .
done
```

`--platform`은 **결과 이미지의 플랫폼**이다. 파일명에 arm64를 붙이거나 단일 플랫폼 이미지를 retag하는 것으로 다른 CPU용 이미지가 만들어지지 않는다. `--push`는 두 변형을 같은 버전 태그의 manifest로 올린다. 이 경로는 로컬 이미지 저장소의 멀티 플랫폼 지원 여부에 의존하지 않으며, `--load`와 혼용하지 않는다. 로컬 단일 CPU 빌드는 `--platform linux/arm64 --load`처럼 하나만 지정할 수 있다.

Dockerfile의 `FROM`에 `--platform=linux/amd64`를 고정하지 않는다. 현재 Java·Nginx Dockerfile은 대상 플랫폼의 베이스 이미지를 선택한다. 베이스 이미지·pgvector도 실제 사용할 **태그가 두 플랫폼을 제공하는지** 확인한다. 호스트에서 만든 `node_modules`, Python venv, `.so`·실행 바이너리를 이미지에 그대로 복사하지 않는다. Java JAR도 JNI·특정 CPU 바이너리 의존성을 추가하면 두 환경에서 별도 확인한다.

## 받는 사람: 태그 확인 → pull → 실행

```sh
# 공유받은 태그로 변경. 출력에 linux/amd64와 linux/arm64가 모두 있어야 한다.
docker buildx imagetools inspect YOUR_DOCKERHUB_ID/itip-mobile-apk:demo-001

# Bash: 백엔드 이미지 태그 설정 예시. DB_PASSWORD는 각자 로컬에서 준비.
export API_IMAGE=YOUR_DOCKERHUB_ID/itip-api:demo-001
export WORKER_IMAGE=YOUR_DOCKERHUB_ID/itip-worker:demo-001
export CHAT_IMAGE=YOUR_DOCKERHUB_ID/itip-chat:demo-001
docker compose -f compose.yaml -f compose.demo.yaml pull
docker compose -f compose.yaml -f compose.demo.yaml up -d
```

각 이미지 태그의 manifest를 확인한다. `compose.yaml`, `compose.demo.yaml`, `compose.mobile.yaml`은 의도적으로 `platform:`을 고정하지 않는다. Docker가 엔진에 맞는 변형을 선택한다. `DOCKER_DEFAULT_PLATFORM`을 개인 shell에서 강제 설정했다면 의도한 값인지 확인한다. 플랫폼이 바뀌었다고 DB volume을 삭제하지 않는다.

| 증상 | 확인·대응 |
| --- | --- |
| `no matching manifest for linux/arm64` 등 | 공유 이미지의 해당 플랫폼이 없음. 두 플랫폼으로 새 버전 빌드·배포 |
| `exec format error` | 이미지·내부 실행 파일의 CPU 불일치 가능. manifest와 COPY한 바이너리 확인 |
| amd64 이미지가 ARM Mac에서 느림 | 에뮬레이션 가능성 확인. native arm64 변형 사용. 강제 `platform: linux/amd64`는 임시 대응이며 호환성 검증을 대신하지 않음 |
| `/bin/sh^M`, `bad interpreter` | CPU 외에 CRLF 줄바꿈 확인. Linux에서 실행할 파일은 LF 유지 |

ARM Mac에서 amd64 이미지를 에뮬레이션으로 실행해도 실제 Windows/x86 노트북 검증과는 다르다. 발표 전에는 두 환경에서 `ps`, API 응답, 실제 APK 설치·네이티브 수집을 각각 확인하고 확인한 환경을 기록한다. 이미지가 빌드됐다는 사실만으로 전체 기능 호환을 보장하지 않는다.

## Windows와 Android에서 따로 주의할 점

- 문서의 `export`, `./gradlew`, `\` 줄연결은 Bash 기준이다. Windows에서는 WSL을 사용하거나 PowerShell에 맞게 바꾼다. PowerShell의 환경변수는 `$env:MOBILE_APK_IMAGE = '계정/itip-mobile-apk:demo-001'`, Gradle은 `./gradlew.bat`다. Docker 명령 자체는 한 줄로 실행할 수 있다.
- GCS 마운트 overlay의 `id -u`, `id -g`는 macOS/WSL 기준이다. WSL에서 사용하면 인증 파일도 본인 WSL 경로와 권한에 맞춘다. Windows PowerShell에 Bash 명령을 그대로 붙여넣지 않는다. 인증 파일은 이미지로 전달하지 않는다.
- Windows USB 실기기의 `adb`는 기기가 연결된 **Windows 호스트**에서 실행한다. WSL에 USB 연결이 자동 공유된다고 가정하지 않는다. 포트 reverse 대상은 노트북의 API 8080·Chat 8082다.
- 컨테이너 `linux/arm64`와 APK `arm64-v8a`는 다른 실행 대상이다. Docker 멀티 플랫폼 빌드를 해도 APK의 네이티브 라이브러리 ABI는 추가되지 않는다. 실제 Android 기기는 `adb shell getprop ro.product.cpu.abilist`로 확인한다. APK의 라이브러리는 Android Studio의 Analyze APK에서 확인한다. Intel/AMD PC의 에뮬레이터는 보통 x86_64, Apple Silicon의 에뮬레이터는 보통 arm64-v8a이므로 사용하는 AVD에서 확인한다.
- yt-dlp 연동 실행 파일·라이브러리도 Android ABI에 맞아야 한다. Mac ARM64용 바이너리는 Android ARM64용 바이너리와 OS·ABI가 달라 그대로 재사용할 수 없다.

## 근거

- [Docker 멀티 플랫폼 빌드·manifest·에뮬레이션](https://docs.docker.com/build/building/multi-platform/)
- [Docker Desktop Windows 설치·WSL 2](https://docs.docker.com/desktop/setup/install/windows-install/)
- [Compose platform 설정](https://docs.docker.com/reference/compose-file/services/#platform)
- [Android ABI](https://developer.android.com/ndk/guides/abis)
