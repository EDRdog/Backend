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
| 센서가 죽어도 에이전트가 재기동하지 않는다 (KISA F3) | `agent/internal/runtime/runtime.go` | 센서 Run 이 오류로 끝나면 그 센서만 사라지고 하트비트는 계속 나간다. 전부 멈췄을 때만 에이전트가 종료 코드 1 로 끝난다(ADR 025). Windows 설치 스크립트는 서비스 실패 시 SCM 이 1분 뒤 다시 띄우게 한다(ADR 025). eslogger 는 한 줄이라도 읽은 뒤에는 센서 안에서 재기동하지만(`eslogger_darwin.go`), 한 줄도 못 읽고 끝난 경우와 다른 센서(ETW, netsnap, l7)에는 이 보호가 없다. 런타임 차원의 센서 재기동은 설계가 필요해 H8 수정에서 제외했다 | 2026-11-10 |

## 성능

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| alerts 조회가 `FINAL` 을 쓴다 | `api-service/.../host/HostRiskQueryBuilder.java` | ReplacingMergeTree 에 `FINAL` 은 조회마다 중복 제거 병합을 강제한다. alerts 가 쌓이면 여기가 먼저 느려진다. 중복 삽입이 실제로 있는지 보고, 없으면 떼고 있으면 `argMax` 로 바꾼다 | 2026-08-24 |
| actuator 프로브가 트랜잭션으로 잡힌다 | 뉴렐릭 설정 | 서비스당 시간 500건대가 트랜잭션으로 들어가 인제스트를 소모한다. 현재 7GB / 월 100GB 라 여유가 있어 미뤘다. 3GB/일을 넘으면 손본다 | 2026-08-24 |

