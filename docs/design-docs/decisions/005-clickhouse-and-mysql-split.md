# 005. ClickHouse 와 MySQL 의 역할을 나눈다

- 날짜: 2026-07-24
- 상태: 채택

## 배경
이벤트 원본과 판정 기록은 계속 쌓이고 고치지 않는다. 계정·조직 설정·트리아지 상태는 건건이 바뀐다. 성질이 다른 둘을 한 저장소에 두면 어느 쪽이든 맞지 않는다.

## 결정

| | 담는 것 | 이유 |
|---|---|---|
| ClickHouse | 이벤트와 판정 기록 원본 | 컬럼 지향이라 집계할 때 필요한 컬럼만 읽는다. 쌓이고 수정하지 않는 로그 데이터에 맞다 |
| MySQL | 계정, 조직 설정, 알림·인시던트 처리 상태 | 건건이 수정되고 트랜잭션이 필요하다 |

알림 목록은 **ClickHouse 의 판정 기록 위에 MySQL 의 처리 상태를 겹쳐서** 보여준다. 판정기록(불변)은 `edrdog.alerts`(ReplacingMergeTree, 조회 시 `FINAL`)에, 가변 status 만 MySQL `alert_status` 오버레이에 둔다.

## 근거
같은 행을 "불변 사실"과 "사람이 바꾸는 상태"로 나누면, 불변 쪽은 append-only 로 두고 가변 쪽만 트랜잭션에 태울 수 있다. 판정 기록을 MySQL 에 넣으면 집계가 느려지고, 트리아지 상태를 ClickHouse 에 넣으면 수정이 비싸다.

**쓰기는 archiver 만 한다.** api-service 는 ClickHouse 를 읽기만 한다. 스키마 출처가 둘이 되면 드리프트가 생긴다.

## 결과/영향
- 알림 목록 질의가 두 저장소를 겹쳐 읽는다.
- `FINAL` 은 조회마다 중복 제거 병합을 강제한다. alerts 가 쌓이면 여기가 먼저 느려진다. 아직 안 고쳤다(`docs/exec-plans/tech-debt.md`).
- MySQL 은 서비스마다 스키마를 따로 둔다(`edrdog_collector`, `edrdog_responder` 등).

## 관련
- [009](009-clickhouse-batch-insert.md), [014](014-partition-by-ingest-day.md), [015](015-hourly-rollup.md)
