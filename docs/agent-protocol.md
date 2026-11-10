# 에이전트 프로토콜

자체 수집기(`agent/`)와 서버(`collector-service`) 사이의 계약이다.

기존에는 osquery 의 TLS remote 규약(`enroll`/`config`/`log`)을 그대로 썼다. 자체 수집기로 옮기면서
그 규약을 버린다. osquery 는 자기 표준 포맷으로만 로그를 내기 때문에 `columns` 중첩과
`node_invalid` 같은 껍데기가 붙었는데, 이제 양쪽을 다 우리가 만드니 그럴 이유가 없다.

## 전송

| | |
|:---|:---|
| 포트 | 에이전트 전용 HTTPS 커넥터 (프론트용 HTTP 포트와 분리) |
| 인증 | `X-Node-Key` 헤더. `enroll` 만 예외 |
| 실패 | HTTP 상태 코드로 알린다. 200 본문에 실패를 담지 않는다 |
| 인코딩 | JSON, UTF-8 |
| 압축 | 선택. `Content-Encoding: gzip` 이면 서버가 본문을 풀어서 받는다 |

압축은 선택이다. 헤더가 없으면 서버는 본문을 그대로 읽는다. 그래서 서버를 먼저 배포하고
에이전트를 나중에 올린다. 반대 순서면 서버가 못 푸는 요청이 먼저 들어온다.

본문이 **8MB** 를 넘으면 `413` 으로 끊는다. 압축한 본문은 푼 크기가 기준이고, 압축 안 한 본문도 같다.
상한이 없으면 몇 KB 짜리 요청이 수 GB 로 풀리거나 큰 본문이 힙에 올라 인증된 에이전트 하나로 collector 를
죽일 수 있다. 정상 배치는 500건에 1MB 언저리다. 깨진 gzip 본문은 `400` 이다.

에이전트는 `401` 을 받으면 저장한 node_key 를 버리고 다시 등록한 뒤 한 번 재시도한다.
서버가 재시작해 키를 잃어도 사람이 손대지 않고 복구되어야 한다.

서버는 발급한 node_key 를 평문으로 저장하지 않고 SHA-256 해시만 남긴다. 평문은 발급 응답에만
실린다. 그래서 같은 노드가 재-enroll 해도 기존 토큰을 되돌려줄 수 없어, 노드를 재사용하더라도
**새 토큰이 매번 발급된다.**

## 1. 등록

```
POST /api/agent/enroll
```

```json
{
  "enroll_secret": "조직마다 다른 비밀값",
  "host_identifier": "lab-mac",
  "platform": "darwin",
  "agent_version": "0.1.0"
}
```

`platform` 은 Go 의 `runtime.GOOS` 값 그대로 `darwin` 또는 `windows` 다.
osquery 는 여기에 비트마스크 숫자를 보내서 서버가 숫자와 이름을 모두 처리해야 했다. 그 분기는 없앤다.

**200**

```json
{ "node_key": "추측 불가 랜덤 토큰" }
```

**401** — enroll_secret 이 어느 조직과도 맞지 않음

```json
{ "error": "invalid_enroll_secret" }
```

## 2. 하트비트

```
POST /api/agent/heartbeat
X-Node-Key: ...
```

본문 없음. 서버는 이 호출로 마지막 접속 시각을 갱신한다(온라인 여부 관측용).

**200**

```json
{
  "config": {
    "sensors": { "process": true, "network": true, "file": true, "dns": true },
    "watch_paths": [
      "/Library/LaunchAgents",
      "/Library/LaunchDaemons"
    ],
    "flush_interval_seconds": 5
  },
  "commands": [
    { "id": "01J...", "type": "kill_process", "target": "/tmp/evil.sh" }
  ]
}
```

