# 029. 서버 자체시험과 무결성 검증은 api-service 가 맡고, 기준값은 jar 밖의 sha256sum 파일로 받는다

- 날짜: 2026-11-11
- 상태: 채택

## 배경
KISA EDR 성능평가 항목 BF-10(보안기능 자체시험), BF-11(자체와 설정값 무결성 검증)의 서버 쪽이다. 제품은 구동 중 주기적으로, 그리고 관리자 요청으로 자체시험을 하고, 제품과 설정값의 무결성을 검증하고, 두 결과를 관리자가 볼 수 있어야 한다(10.1, 11.1). 실패 시 대응은 선택이다(10.2, 11.2). 국정원 대조(`docs/security/nis-requirements-review.md`)에서 서버 4.1.1, 4.1.2, 4.2.1~4.2.4 가 미충족이었다.

관리 서버는 api-service 로 본다(관리 화면과 API 가 여기 있다). 다른 서비스(collector, detector, responder, alert, archiver)는 이번에 넓히지 않았다(`docs/exec-plans/tech-debt.md`).

## 결정

**실행**: 기동 직후(`ApplicationReadyEvent`), 주기(`edrdog.selftest.interval`, 기본 `PT1H`, 환경변수 `SELFTEST_INTERVAL`), 관리자 요청(`POST /api/operations/selftest`) 세 경로가 같은 `SelfTestService.run` 을 탄다. 한 번 돌 때 자체시험 항목과 무결성 항목을 함께 돌려 결과 한 건으로 저장한다.

**자체시험 항목**(이 서버의 보안 기능이 지금 실제로 동작하는지):

| 항목 | 방법 | 고른 이유 |
|---|---|---|
| `database` | `SELECT 1` | 계정과 세션이 MySQL 에 있다. 붙지 못하면 로그인이 전부 멈춘다 |
| `password_hash` | 실제 `BCryptPasswordEncoder` 가 알려진 해시(`htpasswd` 로 만든 값)를 맞게 판별하고 틀린 비밀번호를 거부하는지 | 인코더를 갈아 끼우거나 라이브러리가 깨져 모두 통과하거나 모두 거부하는 상태를 잡는다. 같은 인코더로 만들고 비교하는 왕복 시험은 대칭으로 깨지는 경우를 못 잡아서 알려진 값을 썼다 |
| `random` | 세션 토큰 생성기(`Tokens.newToken`)가 서로 다른 43자 값을 내는지 | 세션 토큰이 예측되면 로그인 없이 세션을 만든다 |
| `sha256` | 표준 시험 벡터 `SHA-256("abc")` | 무결성 검증과 node_key 해시가 기대는 알고리즘이다 |
| `security_config` | `edrdog.api.key`, `edrdog.internal.key` 가 비어 있지 않은지. 값은 적지 않고 이름만 | 비면 키 검증이 무의미해진다 |

뺀 것: **연동 서비스 응답**(collector, responder, Kafka, ClickHouse). 가용성 문제이지 보안 기능의 동작이 아니고, 운영 상태 화면(`operations/health`)이 이미 Kafka, ClickHouse, MySQL 을 보여 준다. 이걸 실패로 세면 무관한 장애 하나에 자체시험 실패 통보가 나간다. 기본값 키(`dev-api-key` 등) 여부도 뺐다. 로컬에서는 늘 기본값이라 늘 실패가 되고, 기본 비밀번호·키 변경은 인증 묶음(KISA 2.4.2)의 일이다.

**무결성 기준값**: `edrdog.selftest.integrity-baseline-file`(`INTEGRITY_BASELINE_FILE`) 이 가리키는 파일이다. `sha256sum` 출력 그대로(`<해시>  <절대경로>` 한 줄씩)이고 jar 와 설정 파일을 적는다. 배포 때 만들어 jar 밖(k8s ConfigMap 또는 Secret 볼륨)에 둔다. 서버는 줄마다 실제 파일의 SHA-256 을 계산해 비교한다.
- 일치는 `PASS`, 불일치와 파일 없음은 `FAIL`(기준과 실제 해시를 detail 에 보여 준다).
- 기준값 파일이 없으면 `SKIP`(실패가 아니다). 읽을 수 없거나 형식이 틀리거나 비어 있으면 `FAIL`.
- jar 로 실행 중인데 기준값에 그 jar 가 없으면 `FAIL`(`running_jar`). 빠진 채로 통과하면 jar 를 검사하지 않고 PASS 가 나온다.
- 로컬 개발(`bootRun`, 클래스 디렉터리)은 실행 중인 jar 가 없어 jar 항목이 없고, 기준값을 안 주면 `baseline` 이 `SKIP` 으로 보인다.
- `sha256sum -c baseline.sha256` 이 같은 판정을 내므로 사람이 손으로도 확인할 수 있다.

**결과 저장**: MySQL `selftest_runs`(JPA 엔티티 `SelfTestRun`). 한 행이 한 번의 실행이고 항목들은 JSON 컬럼 하나에 담는다. 실행 상태는 FAIL 항목이 하나라도 있으면 `FAIL`, 아니면 `PASS`(`SKIP` 은 실패로 세지 않는다).

**조회와 권한**: `GET /api/operations/selftest?limit=20`(최신순, 최대 100, 항목별 결과 포함), `POST /api/operations/selftest`(즉시 실행, 201 과 결과). 세션 Bearer 로 인증하고 role 이 `admin` 이어야 한다(없으면 401, admin 이 아니면 403). `/api/operations` 는 이미 X-API-Key 예외 접두어라 `ApiKeyPolicy` 를 건드리지 않았다.

