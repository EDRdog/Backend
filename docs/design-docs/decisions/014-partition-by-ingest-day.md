# 014. events 를 적재일로 파티션하고 ttl_only_drop_parts 를 함께 켠다

- 날짜: 2026-09-02
- 상태: 채택

## 배경
`PARTITION BY` 가 없으면 테이블 전체가 단일 파티션이고, ClickHouse 는 TTL 만료를 **파트마다 살아 있는 행까지 다시 쓰는 머지**로 처리한다.

## 결정
적재일 기준으로 파티션하고 `ttl_only_drop_parts=1` 을 같이 켠다. 둘은 한 쌍이다.

## 근거
로컬 docker `clickhouse/clickhouse-server:24.8` 단일 인스턴스, 합성 데이터 11일치 × 하루 1000행, 4일치 만료. `system.part_log` 기준.

| | MergeParts | 다시 쓴 행 | 남은 행 |
|---|---:|---:|---:|
| 파티션 없음 | 2회 | 6000 | 7000 |
| 파티션 + `ttl_only_drop_parts=1` | 5회 | 7 | 7000 |

`ttl_only_drop_parts` 기본값이 0이라 **`PARTITION BY` 만으로는 효과가 없다.**

**컬럼 `CODEC(ZSTD)` 를 같이 넣지 않은 이유**: 교체 자체가 확인할 것이 있는 작업이라 압축 변경을 섞으면 무엇이 문제였는지 가려진다.

## 결과/영향
- **이 수치는 운영 실측이 아니다.** 로컬 합성 데이터다. 문서나 이력서에 쓰려면 이 전제를 같이 말해야 한다.
- `PARTITION BY` 는 ALTER 로 못 바꾼다. RENAME 으로 비켜 놓고 archiver 를 재기동해 새 DDL 로 다시 만든다. 손으로 CREATE 하지 않는다.
- 교체하면 새 테이블이 빈 채로 시작한다는 계산을 하면 안 된다. 실제 교체 때 커밋 오프셋이 없어 offset 0 부터 재소비해 42,585건이 다시 찼다(`docs/design-docs/failures/009-*`).

## 관련
- [005](005-clickhouse-and-mysql-split.md), [015](015-hourly-rollup.md)
