# 기술부채

알고 있으면서 아직 안 고친 것. 고치면 줄을 지우고, 고치기로 결정하면 `active/` 에 계획을 쓴다.

## 유실·가용성

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| Kafka 파드에 영속 볼륨이 없다 | `k8s/kafka.yaml` | 파드가 새로 뜨면 토픽과 `__consumer_offsets` 가 통째로 사라진다. 실제로 테이블 교체 때 offset 0 재소비로 이어졌다 | 2026-08-24 |
| 브로커 1대, 복제 계수 1 | `k8s/kafka.yaml` | 컨슈머 장애는 견디지만 브로커 장애는 대비돼 있지 않다. 의도된 선택이지만 한계는 한계다 | 2026-08-24 |
| 에이전트 송신 상한이 단말당 초당 100건 | `agent/internal/runtime/runtime.go` | 5초마다 `Drain(500)` 한 배치만 보낸다. 공격 피크(약 480건/초)면 버퍼 1만 건이 약 26초 뒤 넘쳐 오래된 것부터 버린다. 병목이 브로커가 아니라 에이전트다 | 2026-09-23 |
| 전송이 막히면 수집도 멈춘다 | `agent/internal/runtime/runtime.go` | flush 가 Run 루프에서 동기로 돈다(HTTP 타임아웃 5초 = FlushInterval). 막히면 채널(500)이 약 1분 안에 차고 센서가 멈춰 OS 단에서 유실된다 | 2026-09-23 |
| S3 아카이빙이 기본 OFF | `archiver-service` `ARCHIVE_ENABLED` | 운영에서 꺼져 있어 TTL 7일이 지난 원본은 복구할 수 없다 | 2026-10-04 |
| 센서가 죽어도 에이전트가 재기동하지 않는다 (KISA F3) | `agent/internal/runtime/runtime.go` | 센서 Run 이 오류로 끝나면 그 센서만 조용히 사라지고 하트비트는 계속 나간다. eslogger 는 한 줄이라도 읽은 뒤에는 센서 안에서 재기동하지만(`eslogger_darwin.go`), 한 줄도 못 읽고 끝난 경우와 다른 센서(ETW, netsnap, l7)에는 이 보호가 없다. 런타임 차원의 센서 재기동은 설계가 필요해 H8 수정에서 제외했다 | 2026-11-10 |

## 성능

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| alerts 조회가 `FINAL` 을 쓴다 | `api-service/.../host/HostRiskQueryBuilder.java` | ReplacingMergeTree 에 `FINAL` 은 조회마다 중복 제거 병합을 강제한다. alerts 가 쌓이면 여기가 먼저 느려진다. 중복 삽입이 실제로 있는지 보고, 없으면 떼고 있으면 `argMax` 로 바꾼다 | 2026-08-24 |
| actuator 프로브가 트랜잭션으로 잡힌다 | 뉴렐릭 설정 | 서비스당 시간 500건대가 트랜잭션으로 들어가 인제스트를 소모한다. 현재 7GB / 월 100GB 라 여유가 있어 미뤘다. 3GB/일을 넘으면 손본다 | 2026-08-24 |

## 미구현·미검증

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 격리(isolate)가 구현돼 있지 않다 | `ACTION_ISOLATE` 상수만 존재 | 대응 수단이 프로세스 종료 하나뿐이다. 관리 채널 예외와 자동 해제 타이머가 함께 있어야 안전해서 미뤘다 | 2026-10-04 |
| Windows ETW 센서가 실기기에서 검증되지 않았다 | `agent/internal/sensor/etw_windows.go`, `packaging/install-windows.ps1` | 크로스 컴파일과 단위 테스트까지만 통과했다. 확인 목록은 `agent/README.md` | 2026-10-04 |
| 설치 스크립트의 지문 대조(Windows)가 실기기에서 돌지 않았다 | `api-service/.../install/windows.ps1`, `agent/packaging/install-windows.ps1` | 검증 콜백이 스크립트 변수(`$ServerCertSha256`, `$seen`)를 읽는다. 개발 기기에 PowerShell 이 없어 구문 검사도 못 했다. 틀리면 설치가 늘 멈춘다(고정은 안 되므로 TOFU 로 돌아가지는 않는다) | 2026-11-10 |
| EOS 를 켰을 때의 비용이 측정되지 않았다 | detector | 측정 인프라만 넣어 두고 켜 보지는 않았다. 켜자는 얘기가 나오면 먼저 재야 한다 | 2026-10-04 |