**실패 시 대응(10.2, 11.2)**:
- 경고 로그 `[SELFTEST-FAIL] trigger=... failed=[...]`. 비밀값은 싣지 않는다(예외 메시지 대신 클래스 이름만).
- 운영 상태 화면 `GET /api/operations/health` 의 `dependencies` 에 `selftest` 한 줄(가장 최근 실행이 실패면 `down` 과 실패 항목 이름, 실행 전이면 `unknown`).
- Slack 통보. `edrdog.selftest.notify-webhook-url`(`SELFTEST_WEBHOOK_URL`)을 주면 기존 `SlackWebhookClient` 로 보낸다. 직전 실행과 실패 항목 집합이 달라졌을 때만 보낸다(같은 실패가 매시간 반복 통보되지 않게). 비어 있으면 통보하지 않는다. 통보가 실패해도 실행은 끝낸다.
- 시험이 못 돌아도(DB 장애 등) 기동과 스케줄러는 죽지 않는다. 로그 `[SELFTEST-ERROR]` 만 남는다.

## 근거

**기준값을 jar 안에 두지 않은 이유**: 빌드 때 jar 안에 매니페스트를 넣으면 jar 를 바꾸는 사람이 매니페스트도 같이 바꾼다. 위변조 탐지가 되지 않는다. 기준값이 검사 대상과 다른 곳, 다른 권한 아래에 있어야 의미가 있다.

**버린 대안**
- jar 안의 빌드 매니페스트: 위와 같다. 부분 수정은 잡지만 jar 를 다시 묶는 위변조는 못 잡는다.
- 첫 기동 때 해시를 기록(TOFU): 이미 변조된 상태로 처음 뜨면 변조본이 기준이 된다. 배포마다 jar 가 바뀌므로 기준 갱신 절차가 따로 필요하고, DB 를 쓸 수 있는 사람이 기준도 바꾼다.
- jar 서명(jarsigner)과 공개키 검증: 서명 키 관리와 검증 키 배포가 생긴다. 검증 코드도 같은 jar 안에 있다. 1인 개발 규모에서 운영 부담이 이득보다 크다.
- 해시 하나를 환경변수로: 설정 파일이 여럿이면 못 담고, 경로 정보가 없다. `sha256sum` 형식이면 파일 수와 경로를 그대로 담고 표준 도구로 같은 검사를 할 수 있다.

**actuator HealthIndicator 로 반영하지 않은 이유**: k8s 의 startup, readiness, liveness 프로브가 모두 `/actuator/health`(`k8s/api-service.yaml`)다. 자체시험 실패가 여기서 DOWN 이 되면 파드가 트래픽에서 빠지고 재시작된다. 무결성 불일치는 재시작으로 복구되지 않으므로(파일이 이미지에 있다) 크래시 루프로 서비스만 멎는다. 그래서 `operations/health` 의 `dependencies` 에만 싣는다.

**자동 복구, 서비스 종료는 하지 않은 이유**: 10.2, 11.2 는 선택 항목이다. 변조된 jar 를 서버가 스스로 복구할 수는 없고, 종료하면 위 크래시 루프와 같다. 통보와 상태 표시까지만 한다.

**결과를 항목별 테이블로 나누지 않은 이유**: 조회 단위가 실행 한 건이고 항목만 따로 질의하지 않는다. JSON 컬럼 하나면 엔티티가 하나이고 조인이 없다.

**API 를 `/api/operations/selftest` 에 둔 이유**: 서버 운영 상태 화면과 같은 묶음이고, 이미 Bearer 인증 예외 접두어라 공용 정책 파일을 건드리지 않는다.

## 결과/영향
- **한계**
  - 디스크의 파일만 본다. JVM 이 이미 올린 클래스가 지금 디스크와 같은지는 모른다. 기동 후에 jar 를 바꾸면 다음 실행에서 잡히지만, 바꾸기 전에 올라간 코드는 보증하지 못한다.
  - 환경변수로 주입되는 설정값(Secret)은 파일이 아니라 대조하지 못한다. 그 무결성은 Secret 과 매니페스트의 접근통제가 맡는다.
  - 기준값 파일을 두는 ConfigMap, Secret 에 쓸 수 있는 사람은 파일과 기준값을 함께 바꿀 수 있다. 이 검사는 이미지나 컨테이너 안의 파일만 바꾸는 경우를 잡는다.
  - 가입한 계정이 전부 `admin` 이라(`AuthService.signup`) 지금은 로그인한 사용자 누구나 이 API 를 쓴다. 서버 운영자 구분은 인증 묶음의 일이다.
  - 결과를 지우지 않는다. 시간당 한 행이라 당장 문제는 아니다.
- **배포 때 할 일**: CI 가 jar 의 `sha256sum` 을 만들어 기준값 파일로 마운트하고 `INTEGRITY_BASELINE_FILE` 을 준다. 이 연결은 이번에 하지 않았다. 그 전까지 운영의 무결성 항목은 `SKIP` 이다.
- **감사기록**: 결과를 감사기록에 남기는 연결은 `SelfTestService.run` 의 저장 직후(`runs.save` 다음)에서 `AuditService` 를 부르면 된다. 요청한 관리자는 `requestedBy` 에 있다.

## 관련
- `docs/requirements/security.md` 의 서버 자체시험·무결성 절(SEC-20~)
- `docs/security/kisa-edr-performance-criteria.md`, `docs/security/nis-requirements-review.md` 서버 4.1~4.2
- `docs/design-docs/decisions/024-install-pins-configured-cert-fingerprint.md` (같은 SHA-256 지문 대조 방식을 설치에 쓴 선례)
