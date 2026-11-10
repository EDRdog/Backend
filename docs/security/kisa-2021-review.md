# KISA 소프트웨어 보안약점 점검 (2026-11-10)

기준은 KISA 소프트웨어 보안약점 진단가이드(2021)의 구현단계 49개 항목이다.
코드를 읽어서만 판정했다. 앱 실행, 침투 시험, 의존성 취약점 검사(네트워크 불가, govulncheck 미설치)는 하지 않았다. Windows 코드는 실기기에서 확인하지 않았다.

점검 대상 커밋: `c1b199b` (dev). 판정자는 서브에이전트 3개(api-service / 나머지 자바 서비스 / Go 에이전트)이고, 높음 등급은 사람이 코드에서 다시 확인했다.

## 요약

| 묶음 | 실패 항목 | 높음 |
|---|---|---|
| api-service | 14 | 4 |
| collector·detector·responder·alert·archiver·event-schema | 16 | 3 |
| agent (Go) | 9 | 1 |

세 묶음 모두 통과: SQL·명령어 삽입(1-1, 1-5), 역직렬화(5-5), 난수(2-8), 암호 알고리즘(2-4).

## 높음

| # | 항목 | 내용 | 위치 | 처리 |
|---|---|---|---|---|
| H1 | 2-2 | kill 명령이 tenant 없이 host 이름으로만 큐에 들어간다. 다른 tenant 에 같은 이름의 기기가 있으면 그 기기가 명령을 받는다. 결과 보고도 host 만 비교한다 | `api-service/.../alert/web/AlertController.java` `respond`, `responder-service/.../command/`, `collector-service/.../agent/AgentService.java` | 수정 (`docs/exec-plans/completed/kisa-cross-tenant-kill.md`) |
| H2 | 1-15 | 이벤트의 `host` 를 에이전트가 보낸 값 그대로 쓴다. collector 는 tenantId 만 덮어쓴다. 인증된 노드가 다른 기기 이름으로 이벤트를 넣어 탐지를 흐리거나 가짜 알림을 만든다 | `collector-service/.../agent/EventTagger.java` | 수정 (같은 계획) |
| H3 | 1-15 | 종료 대상(target)을 알림이 아니라 요청 본문에서 받는다. 로그인한 사용자가 아무 프로세스 이름이나 넣을 수 있다. `docs/agent-protocol.md` 의 "detector 가 알림에 실어 보낸 값을 그대로 쓴다" 와 다르다 | `AlertController.java` `respond` | 수정 (같은 계획) |
| H4 | 2-1, 6-2 | 운영 `DEMO_SEED=true`. 인증 없는 `/api/demo/**` 와 공개 계정 `test@edrdog.local`/`1234` 가 살아 있다 | `k8s/api-service.yaml`, `ApiKeyPolicy.java` | 감수. 데모 계정을 일부러 공개해 둔 것이다. H1·H3 를 고치면 이 계정으로 남의 기기를 건드리는 경로가 사라진다 |
| H5 | 2-1 | responder 의 `/api/responder/kill`, `/commands` 와 detector 의 `/api/events` 에 인증이 없다. Kafka 도 무인증 PLAINTEXT. 클러스터 안 파드 하나면 임의 kill 을 넣는다 | `responder-service/.../api/KillController.java`, `CommandController.java`, `detector-service/.../api/IngestController.java` | 부분 수정. responder 는 `X-Internal-Key` 로 막았다(ADR 022). detector `/api/events`, Kafka 인증, NetworkPolicy 는 tech-debt |
| H6 | 1-15 | 압축 안 한 본문에는 8MB 상한이 없다. collector 힙을 터뜨릴 수 있다. `docs/agent-protocol.md` 의 413 설명과 다르다 | `collector-service/.../agent/web/GzipRequestFilter.java` | 수정(ADR 021). 깨진 gzip 400 도 함께 |
| H7 | 2-11, 2-15 | 설치 스크립트가 서버 인증서를 검증 없이 받아 고정한다(Windows 는 검증 콜백이 항상 참). `.sha256` 이 없으면 경고만 하고 설치한다 | `api-service/src/main/resources/install/`, `agent/packaging/` | 수정(ADR 024). 설정된 지문만 고정, `.sha256` 없으면 중단. 바이너리 서명과 기존 설치 기기의 TOFU 인증서는 tech-debt |
| H8 | 4-3 | eslogger 한 줄이 4MB 를 넘으면 `pump` 이 끝난 뒤 `<-drained` 에서 영원히 막힌다. 로그도 재기동도 없고 하트비트는 계속 나간다 | `agent/internal/sensor/eslogger_darwin.go` | 수정(ADR 023). 긴 줄은 건너뛰고 자식을 먼저 죽인다. 실제 eslogger 로는 재현하지 않았다 |

## 중간

| 항목 | 내용 | 위치 |
|---|---|---|
| 2-5 | 24시간 설치 링크가 만료 없는 enroll secret 을 본문에 내준다 | `api-service/.../install/web/InstallController.java` |
| 1-12, 4-2 | 웹훅 URL 검증이 `https://` 접두어뿐(SSRF). 모든 RestClient 에 타임아웃이 없어 응답 없는 웹훅 하나가 alert 리스너를 막는다 | `WebhookValidation.java`, `SlackNotifier.java`, `SlackWebhookClient.java` |
| 2-16, 2-2 | 로그인·가입·enroll 에 시도 제한이 없다. 같은 tenant 안에서 다른 기기 이름으로 재enroll 하면 그 기기의 키를 뺏는다 | `AuthService.java`, `collector .../AgentService.java` |
| 2-2, 4-1 | `/api/operations/health` 가 전체 tenant 집계와 내부 주소를 로그인한 누구에게나 준다 | `OperationsHealthController.java` |
| 2-6, 2-9 | 설정 기본값 `dev-internal-key`, `edrdog`, `changeit`. 비밀번호는 길이 8 만 본다 | 각 서비스 `application.yml`, `AuthValidation.java` |
| 4-2 | 깨진 레코드 하나로 detector 가 멈출 수 있다(역직렬화 예외 핸들러 미설정) | `detector-service/.../application.yml` |
| 2-5 | 에이전트 HTTPS 커넥터가 기본 꺼짐. Kafka·ClickHouse·내부 호출이 평문 | `collector-service/.../application.yml` |
| 1-15 | 공백이 든 경로(`Application Support`)는 kill 이 늘 NO_MATCH. 이름만 온 대상은 같은 이름을 전부 죽이고 보호 목록이 없다 | `agent/internal/command/match.go`, `kill.go` |
| 4-2 | 센서가 죽어도 에이전트 프로세스는 살아 있어 재기동되지 않는다 | `agent/internal/runtime/runtime.go` |
| 7-2, 3-2 | Windows `pktmon.exe` 를 PATH 로 찾고 ETL 을 예측 가능한 임시 경로에 쓴다 | `agent/internal/sensor/pktmon_windows.go` |

낮음은 정수 범위(destPort), 쿨다운 경쟁조건, 깨진 gzip 의 500, 만료 안 되는 맵, 상수 시간 아닌 키 비교, 설치 경로 권한, 명령 무서명 등이다.

## 범위 밖 관찰

- `k8s/collector-service.yaml`, `k8s/api-service.yaml` 에 DB 비밀번호가 평문 env 다.
- Kafka NodePort 30092, ClickHouse NodePort 30123·30900 이 열려 있다. EC2 보안그룹에 따라 외부 노출 여부가 갈린다.
