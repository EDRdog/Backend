# 탐지 시험 결과 20261110T122745Z

- host: gimdonghyeon-ui-MacBookPro.local, macOS 26.6.2, api: http://localhost:8084
- 에이전트 바이너리 sha256: d596a9bd43df70832b022fead4faeb51758ae212ab5127c3542e58c4a80a2b5c
- detector 마지막 기동: 2026-11-10T12:16:00Z (시작 시점 기준 705s 경과)
- 시작 전 10분 이 host 의 알림: 4건 (0 이 아니면 배경 오탐이 섞인다)
- 간격 330s, 판정 대기 120s, 조회 주기 3s, 응답 시험 켬
- 파이프라인 상태(시작 전): `{"kafkaLag":[{"topic":"alerts","consumerGroup":"api-demo-arrivals","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"alert","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"archiver-alerts","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"detector","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"archiver","lag":null,"status":"unknown","error":null}],"clickhouseIngestion":[{"table":"edrdog.events","lagSeconds":0,"recentCount":4469,"status":"up","error":null},{"table":"edrdog.alerts","lagSeconds":21,"recentCount":4,"status":"up","error":null}]}`

| ID | ATT&CK | 기대 | 시작(UTC) | 수집 | 시작→수집 s | 알림 룰 | 심각도/권고 | 시작→알림 s | 응답 | 알림→조치결과 s | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| D01 | T1105+T1204 | detected | 12:27:45 | O | 5.045 | DOWNLOAD_AND_EXECUTE | CRITICAL/kill | 10 | NO_MATCH | 4 | 탐지 |
| D02 | T1059.004 | detected | 12:33:29 | O | 5.896 | SCRIPT_FROM_TEMP_PATH | MEDIUM/notify | 7 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| D03 | T1547.011 | detected | 12:39:06 | X | - | - | - | - | - | - | 미탐 |
| D04 | T1573 | detected | 12:46:37 | X | - | WEAK_TLS_HANDSHAKE | MEDIUM/notify | 3 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| D05 | T1555.001 | detected | 12:54:07 | O | 3.067 | MACOS_KEYCHAIN_DUMP | MEDIUM/notify | 3 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| D06 | T1553.001 | detected | 12:59:40 | O | 4.941 | MACOS_XATTR_REMOVE_QUARANTINE | MEDIUM/notify | 7 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| D07 | T1543.001 | detected | 13:05:17 | O | 2.923 | MACOS_PLISTBUDDY_LAUNCHD_PERSISTENCE | MEDIUM/notify | 3 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| D08 | T1490 | detected | 13:10:50 | O | 4.967 | MACOS_TMUTIL_DELETE_BACKUP | MEDIUM/notify | 6 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| D09 | T1090.003 | detected | 13:16:26 | X | - | DNS_TOR_GATEWAY | MEDIUM/notify | 4 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| D10 | T1567.002 | detected | 13:23:57 | X | - | DNS_MEGA_USERSTORAGE | MEDIUM/notify | 3 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지 |
| M01 | T1033 | missed | 13:31:27 | O | 2.694 | - | - | - | - | - | 미탐(예상대로) |
| M02 | T1082 | missed | 13:39:00 | O | 4.866 | - | - | - | - | - | 미탐(예상대로) |
| M03 | T1057 | missed | 13:46:32 | O | 3.016 | - | - | - | - | - | 미탐(예상대로) |
| M04 | T1059.002 | missed | 13:54:02 | O | 2.829 | SCRIPT_FROM_TEMP_PATH | MEDIUM/notify | 114 | 건너뜀(kill 권고 아님 또는 대상이 시험 경로 밖) | - | 탐지(예상 밖) |
| M05 | T1105 | missed | 14:01:26 | O | 3.828 | - | - | - | - | - | 미탐(예상대로) |
| M06 | T1518.001 | missed | 14:08:56 | O | 3.744 | - | - | - | - | - | 미탐(예상대로) |

## 판정 보정 (사람이 확인, 2026-11-10)

- M04: 표의 `탐지(예상 밖)` 는 시나리오 때문이 아니다. 같은 시각 같은 맥에서 다른 작업이 `/private/tmp/.../venv` 의 python 으로 `pip install` 을 돌렸고, 그것이 SCRIPT_FROM_TEMP_PATH 에 걸렸다(ClickHouse alerts target 으로 확인). M04 는 `미탐(예상대로)` 로 본다. 대신 정상 개발 작업이 이 룰에 걸리는 오탐 사례로 남긴다.
- D04, D09, D10: 수집 칸 X 는 러너가 표식(MARK)을 찾지 못한 것이다. TLS·DNS 이벤트에는 명령행이 없어 표식이 남지 않는다. 알림이 났으므로 수집은 된 것으로 본다.
- D01: 응답 NO_MATCH 는 에이전트 잘못이 아니다. `/tmp` 에 복사한 `/bin/sleep` 은 서명 검사로 macOS 가 실행 직후 SIGKILL 한다(직접 재현: 종료 코드 137). kill 명령이 닿았을 때 대상이 이미 없었다. 시나리오에 `codesign -f -s -`(임시 서명)를 넣고 D01 만 다시 돌려 `KILLED`(알림→조치 4초, 프로세스 소멸 확인)를 얻었다. `result-d01-rerun.md` 참고.
- D03: 수집 0건. LaunchDaemon 에 HOME 이 없어 감시 경로 `~/Library/LaunchAgents` 의 `~` 가 펴지지 않는다(`agent/internal/sensor/eslogger_map.go` ExpandWatchPaths, `/Library/LaunchDaemons/com.edrdog.agent.plist` 에 EnvironmentVariables 없음).
- 첫 실행(`run-aborted.log`)은 러너가 시나리오 헤더를 `sed` 로 읽어 명령행에 `D08-tmutil-delete.sh` 가 실리면서 MACOS_TMUTIL_DELETE_BACKUP 이 오탐해 중단했다. 파일 이름을 ID 만 남기고 헤더를 bash 로 읽게 고친 뒤 다시 돌렸다.

보정 후 탐지율: 노린 기법 9/10 = 90%, 전체 16개 기준 9/16 = 56.3%.
