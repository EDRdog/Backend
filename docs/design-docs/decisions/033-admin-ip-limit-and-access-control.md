# 033. 관리 API 의 접근통제는 인터셉터 한 곳에서 보고, 허용 IP 는 조직별 단일 IP 목록으로 둔다

- 날짜: 2026-11-11
- 상태: 채택

## 배경
KISA EDR 성능평가 항목 BF-07(관리용 단말 IP 제한), BF-20(식별·인증 없는 URL 접근 통제, 권한별 접근통제), BF-19(입력값 검증)의 서버 쪽이다. 국정원 대조(`docs/security/nis-requirements-review.md`)에서 서버 2.3.1(IP 제한)이 미충족이었다.

조사 때 코드는 이랬다.
- 관리 API 매핑 40여 개가 컨트롤러마다 `auth.resolve(bearerToken(...))` 를 따로 불렀다(`bearerToken` 헬퍼도 열여섯 곳에 복제돼 있다). 역할 확인은 감사기록과 자체시험 두 곳에만 있었다.
- 가입자는 전부 `admin` 이고 다른 역할을 만드는 길이 없었다.
- 인증 필터는 없고 `ApiKeyFilter` 의 예외 접두어 목록이 "열린 경로" 를 정했다. 컨트롤러가 인증을 빠뜨려도 알아챌 장치가 없었다.
- 입력 검증은 컨트롤러마다 달랐다. 문자열 길이 상한이 없어 긴 값이 DB 칸을 넘으면 500 이나, 중복 처리 코드에 걸려 409 로 둔갑했다.

## 결정

### 1. 접근통제 관문(`AccessInterceptor`)
`@RequiresRole(Role.ADMIN | Role.VIEWER)` 가 붙은 핸들러는 컨트롤러 앞에서 한 곳이 순서대로 본다.
1. 로그인(없거나 무효하면 401, `AuthService.resolve`)
2. 조직의 허용 IP(목록 밖이면 403 과 감사기록 `admin_ip_block`)
3. 역할(모자라면 403 과 감사기록 `access_denied`)

통과하면 `Principal` 을 요청 속성에 싣는다. 새 컨트롤러는 `@RequiresRole(...)` 한 줄로 셋을 다 얻고, 새 매핑이 이 표시도 없고 열린 경로 목록에도 없으면 `UrlAccessTest` 가 실패한다(BF-20.1).

기존 컨트롤러의 `auth.resolve(...)` 호출은 그대로 뒀다. 관문과 겹쳐 조회가 한 번 더 나가지만(세션 1건, 유저 1건 PK 조회), 컨트롤러 열여섯 곳을 한꺼번에 바꾸면 이번 변경이 커진다. 새로 만든 컨트롤러와 감사기록·자체시험 컨트롤러는 요청 속성에서 읽는다.