설정과 명령을 한 응답에 같이 내려준다. 대응 채널을 따로 열지 않는 이유는 엔드포인트가 방화벽
안쪽에 있어 서버가 먼저 접속할 수 없기 때문이다. 에이전트가 주기적으로 물어보는 쪽이 유일하게
설치 부담 없이 동작한다.

`watch_paths` 는 파일 감시 대상이다. 플랫폼별 기본값은 서버가 정해 내려준다.

`commands` 는 아직 처리되지 않은 명령만 담는다. 같은 명령을 두 번 받아도 안전해야 하므로
에이전트는 이미 실행한 `id` 를 기억하고 건너뛴다.

서버는 node_key 로 푼 노드의 **(tenant, host_identifier)** 로 넣은 명령만 내려준다. host 이름은 조직마다
겹칠 수 있어서, host 로만 찾으면 다른 조직의 같은 이름 기기가 명령을 받는다.

## 3. 이벤트 전송

```
POST /api/agent/events
X-Node-Key: ...
```

```json
{
  "prev_send_us": 41230,
  "events": [
    {
      "host": "lab-mac",
      "type": "process",
      "ts": 1785341400000,
      "process": "sh",
      "parent": "bash",
      "cmdline": "sh -c whoami",
      "destIp": null,
      "destPort": 0,
      "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "detail": "{\"pid\":4242,\"ppid\":501}"
    }
  ]
}
```

`prev_send_us` 는 **직전 이벤트 전송의 왕복 시간(마이크로초)** 이다. 첫 전송에는 실을 값이 없어
빠진다. 서버는 자기 인바운드 처리 시간만 알아서 업링크에 얼마를 썼는지 모르는데, 여기가 유일하게
고객사 네트워크를 타는 구간이라 그 값이 필요하다.

에이전트 시계 하나로만 재기 때문에 서버와의 시계 오차가 섞이지 않는다. 대신 서버 처리 시간이
포함된 왕복값이다. 보낸 시각을 실어 서버 수신 시각과 빼는 방식을 쓰지 않은 이유가 이것이다.
그 방식은 에이전트 시계가 어긋나면 값이 음수가 되거나 튄다.

이벤트 한 건의 형식은 detector 가 판정 입력으로 쓰는 스키마와 같다. 중간 변환이 없다.

| 필드 | 의미 |
|:---|:---|
| `host` | 엔드포인트 식별자. 상관분석 키. 서버가 등록된 `host_identifier` 로 덮어쓴다 |
| `type` | `process` / `network` / `file` / `script` / `dns` / `l7` |
| `ts` | 발생 시각, epoch millis |
| `process` | 프로세스명 또는 파일명. **전체 경로가 아니라 basename** |
| `parent` | 부모 프로세스명. process/script 만 |
| `cmdline` | 명령행. file/script 는 판정에 쓰는 전체 경로를 여기 담는다 |
| `destIp` | 목적지 IP. network 와 l7 |
| `destPort` | 목적지 포트. network 와 l7 |
| `domain` | DNS 질의 이름 또는 TLS SNI. dns 와 l7 |
| `detail` | 타입별 부가정보를 담은 JSON 문자열 |
| `sha256` | 파일 해시. process/script 는 실행 파일의 해시, file 은 그 파일의 해시 |

`domain` 을 `detail` 안에 넣지 않고 따로 둔 이유는 검색 때문이다. 대시보드에서 도메인으로
찾을 수 있어야 하는데 JSON 안에 묻히면 조회가 어렵다.

`sha256` 도 같은 이유로 별도 컬럼이다. "이 악성코드 해시가 우리 조직 어딘가에 있었나" 는 EDR 에서
가장 기본적인 조회인데, JSON 안에 묻히면 그 질문에 답할 수 없다.

`sha256` 은 **64자리 16진수라야 한다.** 서버가 형식을 확인해서 그 형태가 아니면 그 필드만 빈 값으로
떨어뜨린다(이벤트 자체는 남긴다). 잘린 해시나 다른 알고리즘 값이 섞이면 해시 조회 결과가 오염된다.
대문자로 보내도 서버가 소문자로 맞춘다. 같은 해시가 대소문자 때문에 둘로 보이면 조회가 갈린다.

