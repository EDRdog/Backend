# 009. 테이블을 교체했더니 빈 채로 시작하지 않고 42,585건이 다시 찼다

## 증상
`PARTITION BY` 적용을 위해 `events` 를 RENAME 으로 비켜 놓고 archiver 를 재기동했다. 새 테이블이 빈 채로 시작할 줄 알았는데 교체 직후 42,585건까지 찼다.

## 원인
`events` 3개 파티션 중 2개에 커밋 오프셋이 없었다. `auto-offset-reset: earliest` 라 offset 0 부터 재소비했다.

왜 오프셋이 없었나. **Kafka 파드에 영속 볼륨이 없어 `__consumer_offsets` 가 이전 재시작 때 날아가 있었다.**

## 해결
유실이 아니라 재적재라 해롭지는 않아 그대로 두었다. 다만 딸려오는 것이 둘이다.

- 재적재분은 `ingested_at` 이 전부 재기동 시각이라 오늘 파티션 한 칸에 몰린다
- 그 행들의 TTL 7일이 원래 발생 시각이 아니라 **재기동 시점부터** 다시 센다

## 재발 방지
"교체하면 빈 테이블이니 백필이 필요 없다" 는 계산을 하지 않는다. 교체 후 `SELECT count()` 로 실제 상태를 먼저 확인하고 백필 여부를 정한다.

교체 절차는 고정했다.
```
scale archiver=0 → RENAME TABLE edrdog.events TO edrdog.events_old → scale archiver=1 → 확인 → 며칠 뒤 DROP
```
손으로 `CREATE` 하지 않는다. `ClickHouseWriter.ensureSchema()` 가 단일 스키마 출처로 남아야 드리프트가 안 생긴다.

## 관련
- `docs/design-docs/decisions/014-partition-by-ingest-day.md`, `docs/design-docs/failures/006-kafka-heap-equals-container-limit.md`