### 2. 관리 IP 제한(BF-07.1)
- 조직(tenant)별 허용 IP 목록, MySQL `admin_ips`(JPA 엔티티 `AdminIp`, `unique(tenant_id, ip)`). **한 건도 없으면 제한이 없다.** 있으면 그 조직 계정의 로그인과 모든 관리 API(세션 Bearer)를 목록 밖 IP 에서 403 으로 막는다.
- **단일 주소만 등록한다.** 범위, CIDR, 와일드카드, 이름은 400(KISA 가 단일 IP 별 등록, 범위 지정 불가를 요구한다). Guava `InetAddresses` 로 글자 그대로의 IPv4/IPv6 만 읽는다(DNS 조회를 하지 않는다). IPv6 는 소문자 압축형, IPv4 매핑 주소(`::ffff:1.2.3.4`)는 IPv4 로 맞춰 저장하고 접속 IP 도 같은 식으로 맞춰 비교한다(Tomcat 이 루프백을 `0:0:0:0:0:0:0:1` 로 적는다).
- **로그인은 비밀번호를 보기 전에 막는다.** 이메일로 조직을 찾아(`AuthService.principalOfEmail`) 목록 밖이면 403 으로 끝내고 세션을 만들지 않는다. 비밀번호를 맞춰 볼 수 없으니 밖에서 하는 대입이 막히고, 밖에서 틀린 시도를 쌓아 계정을 잠그는 일(인증 묶음의 연속 실패 잠금)도 못 한다. 대가로 이 이메일이 목록을 쓰는 조직 소속이라는 사실이 403 과 401 의 차이로 드러나는데, 가입 API 가 이미 이메일 중복을 409 로 알려 주므로 새로 드러나는 것이 없다.
- **자기 잠금 방지.** 비어 있던 목록의 첫 IP 가 요청자 본인 IP 가 아니면 409, 다른 IP 가 남아 있는 동안 본인 IP 를 빼면 409. 마지막 하나를 빼면 목록이 비어 제한이 풀린다.
- **접속 IP 는 `request.getRemoteAddr()` 다. X-Forwarded-For 는 읽지 않는다.** 헤더는 단말이 마음대로 써서 보내므로 믿는 순간 허용 IP 를 흉내 낼 수 있다.
- 에이전트(collector)와 서비스 간 내부 API(`/api/internal/**`, X-Internal-Key)는 대상이 아니다. 관리 단말이 아니고 세션이 없다.
- API: `GET /api/tenant/admin-ips`(목록과 `yourIp`), `POST /api/tenant/admin-ips`(`{"ip":"..."}`, 201), `DELETE /api/tenant/admin-ips/{id}`(204). admin 만. 변경은 `admin_ip_change` 로 남는다(상세 `add`/`remove`, 대상 IP).

### 3. 역할과 권한 표(BF-20.2)
- 역할은 `admin`(가입자 전원), `viewer`(읽기 전용). `Role` 이 순서를 가지고(`ADMIN` 이 `VIEWER` 를 포함) 모르는 값은 아무 것도 못 한다.
- viewer 는 admin 이 `POST /api/tenant/users`(`{"email","password"}`)로 같은 조직에 만든다. 역할은 요청으로 못 정하고 항상 viewer 다(본문의 `role` 은 무시). 가입자는 전부 admin 이라 viewer 를 만드는 길은 이것뿐이다. 계정 목록, 삭제, 비밀번호 재설정은 만들지 않았다(`docs/exec-plans/tech-debt.md`).
- 권한 거부는 403 이고 감사기록 `access_denied` 를 남긴다(누가 어느 요청을 필요한 역할 없이 시도했는지). 권한 없는 접근 시도는 감사에서 보고 싶은 사건이다.

| 컨트롤러 | 엔드포인트 | 필요한 역할 |
|---|---|---|
| auth | `POST /api/auth/signup`, `POST /api/auth/login`, `POST /api/auth/logout` | 없음(열림). 로그인은 안에서 허용 IP 를 본다 |
| auth | `GET /api/auth/me` | viewer |
| alert | `GET /api/alerts`, `/summary`, `/timeseries`, `/{id}`, `/{id}/lineage`, `/rules` | viewer |
| alert | `PATCH /api/alerts/{id}`(트리아지), `POST /api/alerts/{id}/respond`(kill), `GET /api/alerts/{id}/respond/{executionId}`(kill 결과, 조회가 알림을 confirmed 로 바꾸는 부작용이 있다) | admin |
| incident | `GET /api/incidents`, `/{id}`, `/{id}/timeline` | viewer |
| incident | `PATCH /api/incidents/{id}/status` | admin |
| event, host, search, intelligence, operations | `GET /api/events/**`, `/api/hosts/**`, `/api/search`, `/api/intelligence/**`, `GET /api/operations/health` | viewer |
| operations/selftest | `GET`, `POST /api/operations/selftest` | admin |
| audit | `GET /api/audit` | admin |
| tenant | `GET`, `PUT /api/tenant/webhook`, `GET`, `POST /api/tenant/enroll-secret`, `POST /api/tenant/install-link` | admin |
| access | `GET`, `POST`, `DELETE /api/tenant/admin-ips`, `POST /api/tenant/users` | admin |
| notify | `GET /api/me/webhook`, `GET /api/me/hosts` | viewer |
| notify | `PUT /api/me/webhook`, `POST /api/me/webhook/test`, `POST /api/me/hosts`, `DELETE /api/me/hosts/{host}` | admin |
| internal | `/api/internal/**` | 세션 없음. X-Internal-Key |
| demo | `POST /api/demo/collect/{scenario}` | 없음(열림). 데모 조직과 시나리오 4종으로 좁혀 있다 |
| install | `GET /i/{token}`, `/i/{token}.ps1` | 없음(열림). 링크의 설치 토큰이 인증이다 |

