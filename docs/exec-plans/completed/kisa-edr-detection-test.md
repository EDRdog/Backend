# KISA EDR 자체 탐지 시험

KISA 단말 이상행위 탐지 및 대응(EDR) 제품 성능평가 항목 V1.4(2026-05-08)의 탐지 항목을 우리 손으로 재는 시험이다.
KISA 공격 시나리오는 비공개라, ATT&CK 기법을 흉내 내는 양성·되돌림 가능한 시나리오를 직접 만들어 쓴다.
설계만 한다. 이 문서와 `scripts/detection-test/` 가 산출물이고, 실행은 사람이 단말에서 한다.

## 목적

- 실제 맥 단말에 설치된 에이전트가 로컬 서버로 보내는 행위로, 수집 → 탐지 → 대응이 끝까지 도는지 한 번에 본다.
- 룰이 노리는 기법과 노리지 않는 기법을 섞어, 측정 탐지율이 설계상 100%가 되지 않게 한다.
- 결과를 측정 조건과 함께 남겨, 뒤에 "그때 얼마였나"를 다시 물을 수 있게 한다.

## KISA 항목 매핑

| KISA 항목 | 요구 | 이 시험이 보는 것 |
|---|---|---|
| EDR-BF-14.1 | 단말 행위 수집 | 결과표 `수집` 칸. api-service `/api/events` 에 그 행위 이벤트가 들어오는지 |
| EDR-BF-15.1 | 탐지·분석 | 결과표 `알림 룰` 칸. 상관 창 안에 알림(ruleId)이 나는지 |
| EDR-BF-16.1 | 대응 정책 수행 | 결과표 `응답` 칸. kill 권고 알림에 respond 를 걸어 결과 상태를 받는지 |
| EDR-SE-01 | 알려진 악성파일 탐지율 70% 이상 | 아래 "SE-01 을 그대로 쓰지 않는 이유" 참고. 대신 행위 탐지 커버리지로 잰다 |

### SE-01 을 그대로 쓰지 않는 이유

SE-01 은 "악성코드가 포함된 실행파일·스크립트"를 실행해 지문 기반 탐지율을 재는 항목이다.
이 제품은 백신이 아니라 행위 기반 EDR 이다(README "백신과의 차이"). 지문 대조를 하지 않으므로
SE-01 을 글자 그대로 적용하면 분모가 "알려진 악성파일"이 되어 측정 자체가 성립하지 않는다.
그래서 SE-01 의 취지(기본 설정에서 실행 시 탐지되는 비율)만 가져와, 우리가 실제로 탐지하는
단위인 ATT&CK 기법 샘플에 대한 행위 탐지율로 바꿔 잰다. 이 치환은 결과에 명시한다.

## 환경 (로컬 스택)

시험은 배포 서버가 아니라 로컬 스택에서 돈다(2026-11-10 사용자 결정). 서버는 로컬 kind,
단말은 사용자 맥북의 에이전트가 로컬 collector 를 보게 돌린 것이다.

