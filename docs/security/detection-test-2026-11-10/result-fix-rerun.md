# 탐지 시험 결과 20261110T182943Z

- host: gimdonghyeon-ui-MacBookPro.local, macOS 26.6.2, api: http://localhost:8084
- 에이전트 바이너리 sha256: d7f369d54906baa1608adf2356a91153b44894997757ebfbcccae418f9323f5f
- detector 마지막 기동: 2026-11-10T17:41:44Z (시작 시점 기준 2879s 경과)
- 시작 전 10분 이 host 의 알림: 2건 (0 이 아니면 배경 오탐이 섞인다)
- 간격 330s, 판정 대기 120s, 조회 주기 3s, 응답 시험 끔
- 파이프라인 상태(시작 전): `{"kafkaLag":[{"topic":"alerts","consumerGroup":"api-demo-arrivals","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"alert","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"archiver-alerts","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"detector","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"archiver","lag":null,"status":"unknown","error":null}],"clickhouseIngestion":[{"table":"edrdog.events","lagSeconds":3,"recentCount":2411,"status":"up","error":null},{"table":"edrdog.alerts","lagSeconds":139,"recentCount":1,"status":"up","error":null}]}`

| ID | ATT&CK | 기대 | 시작(UTC) | 수집 | 시작→수집 s | 알림 룰 | 심각도/권고 | 시작→알림 s | 응답 | 알림→조치결과 s | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| D02 | T1059.004 | detected | 18:29:43 | O | 2.103 | SCRIPT_FROM_TEMP_PATH | MEDIUM/notify | 2 | - | - | 탐지 |
| D03 | T1547.011 | detected | 18:35:18 | O | 2.183 | FILE_IN_AUTORUN_PATH | MEDIUM/notify | 3 | - | - | 탐지 |
| D08 | T1490 | detected | 18:40:51 | O | 3.787 | MACOS_TMUTIL_DELETE_BACKUP | MEDIUM/notify | 3 | - | - | 탐지 |