**지금 에이전트는 `sha256` 을 process/script 에만 싣는다. file 이벤트에는 싣지 않는다.**
파일 생성 이벤트는 파일이 만들어진 순간에 오는 것이라 그때 읽으면 아직 다 쓰이지 않은 내용의
해시가 나온다. 그 값은 알려진 악성코드 해시와 영원히 맞지 않으면서 "해시를 확인했다" 는 착각을
주므로 없는 것보다 나쁘다. 실행되는 순간에는 process/script 이벤트가 같은 파일의 해시를 완성된
상태로 실어 보낸다. 서버 쪽 필드는 file 이벤트에도 열려 있으니, 완성 시점을 확신할 수 있는
수집 경로가 생기면 그때 채우면 된다.

에이전트는 해시를 못 구하면(권한 없음, 이미 지워짐, 크기 상한 초과) 그 필드만 비운다.
크기 상한은 32MB 다. 근거는 `agent/internal/sensor/hash.go` 에 있다.

`detail` 에서 판정에 쓰는 것은 프로세스 계보를 잇는 `pid`/`ppid` 와 file 이벤트의 `action` 둘뿐이고,
나머지는 조사 화면에서 보여줄 값이다. dns 는 질의 타입과 응답 IP 목록,
l7 은 인증서 발급자와 주체, 지문, TLS 버전 같은 것이다. 인증서 항목이 늘 때마다 컬럼을 늘리고
서비스 셋을 같이 배포하는 비용이 이득보다 커서 JSON 한 칸으로 묶었다.

타입에 상관없이 쓰이는 `detail` 키는 아래 넷이다.

| 키 | 값 | 실리는 타입 |
|:---|:---|:---|
| `pid` | 프로세스 ID | process / script / network / dns |
| `ppid` | 부모 프로세스 ID | process / script |
| `action` | `CREATE` / `WRITE` / `RENAME` / `DELETE` | file |
| `protocol` | `tcp` / `udp` | network / dns / l7 |

**관측하지 못한 값은 키를 아예 뺀다.** `pid` 를 0 으로, `action` 을 빈 문자열로 실어 보내면
"관측 못 함" 과 "0번 프로세스" 가 서버에서 구분되지 않고 ClickHouse 에 의미 없는 값만 쌓인다.
`detail` 에 담을 것이 하나도 없으면 `detail` 필드 자체가 빠진다.

플랫폼마다 채울 수 있는 값이 다르다. Windows 의 dns 이벤트에는 `protocol` 이 없다. ETW 의
DNS-Client 프로바이더가 질의를 UDP 로 보냈는지 TCP 로 보냈는지 알려 주지 않기 때문이고,
대개 UDP 라는 이유로 지어 넣으면 조사하는 사람이 그 값을 관측 결과로 믿는다.
macOS 의 dns 이벤트에는 `pid` 가 없다. 이유는 `agent/README.md` 에 적힌 것과 같다.

**패킷 페이로드는 어떤 형태로도 보내지 않는다.** 통신 내용을 서버로 옮기는 것은 수집이 아니라
감청이다. 패킷은 엔드포인트 메모리에서 메타데이터만 뽑고 그 자리에서 버린다.

basename 추출은 에이전트가 한다. 서버가 하던 일을 옮긴 것이고, 경로 구분자가 플랫폼마다 다르니
그 플랫폼에서 도는 쪽이 판단하는 게 맞다.

`tenantId` 는 **에이전트가 보내지 않는다.** 서버가 node_key 로 풀어 심는다. 엔드포인트가 보낸 값을
믿으면 다른 조직의 태그를 붙일 수 있다.