## 미구현·미검증

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 격리(isolate)가 구현돼 있지 않다 | `ACTION_ISOLATE` 상수만 존재 | 대응 수단이 프로세스 종료 하나뿐이다. 관리 채널 예외와 자동 해제 타이머가 함께 있어야 안전해서 미뤘다 | 2026-10-04 |
| Windows 에이전트를 x64 데스크톱 Windows 에서 끝까지 돌려 보지 않았다 | `agent/internal/sensor/etw_windows.go`, `packaging/install-windows.ps1` | Windows 11 ARM64 VM(UTM, 26300.9711)에서는 다섯 종이 서버까지 왔다(2026-11-11, ADR 025). `packaging/install-windows.ps1` 설치와 `-service` 경로, SCM 재시작, 재부팅 후 기동, kill 도 같은 VM 에서 봤다. amd64 빌드는 ARM64 Windows 의 x64 에뮬레이션에서 같은 시험을 통과했고, x64 Windows Server 2025(GitHub Actions 러너, 26100)에서는 `TestETWLive` 를 한 번 돌려 다섯 종과 짧은 프로세스 명령행 50/50 을 확인했다(2026-11-11, 서버 없이 센서까지만, CI 잡으로는 두지 않음). x64 데스크톱 Windows 에서 서버 전송, kill, 서비스 설치까지 본 적은 없다. IPv6, 서버가 내려주는 설치 스크립트는 아직이다. Defender 가 pktmon 을 막는지는 짧게 몇 번 돌려 본 것뿐이다 | 2026-10-04 |
| 따옴표와 쉼표로 붙은 비밀값 옵션은 가려지지 않는다 | 에이전트 명령행 가림 처리 | PowerShell `-ArgumentList "-Password","hunter2"` 형태의 값이 그대로 서버까지 갔다(ARM64 VM). `-Password hunter2` 처럼 띄어 쓴 형태는 가려진다 | 2026-11-11 |
| 에이전트를 강제 종료하면 pktmon 캡처가 남는다 | `agent/internal/sensor/pktmon.go` | VM 에서 프로세스를 강제 종료했더니 `pktmon status` 가 캡처 중으로 남았다. 다음 에이전트가 뜰 때까지 패킷 캡처가 계속 돈다. 손으로는 `pktmon stop` 으로 정리한다 | 2026-11-11 |
| 설치 스크립트의 지문 대조(Windows)가 지문이 다를 때 멈추는지 보지 않았다 | `api-service/.../install/windows.ps1`, `agent/packaging/install-windows.ps1` | 검증 콜백이 스크립트 변수(`$ServerCertSha256`, `$seen`)를 읽는다. `packaging/install-windows.ps1` 은 ARM64 VM 에서 지문이 맞는 경우 설치까지 됐다(2026-11-11). 지문이 다른 경우와 서버가 내려주는 쪽은 아직이다. 틀리면 설치가 늘 멈춘다(고정은 안 되므로 TOFU 로 돌아가지는 않는다) | 2026-11-10 |
| 서버 자체시험·무결성의 기준값이 배포에 연결돼 있지 않다 | `.github/workflows/cd.yml`, `k8s/api-service.yaml` | CI 가 jar 의 `sha256sum` 을 만들어 ConfigMap/Secret 으로 마운트하고 `INTEGRITY_BASELINE_FILE` 을 주는 단계가 없다. 그 전까지 운영의 무결성 항목은 `SKIP` 이다(ADR 029) | 2026-11-11 |
| 서버 자체시험 API 의 admin 이 가입자 전원이다 | `api-service/.../selftest/web/SelfTestController.java`, `AuthService.signup` | 가입하면 role 이 `admin` 이라 어느 조직의 가입자든 서버 전체의 자체시험을 보고 실행한다. 서버 운영자 구분이 필요하다(viewer 역할은 생겼지만 조직 admin 과 서버 운영자를 가르는 것은 아직 없다, ADR 033) | 2026-11-11 |
| 접근통제 관문이 세션을 한 번 더 조회한다 | `api-service/.../access/AccessInterceptor.java` | 관문이 로그인한 사람을 읽고 기존 컨트롤러가 `auth.resolve` 로 다시 읽어 요청마다 세션·유저 PK 조회가 두 번 나간다. 컨트롤러가 요청 속성(`AccessInterceptor.PRINCIPAL`)에서 읽게 바꾸면 한 번이다(ADR 033) | 2026-11-11 |
| viewer 계정을 목록·삭제·비밀번호 재설정할 수 없다 | `api-service/.../access/web/UserAccountController.java` | 만들기만 있다. 잘못 만든 계정은 DB 에서 직접 지워야 한다 | 2026-11-11 |
| 관리 IP 제한이 운영에서는 아직 실제 단말 IP 를 못 본다 | `k8s/api-service.yaml` | Caddy 뒤라 `getRemoteAddr()` 가 프록시 주소다. `server.forward-headers-strategy=native` 주입이 필요하고 CD 가 이 매니페스트를 건너뛰어 사람이 한다(ADR 033, SEC-89) | 2026-11-11 |
| 서버 자체시험 결과를 지우지 않는다 | `selftest_runs` | 시간당 한 행이라 연 약 8,760행이다. 이력 보존은 KISA 4.1.2 에 맞지만 상한이 없다 | 2026-11-11 |
| api-service 밖의 서비스에는 자체시험·무결성 검증이 없다 | collector, detector, responder, alert, archiver | 관리 서버를 api-service 로 보고 거기만 했다. 평가에서 서버 구성요소 전체를 요구하면 서비스마다 같은 항목이 필요하다 | 2026-11-11 |
| EOS 를 켰을 때의 비용이 측정되지 않았다 | detector | 측정 인프라만 넣어 두고 켜 보지는 않았다. 켜자는 얘기가 나오면 먼저 재야 한다 | 2026-10-04 |