| 항목 | 값 |
|---|---|
| 서버(인프라) | 로컬 kind: Kafka, ClickHouse, MySQL, otel-lgtm. 기동 절차는 `k8s/README.md` |
| 서버(앱) | 여섯 서비스를 호스트에서 bootRun 으로 띄운다(`./gradlew :collector-service:bootRun` 등). bootRun 은 OTEL 이 기본 켜져 있어 지표가 otel-lgtm 으로 간다. 파드로 띄우면 매니페스트가 `OTEL_ENABLED=false` 라 Grafana 에 커스텀 지표가 안 뜬다(아래 참고) |
| 대상 단말 | 사용자 맥북. 같은 에이전트 바이너리의 설정만 로컬 서버로 바꿔 띄운다. Windows 는 범위 밖 |
| api 조회 | `http://localhost:8084`(api-service bootRun, 또는 `kubectl -n edrdog port-forward svc/api-service 8084:80`). `API_BASE` 로 바꾼다 |
| 수집 포트 | 로컬 collector 의 에이전트 HTTPS `https://localhost:8443`(kind `30443 -> hostPort 8443`). 에이전트는 인증서를 고정한다 |
| Grafana | `http://localhost:3000`(admin / `GRAFANA_ADMIN_PASSWORD`). 첫 화면이 EDRdog Overview. 증거 스크린샷의 대시보드 |
| ClickHouse | `http://localhost:8123`(user/pw/db = edrdog). api-service 조회를 못 믿을 때 직접 질의 |
| 에이전트 버전 | 결과 파일에 바이너리 sha256 로 박제. 현재 소스 기준 `0.1.0`(`agent/cmd/edrdog-agent/main.go:25`) |
| 조회 경로 | api-service 읽기 API(`/api/events`, `/api/alerts`, `/api/alerts/{id}/respond`, `/api/operations/health`) 또는 ClickHouse 직접 질의. 새 코드를 만들지 않는다 |

### Grafana 지표가 뜨게 하려면

커스텀 지표(`cep.*`, `agent.uplink.rtt`, `events.kafka.lag`)는 매니페스트에서 꺼져 있다(여섯 `*-service.yaml` 모두 `OTEL_ENABLED=false`, 로컬 전용이라는 주석과 함께). 두 길 중 하나로 켠다.

- 권장: 앱을 호스트에서 bootRun. 기본 `OTEL_ENABLED=true`, 전송처 `http://localhost:4318`(kind 가 otel-lgtm 으로 매핑). 설정이 필요 없다.
- 파드로 돌릴 때: `kubectl -n edrdog set env deploy/collector-service deploy/detector-service deploy/archiver-service deploy/api-service OTEL_ENABLED=true OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-lgtm:4318` 로 켠다. 시험이 끝나면 되돌린다.

### 센서 한계(이 시험이 전제로 깔고 가는 것)

- 맥 네트워크는 1초 폴링이다. 짧게 열렸다 닫힌 연결은 놓친다(`agent/README.md` macOS 네트워크 절). 그래서 D01 은 443 연결을 몇 초 열어 둔다.
- `dns`/`l7` 센서는 root 와 이더넷이 있어야 돈다. 기본 경로가 VPN(utun)이면 0건이다. D04/D09/D10 전에 VPN 을 끈다.
- `file` 이벤트는 감시 경로(자동실행 폴더) 아래만 통과한다. D03 은 그래서 `~/Library/LaunchAgents` 에 파일을 만든다. 터미널에 전체 디스크 접근 권한(TCC)이 필요하다.
- 맥 `dns` 이벤트에는 프로세스가 없고, Windows 룰 18개는 실기기 미검증이라 이 시험 분모에서 뺀다.

## 대상 룰과 시나리오

탐지 룰은 33개(코드 R1~R5 + Sigma 28개)지만, 맥 센서만으로 발화 가능한 것은 R2~R5 와 맥 Sigma 10개다.
R1(Office→셸)은 맥에 Office 이름이 없고 셸이 `script` 타입으로 들어와 현재 구성에서 발화하지 않는다(미대상).
`file_lsass_dump_name`, `file_mimikatz_artifacts` 는 파일 이벤트가 감시 경로에서만 나와 발화 경로가 없다(미대상).

### 탐지 기대 (10개)