설정을 읽는 것도 admin 이다. webhook URL 과 enroll secret 은 읽는 것만으로 알림 채널을 가로채거나 에이전트를 조직에 등록할 수 있다. 개인 알림 설정(`/api/me/*`)의 쓰기까지 admin 으로 둔 것은 "viewer 는 조회만" 을 그대로 따른 것이다. viewer 가 자기 알림을 받아야 하면 여기를 풀면 된다.

### 4. 인증 없이 열린 경로(BF-20.1)
`UrlAccessTest` 가 스프링의 모든 매핑을 훑어, `@RequiresRole` 도 없고(세션), `/api/internal/` 도 아니고(내부 키), Swagger Basic 도 아니고, `ApiKeyPolicy` 의 예외 목록에도 없어서 X-API-Key 도 요구하지 않는 경우를 "열린 매핑" 으로 센다. 그 집합이 위 표의 열린 것(가입, 로그인, 로그아웃, 데모, 설치 스크립트 둘)과 정확히 같아야 한다. 늘리려면 이 문서와 테스트의 목록을 함께 고친다. 로그아웃은 자기 토큰만 지우는 멱등 호출이라 무효 토큰도 204 로 열어 둔다.

### 5. 입력값 검증(BF-19.1)
- **쿼리 파라미터와 경로 변수**는 `InputGuardInterceptor` 가 컨트롤러 앞에서 한꺼번에 본다. 값이 256자를 넘거나 제어 문자(NUL, 줄바꿈, 0x7f)가 있으면 400. `from`, `to` 는 음수이거나 from 이 to 보다 크면 400. 새 API 도 자동으로 걸린다. 값은 응답에 되돌려 주지 않는다.
- **요청 본문 값**은 쓰는 자리에서 `InputRules` 로 본다. 가입 이메일(`AuthValidation.validEmail` 을 영문·숫자와 `._%+-` 단일 주소, 254자 이하로 조였다), 비밀번호(UTF-8 72바이트 이하, BCrypt 가 그 뒤를 조용히 자르므로), 조직 이름 100자, 로그인(길이와 문자만, 형식은 안 본다. 옛 데모 계정 이메일이 `test` 라서), webhook URL(https, 호스트 있음, 255자 이하, URI 로 읽힘), host 255자.
- **깨진 JSON** 은 파서 문장 대신 고정 문구의 400(`ApiExceptionHandler`). 500 은 스프링 기본 오류 본문이고 스택트레이스와 예외 메시지가 실리지 않는 것을 `ErrorBodyIntegrationTest` 가 실제 HTTP 로 확인한다.
- 이미 있던 검증(offset 상한, limit 클램프, status·bucket 허용 목록, 검색어 길이, 감사기록 파라미터)은 그대로 뒀다. limit 은 범위 밖이면 자르는 것이 기존 문서화된 동작이라 400 으로 바꾸지 않았다.
- 기록 칸 길이를 넘기던 값(host, webhook URL, 이메일, 조직 이름)이 이제 칸 앞에서 400 이 된다. 전에는 저장 오류가 500 이나 409 로 나갔다.

## 근거

