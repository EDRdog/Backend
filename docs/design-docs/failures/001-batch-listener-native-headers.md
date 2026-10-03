# 001. 배치 리스너에 단건용 헤더 파라미터를 붙여 ClickHouse 적재가 통째로 멈췄다

## 증상
archiver-service 는 정상 기동했고 `/actuator/health` 도 UP 이었다. 그런데 ClickHouse 에 이벤트가 한 건도 쌓이지 않았다.

## 원인
트레이스를 이어 붙이려고 `@Header(KafkaHeaders.NATIVE_HEADERS)` 로 헤더만 따로 받는 파라미터를 추가했다(#245). **배치 리스너에는 그 헤더가 채워지지 않는다.** 단건 리스너 기준으로 생각하고 넣었다.

파라미터 해석은 기동 시점이 아니라 **레코드가 도착할 때** 실패한다. 그래서 배포는 초록불로 끝나고 적재만 조용히 멈췄다.

## 해결
리스너를 되돌려 적재를 되살린 뒤(`d514bb8`), 값 대신 `ConsumerRecord` 를 통째로 받는 방식으로 다시 구현했다. 레코드로 받으면 헤더에 손이 닿는다.

## 재발 방지
- 세 서비스(alert, responder, archiver)에 `@EmbeddedKafka` 테스트를 추가했다. 브로커를 띄워 실제로 한 건 흘려서, 리스너가 레코드를 받아 다음 단계로 넘기는지 확인한다.
- `AGENTS.md` 작업 방식에 "기동에서는 티가 안 나고 레코드가 올 때만 터지는 변경" 규칙을 올렸다.
- 각 서비스 `build.gradle` 의 `spring-kafka-test` 줄에 이유를 주석으로 박았다.

## 관련
- `docs/design-docs/decisions/009-clickhouse-batch-insert.md`