| ID | ATT&CK | 룰 | 시나리오 한 줄 | 정리 |
|---|---|---|---|---|
| D01 | T1105+T1204 | DOWNLOAD_AND_EXECUTE | example.com:443 연결을 몇 초 열고, 임시 경로에 `sleep` 복사본을 실행 | 프로세스 kill + 파일 삭제 |
| D02 | T1059.004 | SCRIPT_FROM_TEMP_PATH | `/tmp` 의 양성 스크립트를 bash 로 실행 | 파일 삭제 |
| D03 | T1547.011 | FILE_IN_AUTORUN_PATH | `~/Library/LaunchAgents` 에 빈 파일 생성(.plist 아님, 로드 안 함) | 파일 삭제 |
| D04 | T1573 | WEAK_TLS_HANDSHAKE | example.com:443 에 TLS 1.1 ClientHello 만 전송 | 없음 |
| D05 | T1555.001 | MACOS_KEYCHAIN_DUMP | `security find-certificate -a -p`(읽기 전용) | 없음 |
| D06 | T1553.001 | MACOS_XATTR_REMOVE_QUARANTINE | 임시 파일에 quarantine 속성을 붙였다 `xattr -d` 로 제거 | 파일 삭제 |
| D07 | T1543.001 | MACOS_PLISTBUDDY_LAUNCHD_PERSISTENCE | `/tmp` 파일에 PlistBuddy 로 RunAtLoad=true(실경로 설치 안 함) | 파일 삭제 |
| D08 | T1490 | MACOS_TMUTIL_DELETE_BACKUP | 존재하지 않는 경로에 `tmutil delete`(실제 백업 없음) | 없음 |
| D09 | T1090.003 | DNS_TOR_GATEWAY | `*.tor2web.org` 에 DNS 질의만(접속 안 함) | 없음 |
| D10 | T1567.002 | DNS_MEGA_USERSTORAGE | `*.userstorage.mega.co.nz` 에 DNS 질의만(업로드 안 함) | 없음 |

### 미탐 기대 (6개, 룰이 노리지 않는 흔한 기법)

| ID | ATT&CK | 왜 미탐이 정상인가 |
|---|---|---|
| M01 | T1033 | `whoami`/`id`. 식별 행위를 노리는 룰이 없다 |
| M02 | T1082 | `sw_vers`/`uname`. 시스템 정보 조회 룰이 없다 |
| M03 | T1057 | `ps aux`. 프로세스 열거 룰이 없다 |
| M04 | T1059.002 | 인라인 `osascript`. SCRIPT_FROM_TEMP_PATH 는 임시 경로 인자만 노린다 |
| M05 | T1105 | `curl` 로 받기만 하고 실행 안 함. DOWNLOAD_AND_EXECUTE 는 실행 단계가 있어야 한다 |
| M06 | T1518.001 | `spctl --status`(읽기 전용). MACOS_DISABLE_SECURITY_TOOLS 는 disable 류만 노린다 |

### 분모 선택 (명시)

- 분모 = 전체 시나리오 16개(탐지 기대 10 + 미탐 기대 6).
- 분자 = 알림이 한 건이라도 난 시나리오 수.
- 탐지율 = 분자 / 분모. 미탐 기대를 분모에 넣는 것은 비율이 설계상 100%가 되는 것을 막기 위해서다. 기대값은 10/16 ≈ 62.5%.
- 다른 분모(탐지 기대 10개만)로도 같이 적으면 "노린 룰 중 실제로 걸린 비율"을 따로 볼 수 있다. 둘 다 결과에 남긴다.

## 측정 방법

`scripts/detection-test/run.sh` 가 시나리오를 하나씩, 사이에 간격을 두고 돌리며 시각을 기록한다.

1. 각 시나리오의 시작 시각(UTC)을 찍는다.
2. 수집: `/api/events?host=&type=&from=&to=` 에서 그 시나리오 마커가 든 이벤트를 찾는다(시작→수집 지연).
3. 탐지: `/api/alerts?host=&from=&to=` 에서 상관 창 안에 난 알림의 ruleId 를 모은다(시작→알림 지연).
4. 대응(선택, `--respond`): kill 권고 알림의 대상이 이 시나리오 경로일 때만 `/api/alerts/{id}/respond` 를 걸고 결과 상태를 조회한다(알림→조치결과 지연). KILLED 면 프로세스가 실제로 사라졌는지 한 번 더 본다.
5. 결과를 마크다운 표 한 줄로 남긴다(템플릿은 `scripts/detection-test/results-template.md`).