**인터셉터를 고른 이유**: 로그인, IP, 역할이 서로 이어진 한 줄(401, 403, 403)이라 한곳에서 순서대로 봐야 감사기록과 응답 코드가 일관된다. 컨트롤러마다 부르는 방식은 이미 열여섯 곳에서 복제돼 있고 새 컨트롤러가 빠뜨려도 알 수 없었다. `@RequiresRole` 하나가 "이 핸들러는 세션 인증이다" 의 표시가 되므로 열린 경로 목록 테스트가 가능해졌다.

**버린 대안**
- Spring Security 도입: 이 서비스는 필터체인 없이 BCrypt 만 쓰도록 일부러 구성돼 있다(`AuthConfig`). 세션 저장과 토큰 발급이 직접 만든 것이라 연결 비용이 크고, 이번 범위(인증 묶음이 따로 돈다)를 넘는다.
- 서블릿 필터로 구현: 핸들러 메서드의 어노테이션을 읽으려면 핸들러 매핑이 필요하다. 인터셉터는 그 정보를 그대로 받고, 던진 예외가 기존 `@RestControllerAdvice` 로 처리돼 본문 모양이 같다.
- 허용 IP 를 CIDR 로: KISA 가 범위 지정을 불가로 못박았다.
- X-Forwarded-For 신뢰: 위 이유로 코드에서는 읽지 않는다. 프록시 뒤 배포는 아래 "배포 때" 의 설정으로 푼다.
- 로그인 IP 검사를 비밀번호 확인 뒤에: 밖에서 비밀번호 대입과 계정 잠금 유도가 가능해진다.
- 권한 거부를 기록하지 않음: 감사에서 권한 없는 시도는 보고 싶은 사건이다. 대신 계정 하나가 403 을 반복하면 행이 그만큼 쌓인다(인증된 계정만 만들 수 있다).

## 결과/영향
- 새 테이블 `admin_ips`(JPA 가 만든다). 새 감사 유형 `admin_ip_change`, `access_denied`, `account_create`(`admin_ip_block` 은 ADR 028 이 상수로 두었던 것을 이 묶음이 쓴다).
- 감사기록·자체시험 컨트롤러의 인라인 admin 확인을 걷고 관문에 맡겼다(상태코드는 같다).
- **배포 때 사람이 해야 할 일**: 운영은 Caddy 가 `localhost:30084` 로 프록시한다(`k8s/api-service.yaml`). 이 상태에서 `getRemoteAddr()` 는 단말 IP 가 아니라 프록시 쪽 주소다. 허용 IP 를 실제로 쓰려면 api-service 에 `server.forward-headers-strategy=native`(환경변수 `SERVER_FORWARDHEADERSSTRATEGY=native`)를 주입해야 한다. Tomcat 이 직전 연결(프록시)이 내부망 주소일 때만 X-Forwarded-For 를 해석해 `getRemoteAddr()` 를 그 값으로 바꾼다. 코드에서 헤더를 읽는 것과 달리 신뢰하는 프록시를 이쪽이 정한다. 주입 뒤 단말에서 `GET /api/tenant/admin-ips` 의 `yourIp` 가 공인 IP 와 같은지 본다(SEC-89). 주입하지 않으면 모든 요청이 같은 주소로 보여, 허용 IP 를 등록하려 해도 본인 IP 가 달라 첫 등록이 409 로 막힌다(잠기지는 않는다).
- 한계: viewer 계정 목록, 삭제, 비밀번호 재설정이 없다. 요청마다 세션 조회가 한 번 겹친다. 이 두 가지는 `docs/exec-plans/tech-debt.md`.

## 관련
- `docs/design-docs/decisions/028-audit-log-in-api-service.md`(감사 유형과 기록 방식), `029-server-selftest-and-integrity.md`(admin 전용 자체시험)
- `docs/requirements/security.md` SEC-70 ~ SEC-89
- `docs/security/kisa-edr-performance-criteria.md` BF-07, BF-19, BF-20
