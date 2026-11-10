# 035. 공개 데모 계정은 관리 IP, 보안정책, 계정 생성을 바꿀 수 없고, 거절은 접근통제 관문에서 한다

- 날짜: 2026-11-11
- 상태: 채택

## 배경
데모 계정(`test@edrdog.local`, role admin, `demo_account=true`)의 비밀번호는 공개돼 있다. 방문자 누구나 로그인해서 관리 IP 를 등록하면 다른 모든 곳의 데모 로그인이 403 으로 막히고(ADR 033), 보안정책(유휴 시간 등)을 바꾸거나 계정을 만들 수 있다(이슈 #326).

## 결정
- 거절 지점은 `AccessInterceptor`(ADR 033)다. `@RequiresRole` 에 `denyDemo`(기본 false)를 두고, 켠 핸들러는 로그인, 허용 IP, 역할을 통과한 뒤 요청자가 데모 계정이면 403 `{"error": "공개 데모 계정으로는 바꿀 수 없습니다"}` 로 끝낸다. 컨트롤러에는 닿지 않으므로 상태가 바뀌지 않는다.
- 켠 곳은 `POST`, `DELETE /api/tenant/admin-ips`, `PUT /api/auth/security-policy`, `POST /api/tenant/users` 네 매핑이다. 조회(GET)는 그대로 열려 있다. `AdminIpController` 는 클래스 단위 `@RequiresRole(ADMIN)` 이라 쓰기 두 메서드에만 메서드 단위로 다시 붙였다(메서드가 우선).
- 데모 여부는 `AuthService.isDemoAccount(userId)` 로 읽는다(`mustChangePassword` 와 같은 모양, `denyDemo` 핸들러에서만 PK 조회 1건).

## 근거
- 이 레포는 데모 예외를 `AuthService` 안에서 `user.isDemoAccount()` 로 처리해 왔다(패스워드 변경, 중복 접속, 잠금). 이 쓰기들은 `AuthService` 를 지나지 않아 같은 자리에 둘 수 없다.
- 컨트롤러 세 곳에 각각 검사를 넣는 대신, 이미 모든 관리 API 가 지나는 관문에 한 줄을 두고 표시는 `@RequiresRole` 한 속성으로 한다. 새 쓰기 API 가 생기면 속성만 켜면 된다.

## 버린 대안
- 모든 비-GET 을 데모에서 막기: webhook, enroll-secret 같은 다른 쓰기까지 막혀 이슈 범위를 넘는다.
- `Principal` 에 demo 필드 추가: 생성 지점이 여럿이라 변경이 커진다.

## 결과/영향
- 데모 계정의 거절은 감사기록을 남기지 않는다(역할 거부의 `access_denied` 와 달리). 공개 계정이라 방문자 호출마다 기록이 쌓이는 쪽이 더 해롭다고 봤다.
- 데모 계정이 다른 쓰기(webhook 등)로 상태를 바꾸는 것은 이번 범위가 아니다.

## 관련
- ADR 031, 032(데모 계정 예외), ADR 033(접근통제 관문), `docs/requirements/security.md` SEC-100
