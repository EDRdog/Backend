# 025. golang-etw 를 레포 안에 복사해 이벤트 ID 필터 버그를 고쳐 쓰고, 센서가 전부 멈추면 에이전트를 끝낸다

- 날짜: 2026-11-11
- 상태: 채택

## 배경
Windows 11 ARM64 VM(UTM, Pro 26300.9711)에서 에이전트를 처음 돌렸다. 관리자 권한인데도 ETW 센서가 시작하자마자 `process 프로바이더를 켜지 못했다(...:0xff:1,2:0x10): The parameter is incorrect` 로 멈췄다. 프로세스, 네트워크, 파일, DNS, L7 이 전부 0건이다.

같은 VM 에서 스펙만 바꿔 `EnableProvider` 를 불러 봤다(시험 프로그램, 레포 밖).
- 이벤트 ID 필터가 붙은 스펙은 다섯 프로바이더 모두 실패했다. 필터를 빼면 성공했다.
- 같은 필터를 직접 만든 버퍼로 `EnableTraceEx2` 에 넘기면 성공했다.
- 라이브러리(`github.com/0xrawsec/golang-etw` v1.6.2, 최신)의 `BuildFilterDesc` 가 돌려준 버퍼를 읽어 보니 `[3 0 0 0 ...]` 이었다. 기대값은 `[1 0 1 0 1 0]`(FilterIn=1, Count=1, ID=1)이다.

원인은 버퍼가 놓인 자리다. 버퍼 주소를 `uint64` 로만 넘기니 컴파일러는 버퍼가 밖으로 새지 않는다고 보고, 인라인된 `AllocEventFilterEventID` 의 `make` 를 스택에 둔다(`go build -gcflags=-m` 이 `make([]byte, size) does not escape` 를 찍는다). 그 함수가 끝나면 그 스택 자리는 다음 호출이 덮어쓴다. Go 1.25 부터 작은 가변 크기 `make` 도 스택에 둘 수 있게 되어 생긴 것으로 보고, 아키텍처와 상관없으니 x64 에서도 같을 것으로 본다(x64 실기기는 아직 안 봤다).

같은 시험에서 권한 없이 띄우면 ETW 센서가 오류를 남기고 멈추는데 에이전트는 계속 돌았다. `runtime.Run` 이 센서 오류를 로그로만 남기기 때문이다. `agent/README.md` 의 검증 절차는 종료 코드 1 로 죽는다고 적고 있었다.

## 결정
- golang-etw v1.6.2 를 `agent/third_party/golang-etw` 에 복사하고 `go.mod` 의 `replace` 로 쓴다. 바꾼 곳은 두 군데다. `etw/advapi32_header.go` 의 `AllocEventFilterEventID` 에 `//go:noinline` 을 붙여 버퍼를 힙에 둔다(스택은 커지거나 줄 때 옮겨져서 넘긴 주소가 낡을 수 있다). `etw/producer.go` 의 `EnableProvider` 는 버퍼를 포인터 변수로 들고 `EnableTraceEx2` 뒤에 `runtime.KeepAlive` 를 건다. 두 곳 모두 `EDRdog 패치` 주석을 달았다.
- 센서가 전부 오류로 멈추면 `runtime.Run` 이 오류를 돌려주고, 에이전트는 조치 채널도 닫은 뒤 종료 코드 1 로 끝난다. 일부만 멈추면 지금처럼 로그만 남기고 나머지로 계속 수집한다.

## 근거
버린 대안:
- **스펙에서 이벤트 ID 필터를 뺀다**: 수정은 가장 작다. 그런데 Kernel-Network keyword 0x30 은 연결 말고 송수신 이벤트까지 커널이 넘긴다. 패킷마다 이벤트가 생겨 CPU 와 처리량이 늘고, 늘어난 양을 잰 적이 없다.
- **에이전트에서 `EnableTraceEx2` 를 직접 부른다**: 세션 핸들이 비공개 필드라 꺼내려면 unsafe 로 구조체 오프셋을 읽어야 한다. 라이브러리가 바뀌면 조용히 깨진다.
- **상류에 고쳐 달라고 하고 기다린다**: 그동안 Windows 수집이 0건이다. 상류 PR 은 따로 낼 수 있다.
- **센서 하나만 멈춰도 에이전트를 끝낸다**: pktmon(L7) 은 Defender 나 드라이버 사정으로 못 열 수 있다. 그것 때문에 프로세스 수집까지 끊기면 손해가 더 크다.

## 결과/영향
- 같은 VM 에서 고친 바이너리로 프로세스(notepad, 부모 powershell), 네트워크, DNS(example.org), 파일(시작프로그램 폴더 CREATE), L7(TLS 1.3, example.org) 이벤트가 로컬 서버의 ClickHouse 까지 들어왔다. 측정 조건: Windows 11 ARM64 Pro 26300.9711, UTM, Defender 실시간 보호 켬, 몇 분씩 짧게 실행. 운영 실측이 아니다.
- 권한 없이 띄우면 안내 문구를 남기고 바로 종료 코드 1 로 끝나는 것을 같은 VM 에서 확인했다.
- 라이브러리 사본을 레포가 관리한다. 상류가 고치면 `replace` 와 `third_party/golang-etw` 를 지운다. 두 라이브러리 모두 GPL-3.0 이라 사본과 함께 LICENSE 를 둔다.
- 사본을 상류 새 버전으로 바꿀 때 `EDRdog 패치` 주석 두 곳을 다시 넣어야 한다. 빠뜨리면 다시 조용히 0건이 된다. 상류가 고쳤는지는 `go build -gcflags=-m ./etw` 에서 `AllocEventFilterEventID` 의 `make` 가 `escapes to heap` 인지로 본다.
- 센서가 전부 멈춰 에이전트가 끝나면 다시 띄울 주체가 필요하다. 두 Windows 설치 스크립트에 `sc.exe failure`(1분 뒤 재시작, 하루 지나면 횟수 초기화)와 `sc.exe failureflag 1` 을 넣었다. failureflag 가 없으면 SCM 은 정상 정지 보고에 종료 코드만 1 인 경우를 실패로 치지 않는다. VM 에서 `packaging/install-windows.ps1` 로 설치한 뒤 ETW 세션을 밖에서 끊자 에이전트가 종료 코드 1 로 끝났고(이벤트 7023), SCM 이 60초 뒤 다시 띄워 세션이 다시 열렸다(7031). 전에는 하트비트만 나가며 0건으로 돌았다.
- `third_party/golang-etw` 는 별도 모듈이라 `go vet ./...`, `go test ./...` 대상에서 빠진다. 사본의 시험은 돌리지 않는다.

## 관련
- `docs/requirements/ingest.md` AGT-24, AGT-M2
- `docs/exec-plans/tech-debt.md` 의 센서 재기동(F3), Windows 실기기 항목
- `agent/README.md` 의 Windows 실기기 검증 절차