지연값 성격: 시작→수집은 센서+collector+Kafka+archiver+ClickHouse 를 합친 값이고, 시작→알림은 조회 주기(기본 3초)를 포함한 상한이다. 순수 detector 지연이 아니다.

### 증거(스크린샷)와 Grafana 패널

최종 산출물은 KISA 항목별 합/불 보고서다(틀: `scripts/detection-test/kisa-report-template.md`). 항목마다 아래 세 장을 붙인다.

1. 러너 터미널 출력(결과표 한 줄과 수집/알림/응답 판정).
2. 알림·조치 증거: api-service 조회(`GET /api/alerts/{id}`, `/api/alerts/{id}/respond/{exe}`) 응답 또는 ClickHouse 질의 출력(아래).
3. Grafana 패널: 수집/탐지/대응 지연.

지연을 보여 주는 지표(모두 Micrometer, OTLP 로 otel-lgtm 에 들어간다). 아래 이름은 Prometheus 변환형이다. 정확한 시계열 이름은 Grafana Explore 의 metrics browser 로 한 번 확인한다(단위 접미사가 환경마다 다를 수 있다).

| KISA | 보는 지연 | 지표(메서드 정의) | Grafana 패널/쿼리 |
|---|---|---|---|
| BF-14 수집 | 업링크 왕복 + Kafka 대기 | `agent.uplink.rtt`(`collector .../agent/AgentService.java:51`), `events.kafka.lag`(`archiver .../EventListener.java:37`) | `agent_uplink_rtt_seconds{quantile="0.95"}`, `events_kafka_lag_seconds{quantile="0.95"}` |
| BF-15 탐지 | 이벤트 지각도, 발화 수 | `cep.event.lateness`(`detector .../CorrelationProcessor.java:76`), `cep.alerts`(`:242`), `cep.late.events`(`:145`) | `cep_event_lateness_milliseconds{quantile="0.95"}`, `increase(cep_alerts_total[5m])`, `increase(cep_late_events_total[5m])` |
| BF-16 대응 | 알림→조치 결과 | 서버 미터 없음. 러너의 `알림→조치결과 s` 와 api-service respond 결과가 증거다 | 패널 없음. 러너 출력 + respond 응답 스크린샷으로 대체 |

기존 EDRdog Overview 대시보드에는 위 커스텀 지표 패널이 없다(HTTP·JVM·컨슈머 랙만 있다, `k8s/local/otel-lgtm.yaml`). 새 대시보드를 만들지 말고, 위 쿼리를 Grafana Explore 에 붙여 스크린샷만 찍는다. 반복해서 쓸 거면 패널 넷(위 네 쿼리)을 EDRdog Overview JSON 에 time series 패널로 더하는 것으로 충분하다.

ClickHouse 직접 질의(api-service 를 못 믿을 때, `http://localhost:8123`):

```sql
-- 이 host 의 최근 알림(룰·심각도·대상)
SELECT ts, rule_id, severity, action, target FROM edrdog.alerts FINAL
WHERE host = '{HOST}' AND ts >= {START_MS} ORDER BY ts;
-- 이 시나리오 마커가 수집됐는지
SELECT ts, type, process, cmdline, domain FROM edrdog.events
WHERE host = '{HOST}' AND cmdline LIKE '%{MARK}%' ORDER BY ts;
```

## 측정 창 규칙 (지키지 않으면 결과 무효)