## 문서·구조

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 하위 모듈 CLAUDE.md 가 코드와 어긋난다 | `alert-service/CLAUDE.md`, `responder-service/CLAUDE.md` | "미구현/스켈레톤" 이라고 적혀 있는데 실제로는 구현돼 있다. 읽은 사람이 틀린 전제로 출발한다. 지금은 gitignore 로 추적에서 빠져 있다 | 2026-10-04 |
| 빈 껍데기 디렉터리가 남아 있다 | `archiver/`, `detector/`, `responder/`, `api-server/` | 빌드 산출물만 있고 실제 모듈이 아니다. 모듈로 착각하기 쉽다 | 2026-10-04 |
| 자바 쪽에 린터가 없다 | 루트 `build.gradle` | spotless·checkstyle 설정이 없어 `scripts/check.sh` 의 lint 자리가 Go `vet` 뿐이다 | 2026-10-04 |

## 격리

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| detector `/api/events` 에 인증이 없다 | `detector-service/.../api/IngestController.java` | 클러스터 안 파드가 임의 이벤트를 detector 에 넣을 수 있다. responder 는 `X-Internal-Key` 로 막았지만(ADR 022) 여기는 그대로다. KISA H5 의 남은 부분 | 2026-11-10 |
| Kafka 가 무인증 PLAINTEXT 다 | `k8s/kafka.yaml` | 클러스터 안 파드가 `events`·`alerts` 를 읽고 쓸 수 있다. `alerts` 를 직접 넣으면 responder 가 kill 명령을 만든다(실행 스위치가 켜져 있을 때). KISA H5 의 남은 부분 | 2026-11-10 |
| NetworkPolicy 가 없다 | `k8s/` | 파드끼리 제한 없이 통신한다. 내부 키가 유일한 방어선이고, 키가 새면 그 위로 막을 층이 없다 | 2026-11-10 |
| 내부 키 기본값이 `dev-internal-key` 다 | 각 서비스 `application.yml` 의 `edrdog.internal.key` | `INTERNAL_API_KEY` 를 주입하지 못한 서비스는 공개된 기본값으로 뜬다. responder 도 같은 방식이다(다른 서비스와 맞췄다). 기본값을 없애는 건 KISA 2-6 에서 따로 다룬다 | 2026-11-10 |
| detector 상태가 tenant 를 가리지 않는다 | `detector-service/.../kafkastreams/topology/` 상태 키와 `events` 파티션 키(둘 다 host) | 서로 다른 tenant 에 같은 host 이름의 기기가 있으면 상관 버퍼를 같이 쓴다. 한쪽 이벤트가 다른 쪽 판정의 근거가 되거나 판정을 가린다. 알림의 tenant·target 은 트리거 이벤트를 따르므로 kill 은 트리거 쪽 기기로만 간다. 키를 `tenant\|host` 로 바꾸면 파티션 배치가 달라져 미뤘다(`README.md` 의 파티션 키 절) | 2026-11-10 |

## 입력 상한

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 이벤트 필드 하나의 길이와 detector 버퍼에 바이트 기준 상한이 없다 | `collector-service/.../agent/web/` 의 필드 검증, `detector-service/.../kafkastreams/topology/EventBuffer.java` | 요청 본문은 8MB 로 막혔지만(021) 8MB 짜리 필드 하나가 그대로 Kafka 와 detector 상관 버퍼에 들어간다. 버퍼 상한은 건수 기준이라 큰 이벤트가 쌓이면 힙을 쓴다 | 2026-11-10 |

## 보안

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 에이전트 바이너리에 서명이 없다 | `.github/workflows/agent-release.yml`, `api-service/.../install/` | 설치 스크립트가 보는 `.sha256` 은 바이너리와 같은 GitHub Release 에서 온다. 릴리스를 바꿀 수 있는 쪽은 해시도 바꾸므로, 해시는 끊긴 다운로드만 막는다. cosign·minisign 서명과 공개키 고정이 남은 틈이다(KISA H7, ADR 024) | 2026-11-10 |
| 이미 깔린 기기의 `server.pem` 은 TOFU 로 받은 것이다 | 엔드포인트 `/etc/edrdog/server.pem`, `C:\ProgramData\EDRdog\server.pem` | 지문 대조는 2026-11-10 이후 설치부터다. 그 전에 깐 기기는 운영자 지문과 직접 대조해 봐야 안다(ADR 024 결과/영향) | 2026-11-10 |

## 측정값 전제

ClickHouse 파티션(#180)과 롤업(#298) 수치는 **로컬 docker 단일 인스턴스 + 합성 데이터**다. 운영 실측이 아니다. 문서나 이력서에 쓰려면 이 전제를 같이 말해야 한다. 운영 수치가 필요하면 같은 질의를 운영에서 다시 돌려야 한다.
