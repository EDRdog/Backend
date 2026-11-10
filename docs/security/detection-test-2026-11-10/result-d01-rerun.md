# 탐지 시험 결과 20261110T142941Z

- host: gimdonghyeon-ui-MacBookPro.local, macOS 26.6.2, api: http://localhost:8084
- 에이전트 바이너리 sha256: d596a9bd43df70832b022fead4faeb51758ae212ab5127c3542e58c4a80a2b5c
- detector 마지막 기동: 2026-11-10T12:16:00Z (시작 시점 기준 8021s 경과)
- 시작 전 10분 이 host 의 알림: 7건 (0 이 아니면 배경 오탐이 섞인다)
- 간격 330s, 판정 대기 120s, 조회 주기 3s, 응답 시험 켬
- 파이프라인 상태(시작 전): `{"kafkaLag":[{"topic":"alerts","consumerGroup":"api-demo-arrivals","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"alert","lag":0,"status":"up","error":null},{"topic":"alerts","consumerGroup":"archiver-alerts","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"detector","lag":null,"status":"unknown","error":null},{"topic":"events","consumerGroup":"archiver","lag":null,"status":"unknown","error":null}],"clickhouseIngestion":[{"table":"edrdog.events","lagSeconds":1,"recentCount":8070,"status":"up","error":null},{"table":"edrdog.alerts","lagSeconds":10,"recentCount":26,"status":"up","error":null}]}`

| ID | ATT&CK | 기대 | 시작(UTC) | 수집 | 시작→수집 s | 알림 룰 | 심각도/권고 | 시작→알림 s | 응답 | 알림→조치결과 s | 판정 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| D01 | T1105+T1204 | detected | 14:29:41 | O | 3.69 | DOWNLOAD_AND_EXECUTE | CRITICAL/kill | 10 | KILLED | 4 | 탐지 |