- **detector 재시작 후 10분 안에는 재지 않는다.** Kafka Streams 가 재시작하면 상태 복원·리밸런싱 대기가 그대로 지연으로 잡힌다(CLAUDE.md: 배포 직후 `kafkaLagMs` p50 82.9초 → 한 시간 뒤 105ms, 790배). 로컬도 같다. 러너는 `LAST_RESTART_AT`(detector 를 bootRun 한 시각, 또는 `kubectl -n edrdog get pods` 의 startTime) 기준 600초가 안 지났으면 멈춘다.
- 시작 전 파이프라인 상태(`/api/operations/health` 의 kafkaLag, clickhouseIngestion)를 결과에 박는다. lag 가 크면 그날 수치는 보류한다.
- 시작 전 10분간 이 host 의 알림 수를 적는다. 0 이 아니면 배경 오탐이 섞인 것이다.
- 부하 테스트(`scripts/loadtest`, k6)와 겹쳐 돌리지 않는다. 카운터가 리셋돼 측정이 섞인다(AGENTS.md 금지사항).

## 안전 규칙

- 진짜 악성코드 없음. 모든 시나리오는 양성 명령(`ls`/`sleep` 복사본, `dig`, 읽기 전용 `security`/`spctl`, 자기 임시 파일의 `xattr`)뿐이다.
- 외부 접속은 명시한 무해한 곳만: example.com(IANA 문서용), DNS 질의는 `tor2web.org`·`userstorage.mega.co.nz`(질의만, 접속 없음). 제3자 호스트에 페이로드를 보내지 않는다.
- 지속성 안 남김: D03/D07 은 실제 자동실행 경로에 로드하지 않고, 모든 생성물은 cleanup 단계에서 지운다. 러너는 어떤 경로로 끝나도 cleanup 을 돈다(trap).
- 권한 상승 없음: 모든 시나리오는 일반 사용자 권한이다. 러너는 root 로 돌면 거부한다. 계정 추가·관리자 권한 부여·SIP 해제처럼 상태를 바꾸는 룰(dscl/sysadminctl/csrutil 등)은 되돌리기 비용과 권한 상승 때문에 시나리오에서 뺐다.
- 사용자 데이터 안 건드림: 읽기는 자기 프로세스/시스템 메타만, 쓰기는 `/tmp` 와 자기 생성 파일만.
- 비밀값은 파일에 두지 않는다. 토큰·비밀번호는 환경변수로만 받는다.

## 결과 기록

- 러너가 `dettest-<UTC>.md` 를 만든다(측정 조건 + 결과표 + 탐지율). 형식은 `scripts/detection-test/results-template.md`.
- 그 표를 바탕으로 KISA 항목별 합/불 보고서를 채운다. 틀은 `scripts/detection-test/kisa-report-template.md`(항목마다 스크린샷 세 장).
- 결과 파일과 스크린샷은 레포에 커밋하지 않는다(로컬 측정 산출물). 요약만 이 계획 아래 "결과" 절이나 요구사항 상태 칸에 반영한다.
- 뉴렐릭·NRQL 은 운영 전용이라 이 로컬 시험에서는 쓰지 않는다(지표는 로컬 otel-lgtm 에 있다).

## 범위 밖 (이번에 하지 않는 것)

- 코드 수정 없음. R1 도달 불가와 `file_*` 두 룰의 발화 경로 부재는 설계 결정이 필요해 여기서 고치지 않는다(관찰로만 남긴다).
- Windows 시나리오. 실기기 미검증이라 뺀다(`agent/README.md`).
- 새 조회 코드·새 엔드포인트. 기존 api-service 읽기 API 로만 잰다.
- 실제 실행. 이 작업은 설계까지다. 러너 기본은 `--dry-run` 이고 실제 실행은 사람이 `--run` 으로 한다.

## 사람이 할 일

모두 로컬이다. 배포 서버 명령(sudo kubectl)은 없다. 로컬 kubectl 은 sudo 없이 쓴다.

### A. 로컬 서버 띄우기

