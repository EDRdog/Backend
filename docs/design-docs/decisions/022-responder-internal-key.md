# 022. responder 내부 API 는 서블릿 필터 한 곳에서 X-Internal-Key 로 인증한다

- 날짜: 2026-11-10
- 상태: 채택

## 배경
KISA H5. responder 의 `/api/responder/kill`, `/commands` 에 인증이 없어 클러스터 안 파드 하나면 임의 호스트에 kill 명령을 넣을 수 있었다. collector 의 `InternalNodeController`, api-service 의 내부 엔드포인트는 이미 `X-Internal-Key` 를 `INTERNAL_API_KEY`(`edrdog.internal.key`)와 비교한다.

## 결정
- `InternalKeyFilter`(`OncePerRequestFilter`) 하나가 `/api/responder/**` 전체를 검사한다. 헤더가 없거나 다르면 컨트롤러에 닿기 전에 401 이다. `/actuator` 는 건드리지 않아 프로브는 열려 있다.
- 비교는 `MessageDigest.isEqual` 이다(상수 시간).
- 키는 다른 서비스와 같은 설정 `edrdog.internal.key` = `${INTERNAL_API_KEY:dev-internal-key}` 로 읽는다. 기본값 동작도 맞췄다.
- 호출자인 api-service `ResponderClient`, collector `ResponderClient` 는 `RestClient` 기본 헤더로 키를 싣는다. 각 서비스가 이미 가진 `edrdog.internal.key` 를 쓴다.
- k8s 는 responder 가 이미 `edrdog-secrets` 를 `envFrom` 으로 읽으므로 매니페스트 변경이 없다(주석만 추가). 시크릿 값은 git 에 없다.

## 근거
**필터를 고른 이유**: 컨트롤러 메서드마다 검사를 복사하면 새 엔드포인트가 생길 때 빠뜨리기 쉽고, 빠뜨려도 티가 안 난다. 필터는 경로 접두사 하나로 모든 하위 경로를 덮는다. 매핑 없는 경로도 404 가 아니라 401 이라 경로 존재 여부가 새지 않는다.

**인터셉터를 버린 이유**: 핸들러 매핑 뒤에 돌아 매핑 없는 경로에는 걸리지 않는다. 얻는 것이 없다.

**Spring Security 를 버린 이유**: responder 에 의존성이 없고, 헤더 하나 비교하려고 보안 필터 체인을 들이는 건 과하다.

## 결과/영향
- 배포 순서: 세 서비스 모두 `INTERNAL_API_KEY` 가 같은 값으로 주입돼 있어야 한다. 한쪽만 기본값(`dev-internal-key`)이면 kill 과 명령 전달이 401 로 멈춘다. collector 가 401 을 받으면 하트비트의 명령 조회는 빈 목록으로 삼켜지므로(fail-soft) 에이전트는 멀쩡해 보이고 명령만 안 내려간다.
- 남은 것은 `docs/exec-plans/tech-debt.md` 에 있다: detector `/api/events` 무인증, Kafka 무인증, NetworkPolicy 없음, 기본 키 값.