## 탐지

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| R1 이 실제 에이전트 이벤트로는 안 걸릴 수 있다 | `Rules.suspiciousProcessChain`, `isSequenceTrigger` | R1 은 자식 shell 이 `process` 타입이라고 가정한다(`isProcess`). 에이전트는 powershell, cmd, wscript, cscript, mshta 를 `script` 타입으로 보낸다(`agent/internal/sensor/etw_cmdline.go` `interpreters`). 단위 테스트는 process 타입으로 만들어서 통과한다. 실데이터로 확인하지 않았다 | 2026-11-10 |
| 새 데이터 룰이 api-service 카탈로그에 없다 | `api-service/.../alert/ThreatCatalog.java`, `SourceEventMatcher` | 화면에 한글 이름 대신 ruleId 원문, 카테고리 "기타", MITRE 비어 있음. dns 근거는 원본 이벤트로 이어지지 않는다(`SourceEventMatcher` 가 `dns ` 요약을 모른다). process, file, script 는 요약으로 이어진다 | 2026-11-10 |
| file 데이터 룰이 걸릴 일이 적다 | 에이전트 파일 센서의 감시 경로 | 에이전트는 자동실행 경로 등 감시 대상의 파일 이벤트만 보낸다. 번들 `file_*` 룰은 그 밖의 경로(임시 폴더 등)에서는 걸리지 않는다 | 2026-11-10 |
| 부정(filter) selection 의 경로 있는 Image 가 파일명으로 줄어 넓게 걸린다 | `SigmaParser.text` | `Image: 'C:\\Program Files\\X\\x.exe'` 를 제외 조건으로 쓰면 다른 경로의 같은 이름 파일도 제외된다(탐지가 줄어드는 쪽 오차). 지금 번들 룰은 Image 를 제외 조건으로 쓰지 않는다 | 2026-11-10 |
| DestinationPort 에 contains 류 modifier 를 써도 문자열 비교가 된다 | `SigmaParser` network_connection | 포트는 숫자 비교(범위, 이상)가 안 되고 `contains: 44` 가 4444 에도 걸린다. 번들 network 룰이 없어 영향은 없다 | 2026-11-10 |
| 룰이 갑자기 줄어도 경고가 없다 | `DataRules.scan` | 파일을 지우거나 ConfigMap 이 일부만 남으면 룰 수가 조용히 준다(로그 INFO 한 줄뿐). 감소 폭이 크면 경고한다 | 2026-11-10 |
| 데이터 룰 시간당 판정 비용을 재지 않았다 | `Rules.evaluate` | 이벤트마다 룰 28개를 순서대로 훑는다(type 색인 없음). 룰이 수백 개가 되기 전에 비용을 재고 색인한다 | 2026-11-10 |

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

## 감사기록

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 감사기록 보관 기간, 용량 임계 통보, 포화 시 대응이 없다 | `api-service/.../audit/` | `audit_log` 는 지우지 않고 쌓이기만 한다(국정원 서버 7.4, 7.5). 조회는 `tenant_id, ts` 색인을 탄다 | 2026-11-11 |
| 접속 IP 가 `getRemoteAddr()` 다 | `AuthController`, `AlertController` | 로드밸런서나 인그레스 뒤에서는 그 장비 주소가 찍힌다. 앞단이 생기면 `server.forward-headers-strategy` 와 신뢰할 프록시를 정해야 한다. `X-Forwarded-For` 를 그냥 믿으면 위조된다 | 2026-11-11 |
| tenant 를 알 수 없는 실패 기록은 조회 API 에 안 나온다 | `audit_log` 의 `tenant_id` 가 빈 행 | 가입 안 된 이메일의 로그인 실패, 시크릿이 틀린 enroll 시도. 무차별 대입 탐지는 DB 를 직접 봐야 한다. enroll 실패가 무한히 쌓이는 상한도 없다 | 2026-11-11 |
| 에이전트 감사기록 중복 제거가 없다 | `POST /api/internal/audit` | 성공 응답을 못 받고 재전송한 배치는 중복 행이 된다. 사건 id 를 에이전트가 만들어 보내면 막을 수 있지만 계약을 늘려야 해서 미뤘다 | 2026-11-11 |
| 에이전트 감사기록의 사건 유형이 서버에 등록돼 있지 않다 | `AuditType.FORMAT` | 형식만 검사한다. collector 를 통과한 에이전트가 임의 유형 문자열을 남길 수 있다(tenant 와 host 는 서버가 정한다) | 2026-11-11 |

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