`host` 는 에이전트가 싣지만 **서버는 믿지 않는다.** node_key 로 푼 노드의 `host_identifier` 로 덮어쓴다.
보낸 값을 믿으면 인증된 노드 하나가 다른 기기 이름으로 이벤트를 넣어 그 기기의 탐지를 흐리거나 가짜 알림을
만든다. 에이전트는 두 값을 같은 설정(`host_identifier`, 비면 호스트 이름)에서 채우므로 정상 동작은 그대로다.

**200**

```json
{ "accepted": 1 }
```

`accepted` 는 브로커 ack 까지 받은 건수다. 검증에서 떨어진 건은 빠진다(다시 보내도 또 떨어진다).

**503**: 브로커 ack 를 못 받았다. 배치 일부가 이미 들어갔을 수 있지만 배치째 다시 보내야 한다.

전송에 실패하면(2xx 가 아니면) 에이전트는 그 배치를 버퍼 앞으로 되돌리고 다음 주기에 다시 보낸다.

## 4. 명령 결과 보고

```
POST /api/agent/command-result
X-Node-Key: ...
```

```json
{
  "command_id": "01J...",
  "status": "KILLED",
  "message": "pid 4242 종료"
}
```

**200** — 본문 없음

서버는 보고한 노드의 (tenant, host_identifier) 가 그 명령을 넣은 대상과 같을 때만 결과를 반영한다.
다르면 없는 명령 id 와 똑같이 200 을 주고 버린다. 명령 id 만 맞으면 받아 주면 다른 기기가 남의 조치를
`KILLED` 로 꾸며 알림을 닫는다.

에이전트가 쓰는 상태는 셋뿐이다.

| 상태 | 뜻 |
|:---|:---|
| `KILLED` | 대상을 찾아 종료했다 |
| `NO_MATCH` | 그 이름/경로로 도는 프로세스가 없다 |
| `FAILED` | 찾았지만 종료하지 못했다 |

`PENDING` / `TIMEOUT` / `COOLDOWN` / `DISABLED` 는 서버가 붙인다. 엔드포인트는 그 판단을 할 수 없다.

## 5. 감사기록 전송

```
POST /api/agent/audit
X-Node-Key: ...
```

```json
{
  "events": [
    { "ts": 1785341400000, "type": "integrity_check", "result": "success",
      "detail": "binary_integrity=ok(sha256=e3b0...); config_integrity=ok(sha256=9f86...)" }
  ]
}
```

KISA BF-13 의 에이전트 감사기록이다. 이벤트(`/api/agent/events`)와 따로 보낸다. 이벤트는 탐지 입력이고 감사기록은
관리자가 조회하는 증적이라 가는 곳이 다르다(`docs/design-docs/decisions/030-agent-selftest-integrity-audit.md`).

| 필드 | 의미 |
|:---|:---|
| `ts` | 발생 시각, epoch millis |
| `type` | 사건 종류. 소문자와 밑줄 1~64자. 아래 표 |
| `result` | `success` 또는 `failure` |
| `detail` | 사건 설명. 1000자를 넘으면 서버가 잘라서 받는다. 비밀값을 싣지 않는다 |

| `type` | 언제 |
|:---|:---|
| `agent_start` | 에이전트가 시작할 때. `version`, `platform` |
| `agent_stop` | 종료할 때. 센서가 전부 멈춰 끝나면 `failure`. 강제 종료(SIGKILL, 전원 차단)는 남지 않는다 |
| `agent_enroll` | 서버 등록에 성공했을 때 |
| `sensor_stop` | 센서가 오류로 멈출 때. 센서 이름과 오류 |
| `security_check` | 보안 기능 자체시험(KISA BF-10). 기동 시와 주기마다 한 건. 항목별 결과가 `detail` 에 `이름=ok|fail|skip` 으로 접혀 있다 |
| `integrity_check` | 바이너리와 설정 파일 무결성 검증(KISA BF-11). 기동 시와 주기마다 한 건. 통과한 항목은 현재 SHA-256 을 싣는다 |
| `command_result` | 대응 명령을 실행했을 때. 명령 id, 종류, 대상, 결과. `FAILED` 이면 `failure`, 나머지는 `success` |

