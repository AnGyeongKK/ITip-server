# Supabase Google 로그인

```text
모바일 → Supabase Auth → Google 로그인 → Supabase 세션 발급
모바일 → Spring API·Chat (Bearer access_token) → 공개 키로 JWT 검증
Spring → JWT sub의 사용자 UUID로 본인 분석·폴더·채팅·기기 조회
```

## 콘솔 설정

1. Google Cloud에서 웹 애플리케이션 OAuth 클라이언트를 만든다. 승인된 리디렉션 URI에는 Supabase Dashboard의 Google provider 화면에 표시된 callback URL을 등록한다. 기본 형식은 `https://<project-ref>.supabase.co/auth/v1/callback`이다.
2. Supabase Dashboard에서 **Authentication → Sign In / Providers → Google**을 활성화하고 Google Client ID와 Client Secret을 입력한다. Google만 열려면 다른 로그인 provider 및 가입 경로도 Supabase에서 비활성화한다.
3. **Authentication → URL Configuration → Redirect URLs**에 모바일 팀이 확정한 앱 callback 주소를 등록한다. Google 콘솔에 등록한 callback은 Google에서 Supabase로 돌아오는 주소다. 앱 callback은 Supabase에서 앱으로 돌아오는 주소다.
4. Supabase의 JWT Signing Keys에서 **ECC P-256 (ES256)** 또는 **RSA (RS256)** 키를 사용한다. 기존 프로젝트가 HS256으로 서명한다면 먼저 비대칭 키로 전환하고 앱에서 새 세션을 발급받는다.

[Supabase Google 설정](https://supabase.com/docs/guides/auth/social-sign-in/auth-google) · [JWT 서명 키](https://supabase.com/docs/guides/auth/signing-keys)

## 서버 실행

API와 Chat에 다음 환경변수를 동일하게 설정한다.

| 이름 | 값 |
| --- | --- |
| `ITIP_AUTH_MODE` | `supabase` |
| `SUPABASE_URL` | 프로젝트의 기본 URL. 예시 `https://<project-ref>.supabase.co` |

Spring은 `/auth/v1/.well-known/jwks.json`의 공개 키를 사용한다. Google Client Secret은 Supabase에 입력한다. 앱의 Supabase SDK에는 프로젝트 URL과 publishable key를 설정한다.

기존 로컬 DB를 그대로 사용할 수 있다. Spring은 검증된 `sub`를 기존 `owner_id`에 사용한다. 신규 사용자는 빈 기록에서 시작하며, 시연 데이터는 `DEMO_USER_ID`에 남는다. 기존 시연은 로컬에서 `ITIP_AUTH_MODE=demo`와 API·Chat의 동일한 `DEMO_USER_ID`로 실행한다. `compose.demo.yaml`의 기본값은 `demo`이며 외부에 노출하는 환경은 `supabase`를 명시한다.

## 모바일 연동

- Supabase SDK가 Google 로그인, 세션 갱신, 로그아웃을 담당한다. API·Chat의 JSON 요청과 POST SSE 요청 모두 `Authorization: Bearer <access_token>`을 보낸다. SSE 연결에 별도 HTTP 코드를 쓰면 그 요청에도 헤더를 설정한다.
- 알림을 등록한 기기는 로그아웃 전에 기존 세션으로 `DELETE /api/v1/users/me/devices/{installationId}`를 호출한다. 성공 후 앱의 등록 상태를 지우고 Supabase에서 로그아웃한다. 연결 해제에 실패하면 재시도할 수 있게 표시한다. 다음 계정은 로그인 후 같은 기기를 다시 등록한다. 데모에서 로그인 모드로 바꿀 때도 데모 모드에서 먼저 해제한다.
- 진행 중인 분석의 `batchId`와 앱이 보관하는 기록·채팅 캐시는 사용자 UUID별로 분리한다.
- `GET /api/v1/users/me`가 `200`과 `data.userId`에 로그인한 UUID를 반환하면 서버 연동이 된 것이다.
- 토큰이 없거나 유효하지 않으면 SSE 연결을 열기 전에도 같은 `401` JSON을 반환한다. 앱은 세션을 갱신하고, 만료 등으로 세션을 복구할 수 없을 때 로그인 화면을 연다. 공개 키를 가져오지 못하는 서버 장애(`5xx`)에서는 세션을 유지하고 재시도한다.

```json
{
  "code": "AUTHENTICATION_REQUIRED",
  "message": "로그인이 필요해요."
}
```
