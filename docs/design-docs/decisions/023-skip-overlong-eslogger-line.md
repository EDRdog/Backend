# 023. eslogger 의 상한 초과 줄은 건너뛰고, 읽기가 끝나면 자식을 먼저 죽인다

- 날짜: 2026-11-10
- 상태: 채택

## 배경
`ESLoggerSensor.pump` 이 `bufio.Scanner`(상한 4MB)로 읽었다. 한 줄이 상한을 넘으면 `Scan` 이 `ErrTooLong` 으로 멈추고 pump 이 반환한다. 그 뒤 `runOnce` 는 자식이 살아 있는 채로 `<-drained` 를 기다렸다. stderr 가 EOF 가 안 되니 영원히 막히고, stdout 도 더는 안 읽혀 eslogger 도 선다. 로그도 재기동도 없이 하트비트만 계속 나가 프로세스·파일 이벤트가 조용히 끊긴다(KISA H8). 사용자가 제어문자 섞인 1MB 인자로 프로세스를 실행하면 eslogger 가 JSON 에서 약 6배로 이스케이프해 상한을 넘길 수 있다. 실제 eslogger 로 재현한 것은 아니고, 같은 모양(pump 만 끝나고 자식은 살아 있음)을 가짜 실행 파일로 재현해 테스트했다.

## 결정
- Scanner 를 `bufio.Reader` 기반 `readLine` 으로 바꾼다. 상한(4MB)을 넘는 줄은 끝까지 읽어 버리고 `skipped` 개수와 함께 Warn 로그를 남기고 다음 줄로 간다. 상한 이하의 줄은 그대로 파싱한다.
- `runOnce` 는 pump 이 어떤 이유로 끝나든 `<-drained` 와 `Wait` 전에 자식을 죽인다.
- 센서가 죽었을 때 런타임이 재기동하는 문제(F3)는 건드리지 않고 tech-debt 에 남긴다.

## 근거
버린 대안:
- **상한을 올린다**: 값만 옮길 뿐 더 큰 줄은 같은 방식으로 센서를 멈춘다. 공격자가 상한을 정한다.
- **줄 하나 때문에 센서를 오류로 끝낸다**: 센서 재기동이 런타임에 없어(F3) 그대로 이벤트 수집이 끝난다. 건너뛰는 쪽이 이벤트 한 건 손해로 끝난다.
- **pump 이 끝나면 stderr 를 닫는다**: 권한 실패 때 stderr 에만 있는 원인 메시지가 사라진다. 자식을 죽이면 메시지는 파이프에 남은 채 EOF 가 온다.

## 결과/영향
- 건너뛴 줄은 이벤트가 사라진다. 그 줄은 4MB 이상의 이스케이프된 인자를 단 exec 하나라서, 탐지 회피에 쓰일 여지는 남는다. 개수가 로그로 남아 볼 수는 있다.
- 줄마다 Warn 을 남기므로 상한 초과 exec 를 대량으로 내면 로그가 늘 수 있다.
- stderr 줄 읽기(`eslStderr.drain`)는 여전히 Scanner 다. 4MB 를 넘는 stderr 줄은 eslogger 가 내지 않는다고 보고 바꾸지 않았다.

## 관련
- `docs/requirements/ingest.md` AGT-22, AGT-23
- `docs/security/kisa-2021-review.md` H8, F3
