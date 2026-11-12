# 탐지 시험 결과 20261112T090401Z

- host: gimdonghyeon-ui-MacBookPro.local, macOS 26.6.2, api: http://localhost:8084
- 에이전트 바이너리 sha256: d596a9bd43df70832b022fead4faeb51758ae212ab5127c3542e58c4a80a2b5c
- detector 마지막 기동: 2026-11-12T08:53:06Z (시작 시점 기준 655s 경과)
- 시작 전 10분 이 host 의 알림: 0건 (0 이 아니면 배경 오탐이 섞인다)
- 간격 150s, 판정 대기 120s, 조회 주기 3s, 응답 시험 끔
- 파이프라인 상태(시작 전): `{"kafkaLag":[{"topic":"alerts","consumerGroup":"api-demo-arrivals","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"alert","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"archiver-alerts","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"detector","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"archiver","lag":null,"status":"unknown","error":null}],"clickhouseIngestion":[{"table":"edrdog.events","lagSeconds":5,"recentCount":1786,"status":"up","error":null},{"table":"edrdog.alerts","lagSeconds":955,"recentCount":0,"status":"up","error":null}]}`

| ID | ATT&CK | 기대 | 시작(UTC) | 수집 | 시작→수집 s | 알림 룰 | 심각도/권고 | 시작→알림 s | 응답 | 알림→조치결과 s | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A01 | T1059.002 | detected | 09:04:01 | O | 6.056 | MACOS_OSASCRIPT_SHELL_DECODE_EXEC,SCRIPT_FROM_TEMP_PATH | MEDIUM/notify | 6 | - | - | 미탐(다른 룰만) |
| A02 | T1555.001 | detected | 09:06:37 | O | 4.812 | MACOS_KEYCHAIN_DUMP | MEDIUM/notify | 7 | - | - | 탐지 |
| A03 | T1555.001 | detected | 09:09:14 | O | 2.687 | MACOS_KEYCHAIN_FILE_COPY | MEDIUM/notify | 3 | - | - | 미탐(다른 룰만) |
| A04 | T1539 | detected | 09:11:47 | O | 5.029 | MACOS_CHROMIUM_HEADLESS_DEBUGGING | MEDIUM/notify | 7 | - | - | 미탐(다른 룰만) |
| A05 | T1539 | detected | 09:14:25 | O | 1.828 | MACOS_INFOSTEALER_COOKIE_THEFT | MEDIUM/notify | 3 | - | - | 탐지 |
| A06 | T1005 | detected | 09:16:59 | O | 2.919 | MACOS_INFOSTEALER_LOCAL_DATA | MEDIUM/notify | 3 | - | - | 탐지 |
| A07 | T1555.003 | detected | 09:19:32 | O | 4.687 | MACOS_INFOSTEALER_COOKIE_THEFT | MEDIUM/notify | 7 | - | - | 탐지 |
| A08 | T1555.003 | detected | 09:22:09 | O | 2.767 | MACOS_INFOSTEALER_BROWSER_CREDENTIALS | MEDIUM/notify | 3 | - | - | 탐지 |
| A09 | T1555.003 | detected | 09:24:42 | O | 4.578 | MACOS_INFOSTEALER_BROWSER_CREDENTIALS | MEDIUM/notify | 6 | - | - | 탐지 |
| A10 | T1041 | detected | 09:27:19 | O | 2.874 | MACOS_INFOSTEALER_EXFIL_CURL_ARCHIVE | MEDIUM/notify | 3 | - | - | 탐지 |
