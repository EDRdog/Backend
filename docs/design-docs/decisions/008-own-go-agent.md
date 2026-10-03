# 008. 수집기를 osquery + Zeek 에서 자체 Go 에이전트로 전면 교체한다

- 날짜: 2026-07-30
- 상태: 채택

## 배경
예전에는 프로세스·파일을 osquery 로, 네트워크를 Zeek 로 받아 서버에서 host 이름으로 묶었다. 두 도구가 각자 잘하는 것은 분명했다.

## 결정
둘을 버리고 Go 단일 실행 파일로 직접 만든다. 대상 플랫폼은 macOS 와 Windows.

| | 프로세스 | 네트워크 | 파일 |
|---|---|---|---|
| Windows | ETW `Kernel-Process` | ETW `Kernel-Network` | ETW `Kernel-File` |
| macOS | `eslogger` 의 `exec` | libproc 소켓 스냅샷(폴링) | `eslogger` 의 `create`/`rename`/`unlink` |

## 근거
**나눠 놓으면 정작 필요한 연결이 끊긴다.**

```
osquery 는 소켓의 주인을 모른다
Zeek 는 PID 를 모른다
```

가장 심각도가 높은 R2(다운로드 후 실행)가 정확히 두 관점을 이어야 성립하는 룰이다. 도구가 둘이면 그 연결은 "같은 host 에서 비슷한 시각에 일어났다" 는 정황 추정일 수밖에 없다. 한 에이전트가 프로세스 이벤트와 소켓 이벤트를 같이 받으면 **그 둘은 PID 로 이어진다.** 추정이 아니라 커널이 알려준 사실이다.

**Windows 사정이 하나 더 있었다.** Zeek 는 Windows 를 공식 지원하지 않는다. 리눅스 장비를 따로 세워 트래픽을 미러링하지 않으면 Windows 엔드포인트의 네트워크는 붙일 방법이 없었다.

**폴링으로 후퇴하지 않았다.** 공격 도구는 대개 짧게 실행되고 사라진다. 주기적으로 상태를 조회하면 조회 사이에 뜨고 사라진 프로세스를 놓친다. 수집기를 갈아치우면서도 이벤트 구독은 유지했다.

**macOS 가 EndpointSecurity API 를 직접 안 쓰는 이유**: API 직접 호출은 애플의 entitlement 심사를 통과해야 한다. `eslogger` 는 그 권한을 이미 가진 서명 바이너리라 심사 없이 같은 이벤트를 받는다. 대신 애플이 "API 가 아니며 릴리스마다 출력 구조가 바뀔 수 있다" 고 못 박아 두었다. 이 한계는 안고 간다.

**버린 대안**: Npcap 은 라이선스 문제로 쓰지 않는다. Windows 의 TLS SNI 는 문서화된 Win32 API("Minimum supported client 가 비어 있다")도, 리버스엔지니어링된 IOCTL("문서가 없다")도 아닌 `pktmon.exe` 외부 호출로 받는다.

## 결과/영향
- **`agent/` 만 GPL-3.0 이다.** ETW 라이브러리 `github.com/0xrawsec/golang-etw` 가 GPL-3.0 이라 링크한 바이너리가 전염된다. 나머지 저장소는 MIT. ETW 를 직접 syscall 로 붙이면 이 제약이 없어진다.
- macOS 바이너리는 macOS 에서만 만들 수 있다(cgo). Windows 쪽은 순수 Go 라 크로스 빌드가 된다. CI 가 macOS 러너를 쓰는 이유다.
- **macOS 네트워크만 폴링이다.** EndpointSecurity 에 소켓 연결 이벤트가 없고 NetworkExtension 은 심사 대상이라서다. 주기 사이에 열렸다 닫힌 연결은 놓친다.
- **Windows ETW 센서는 실기기에서 검증되지 않았다.** 크로스 컴파일과 단위 테스트까지만 통과한 상태다.

## 관련
- `agent/README.md`, [006](006-event-time-ordering.md)