자체시험 항목은 `server_pinning`(서버 인증서 고정 설정), `config_permission`(설정 파일이 다른 사용자에게 안 열려 있다),
`ca_cert_permission`(고정 인증서를 다른 사용자가 못 바꾼다), `sensors`(띄운 센서가 전부 살아 있다)다.
`skip` 은 그 환경에서 확인할 수 없다는 뜻이고 실패가 아니다(기동 시점의 `sensors`, Windows 의 권한 두 항목).
무결성 항목은 `binary_integrity`, `config_integrity` 다.

`tenantId` 와 `host` 는 에이전트가 보내지 않는다. 서버가 node_key 로 풀어 붙인다. 본문에 있어도 읽지 않는다.
collector 는 같은 값을 api-service 의 `POST /api/internal/audit`(`X-Internal-Key`)로 넘긴다.

```json
{ "tenantId": 7, "host": "lab-mac", "events": [ { "ts": 1785341400000, "type": "agent_start", "result": "success", "detail": "version=0.1.0 platform=darwin" } ] }
```

**200** `{ "accepted": 1 }`. `accepted` 는 api-service 로 넘긴 건수다. 모양이 틀린 건(`type` 모양, `result` 값)은 그 건만 빠진다.

**400**: `events` 가 없거나 100건을 넘는다. 에이전트는 100건씩 나눠 보낸다.

**413**: 본문이 8MB 를 넘는다. 위 전송 절의 상한이 그대로 걸린다.

**503**: api-service 로 넘기지 못했다. 에이전트는 그 기록을 메모리 로그 앞으로 되돌리고 다음 주기에 다시 보낸다.
응답이 유실되면 같은 배치가 두 번 들어갈 수 있다(적어도 한 번 전달).

에이전트의 로그는 1000건이 상한이고 넘치면 오래된 것부터 버린다. 프로세스가 죽으면 못 보낸 기록도 사라진다.
디스크 큐는 두지 않았다(이벤트 버퍼와 같은 한계).

## 대응 결과는 조회로 받는다

에이전트는 방화벽 안쪽이라 서버가 먼저 부를 수 없고, 명령은 다음 하트비트에 실려 나간다.
그래서 서버는 결과를 기다리지 않는다.

```
POST /api/alerts/{alertId}/respond                → 202 { status: PENDING, executionId }
GET  /api/alerts/{alertId}/respond/{executionId}  → { status }   PENDING 이면 다시 조회
```

api-service 는 이 둘을 responder 의 `POST /api/responder/kill`, `GET /api/responder/kill/{id}` 로
위임한다. 위임할 때 tenant·host·target 을 전부 알림에서 꺼내 싣는다. 조회 결과는 명령의 tenant·host 가
알림과 같을 때만 보여 주고, 결과가 `KILLED` 가 되는 조회에서 알림을 `CONFIRMED` 로 넘긴다.

명령은 responder 의 MySQL(`edrdog_responder.agent_command`)에 (tenant, host) 로 저장한다. 같은 키로
쿨다운도 센다. tenant 칸이 생기기 전에 들어간 옛 행은 tenant 가 비어 어느 하트비트에도 내려가지 않는다.

| 상태 | 언제 |
|:---|:---|
| `PENDING` | 저장됨. 아직 아무 하트비트도 가져가지 않음 |
| `DELIVERED` | 하트비트가 가져감. 조건부 갱신이라 collector 가 여럿이어도 한 번만 내려간다 |
| `KILLED` / `NO_MATCH` / `FAILED` | 에이전트가 보고함. `DELIVERED` 인 명령에만 한 번 기록된다 |

