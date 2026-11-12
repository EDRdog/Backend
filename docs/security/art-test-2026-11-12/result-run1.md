# 탐지 시험 결과 20261112T081900Z

- host: gimdonghyeon-ui-MacBookPro.local, macOS 26.6.2, api: http://localhost:8084
- 에이전트 바이너리 sha256: d596a9bd43df70832b022fead4faeb51758ae212ab5127c3542e58c4a80a2b5c
- detector 마지막 기동: 2026-11-12T08:08:20Z (시작 시점 기준 640s 경과)
- 시작 전 10분 이 host 의 알림: 0건 (0 이 아니면 배경 오탐이 섞인다)
- 간격 150s, 판정 대기 120s, 조회 주기 3s, 응답 시험 끔
- 파이프라인 상태(시작 전): `{"kafkaLag":[{"topic":"alerts","consumerGroup":"api-demo-arrivals","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"alert","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"archiver-alerts","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"detector","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"archiver","lag":null,"status":"unknown","error":null}],"clickhouseIngestion":[{"table":"edrdog.events","lagSeconds":5,"recentCount":1853,"status":"up","error":null},{"table":"edrdog.alerts","lagSeconds":1508,"recentCount":0,"status":"up","error":null}]}`

| ID | ATT&CK | 기대 | 시작(UTC) | 수집 | 시작→수집 s | 알림 룰 | 심각도/권고 | 시작→알림 s | 응답 | 알림→조치결과 s | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| A01 | T1059.002 | detected | 08:19:01 | O | 5.89 | - | - | - | - | - | 미탐 |
| A02 | T1555.001 | detected | 08:23:31 | O | 5.947 | MACOS_KEYCHAIN_DUMP | MEDIUM/notify | 7 | - | - | 탐지 |
| A03 | T1555.001 | detected | 08:26:08 | O | 3.921 | - | - | - | - | - | 미탐 |
| A04 | T1539 | detected | 08:30:38 | O | 3.836 | - | - | - | - | - | 미탐 |
| A05 | T1539 | detected | 08:35:09 | O | 3.097 | MACOS_INFOSTEALER_COOKIE_THEFT | MEDIUM/notify | 3 | - | - | 탐지 |
| A06 | T1005 | detected | 08:37:42 | O | 4.903 | MACOS_INFOSTEALER_LOCAL_DATA | MEDIUM/notify | 7 | - | - | 탐지 |
| A07 | T1555.003 | detected | 08:40:19 | O | 2.747 | MACOS_INFOSTEALER_COOKIE_THEFT | MEDIUM/notify | 3 | - | - | 탐지 |
| A08 | T1555.003 | detected | 08:42:52 | O | 5.063 | MACOS_INFOSTEALER_BROWSER_CREDENTIALS | MEDIUM/notify | 7 | - | - | 탐지 |
| A09 | T1555.003 | detected | 08:45:29 | O | 2.825 | MACOS_INFOSTEALER_BROWSER_CREDENTIALS | MEDIUM/notify | 3 | - | - | 탐지 |
| A10 | T1041 | detected | 08:48:02 | O | 4.86 | MACOS_INFOSTEALER_EXFIL_CURL_ARCHIVE | MEDIUM/notify | 6 | - | - | 탐지 |