1. 인프라 kind 기동(`k8s/README.md`): Kafka, ClickHouse, MySQL, otel-lgtm.
2. 에이전트 수집 TLS 준비(`k8s/README.md` 의 에이전트 수집 포트 절): `scripts/gen-dev-keystore.sh ./dev-tls localhost` → SAN 이 `localhost` 인 인증서. `./dev-tls/agent-server.pem` 이 에이전트가 고정할 인증서다.
3. 앱 여섯 개를 bootRun 으로 띄운다(OTEL 기본 켜짐). collector 는 위 keystore 로 8443 을 연다. 실제 kill 을 재려면 responder 에 `RESPONDER_EXECUTE_ENABLED=true`.
4. detector 를 띄운 시각을 적어 둔다(측정 창 10분 규칙 기준). 파드로 돌렸으면 `kubectl -n edrdog get pods -o jsonpath='{.items[?(@.metadata.labels.app=="detector")].status.startTime}'`.

### B. 맥 에이전트를 로컬 서버로 돌리기 (끝나면 되돌린다)

바이너리는 바꾸지 않는다(바이너리를 바꾸면 TCC 권한이 풀리고 enroll 을 새로 탄다, `agent/README.md`). 같은 바이너리에 설정만 로컬로 바꾼다.

```bash
# 1. 현재 설정을 백업
sudo cp /etc/edrdog/config.json /etc/edrdog/config.json.bak

# 2. 로컬 서버를 보도록 base_url 과 고정 인증서를 바꾼다(enroll_secret 은 로컬 조직 값으로)
sudo cp ./dev-tls/agent-server.pem /etc/edrdog/server-local.pem
# config.json 에서: base_url -> https://localhost:8443, ca_cert_path -> /etc/edrdog/server-local.pem,
#                   enroll_secret -> 로컬 대시보드에서 발급한 값
sudo launchctl kickstart -k system/com.edrdog.agent    # 설정 다시 읽기(로컬 서버로 재-enroll)

# 3. 시험이 끝나면 원복
sudo cp /etc/edrdog/config.json.bak /etc/edrdog/config.json
sudo launchctl kickstart -k system/com.edrdog.agent
```

로컬 서버로 재-enroll 하면 node_key 가 새로 발급된다(원래 서버의 등록은 그대로 남는다). 되돌리면 원래 서버로 다시 붙는다. 같은 바이너리라 TCC(전체 디스크 접근)는 유지된다.

### C. 시험 실행 (맥)

```bash
# VPN 끄기(utun 이면 dns/l7 센서가 0건), 에이전트가 도는지 확인
pgrep -x edrdog-agent

export API_BASE=http://localhost:8084          # api-service bootRun, 또는 port-forward 한 포트
read -rs EDRDOG_PW                             # 로컬 계정 비밀번호(화면에 안 보임)
export EDRDOG_TOKEN=$(curl -s "$API_BASE/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"<이메일>\",\"password\":\"$EDRDOG_PW\"}" | jq -r .token)
export EDRDOG_HOST=$(hostname -s)              # 에이전트 host_identifier 와 같아야 한다
export LAST_RESTART_AT=<detector 띄운 시각>     # 예 2026-11-10T03:00:00Z, 10분 지난 뒤 시작

scripts/detection-test/run.sh --dry-run        # 먼저 무엇이 돌지 확인
scripts/detection-test/run.sh --run            # 수집·탐지까지
scripts/detection-test/run.sh --run --respond  # 대응(kill)까지
```

### 자격 증명

- `EDRDOG_TOKEN`·`EDRDOG_PW`: 환경변수로만. 파일·레포에 저장하지 않는다.
- enroll_secret: 로컬 대시보드에서 발급한 값을 config.json 에 넣는다(로컬 전용, 운영 비밀 아님). 운영 enroll_secret 은 쓰지 않는다.
- node_key: 에이전트가 재-enroll 로 받는다. 사람이 다루지 않는다.

## 결과

2026-11-10 로컬 스택에서 실행했다. 결과와 증거는 `docs/security/detection-test-2026-11-10/README.md`.