조회 API 는 `PENDING` 과 `DELIVERED` 를 모두 `PENDING` 으로 보여주고, 시한(기본 30초)을 넘기면
`TIMEOUT` 으로 보여준다. 같은 시한 안에 아무 하트비트도 가져가지 않은 명령은 그 뒤로 내려보내지 않는다.
화면에 `TIMEOUT` 이 뜬 뒤 다시 누른 명령과 겹쳐 두 번 실행되지 않게 하기 위해서다. 시한 안에 가져간 명령의
보고가 늦게 오면 이후 조회에서 결과로 바뀐다.

## 명령 종류

### kill_process

`target` 은 종료할 대상이다. detector 가 알림에 실어 보낸 값을 그대로 쓴다.
전체 경로가 있으면 경로, 없으면 프로세스명이다.

archiver 가 이 값을 판정 기록(`edrdog.alerts` 의 `target`)에 저장하고, api-service 의 respond 가 거기서
꺼낸다. 대시보드가 요청 본문에 `target` 을 실어 보내도 받기만 하고 쓰지 않는다. 본문을 믿으면 로그인한
사용자가 그 기기의 아무 프로세스나 죽일 수 있다. 저장 칸이 생기기 전에 쌓인 알림은 대상이 비어 있어
`400` 으로 거절한다.

에이전트는 실행 중인 프로세스에서 대상을 찾아 종료한다. 경로가 오면 실행 파일 경로가 일치하는
프로세스를, 이름이 오면 파일명이 일치하는 프로세스를 찾는다.

자기 자신과 PID 1 은 절대 종료하지 않는다.

## 감사기록 (collector 가 api-service 로 넘기는 내부 계약)

에이전트가 만든 감사기록(KISA EDR-BF-13.2)을 서버가 받는 자리다. 에이전트는 collector 로 올리고, collector 가 node_key 로 tenant 와 host 를 풀어 api-service 로 넘긴다. 이 계약은 에이전트 묶음이 collector 와 api-service 호출을 만들 때 그대로 쓴다. 근거는 `docs/design-docs/decisions/028-audit-log-in-api-service.md`.

`POST /api/internal/audit` (api-service). `X-Internal-Key` 헤더로만 인증한다.

```json
{
  "tenantId": 1,
  "host": "host-a",
  "events": [
    { "ts": 1762800000000, "type": "integrity_check_result", "result": "failure", "detail": "binary hash mismatch" }
  ]
}
```

| 필드 | 규칙 |
|---|---|
| `tenantId` | 양수. collector 가 node_key 로 푼 값이다. 에이전트가 보낸 값을 쓰지 않는다 |
| `host` | 1~255자. 마찬가지로 collector 가 푼 값 |
| `events` | 1~100건. 한 건이라도 형식이 틀리면 400 으로 배치 전체를 받지 않는다 |
| `events[].ts` | 사건이 난 시각, epoch millis, 0 보다 큼. 서버 시각보다 앞선 값은 받은 시각으로 맞춘다 |
| `events[].type` | 소문자, 숫자, `_` 로 1~64자(`[a-z0-9_]{1,64}`). 서버는 형식만 본다. 서버가 쓰는 유형은 `AuditType` 에 있다 |
| `events[].result` | `success` 또는 `failure` |
| `events[].detail` | 짧은 설명. 500자를 넘으면 잘라서 저장한다. 비밀값을 넣지 않는다 |

- 응답 `200 {"accepted": N}`. 키가 없거나 틀리면 `401`. 저장에 실패하면 `5xx` 이고 아무것도 저장하지 않으므로 호출자가 같은 배치를 다시 보내면 된다(중복 제거는 없다. 성공 응답을 못 받은 배치를 다시 보내면 중복 행이 생길 수 있다).
- 저장된 기록은 행위자 종류 `agent`, 행위자 식별 `host` 로 보이고, 그 tenant 의 admin 이 `GET /api/audit` 로 조회한다.
