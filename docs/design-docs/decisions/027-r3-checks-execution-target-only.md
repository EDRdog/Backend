# 027. R3(SCRIPT_FROM_TEMP_PATH)는 실행 대상만 본다

## 배경

R3 는 script 이벤트 명령행의 **아무 경로 인자**가 임시·다운로드 경로면 알림을 냈다. KISA 자체 시험(2026-11-10, `docs/security/detection-test-2026-11-10/`) 중 같은 맥의 정상 개발 작업이 네 번 걸렸다.

1. `python3.9 -c ... sys.path = ['/var/folders/.../pip.whl'] ...` (코드 속 경로)
2. `python3 -m venv /private/tmp/.../venv` (모듈 인자)
3. 2와 같은 형태 한 번 더
4. `python3 /private/tmp/.../venv/bin/pip install -q qrcode` (임시 경로의 스크립트를 실제로 실행)

`bash -c tsc -p /private/tmp/x.json`(설정 파일 인자)도 잡는다는 테스트가 있었다.

## 결정

명령행에서 **실행되는 것**만 본다.

- 명령 구분자(`&&`, `;`, `|` 등)로 나눈 명령마다 첫 토큰(실행 파일)
- 셸 `-c`, `cmd /c`, powershell `-Command` 뒤의 문자열은 다시 명령으로 본다
- 인터프리터(셸, python, perl, ruby, node, osascript, wscript, cscript, mshta)는 옵션을 건너뛴 첫 인자(스크립트 파일)
- powershell 은 `-File` 값
- `source`, `.`, `eval` 과 래퍼(`nohup`, `env`, `exec`, `sudo` 등) 뒤의 명령
- `-c`, `-m`, `-e`, `-` 뒤는 파일이 아니라 코드·모듈·표준입력이라 보지 않는다

리다이렉션 대상은 지금처럼 건너뛴다.

## 근거

- 위 1~3 과 `tsc -p` 는 임시 경로를 읽거나 쓸 뿐 거기서 실행하지 않는다. 이 룰이 노리는 T1059(임시 경로에 떨군 스크립트 실행)가 아니다.
- 4 는 실제로 임시 경로에 놓인 스크립트를 실행한 것이라 계속 잡는다. venv 경로를 예외로 두면 공격자가 디렉터리 이름을 venv 로 짓는 것만으로 빠져나간다.

버린 대안

- **-c/-m 뒤만 빼고 넓은 검사 유지:** 1~3 은 없어진다. 하지만 `tsc -p`, `cat /tmp/x` 같은 인자 오탐은 그대로 남는다.
- **그대로 두고 기술부채로만 남기기:** 개발자 단말에서 알림이 계속 쌓여 진짜 알림이 묻힌다.

## 결과/영향

- 인자로만 임시 경로가 나오는 명령은 더 이상 알림이 아니다(`RulesTest.r3_tempPathAsPlainArgument_noAlert`).
- 셸 문자열이 인용부호로 한 토큰이 된 `bash -c "cd / && /tmp/evil.sh"` 는 예전에 놓쳤다. 이제 다시 쪼개 보므로 잡는다(`RulesTest.r3_nestedExecutionTarget_alerts`).
- 알려진 한계: 셸·인터프리터의 값을 받는 옵션(`python -W x /tmp/a.py` 의 `x`)은 스크립트로 잘못 읽을 수 있다. 그때는 `x` 가 임시 경로가 아니라 미탐이 된다.

## 관련

- `detector-service/src/main/java/com/edrdog/detectorservice/rule/Rules.java` `executesFromTempPath`
- `docs/requirements/detection.md` DET-27
