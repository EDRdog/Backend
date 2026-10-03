# 009. ClickHouse 적재는 배치 INSERT 로 한다

- 날짜: 2026-07-31
- 상태: 채택

## 배경
archiver 가 이벤트 1건마다 INSERT 를 날리고 있었다.

## 결정
Kafka 배치 리스너로 받아 **poll 단위로 한 번에 INSERT** 한다. 앱에서 따로 버퍼링하지 않고 `async_insert` 도 쓰지 않는다.

배치가 실제로 모이도록 컨슈머 설정을 같이 바꿨다: `fetch-min-size: 64KB`, `fetch-max-wait: 500ms`.

## 근거
**ClickHouse MergeTree 에는 멤테이블이 없다.** INSERT 가 곧바로 디스크에 파트 하나를 쓴다. 이벤트 1건이 곧 파트 1개였고, 그대로 두면 `Too many parts` 로 적재가 거부된다.

**앱 버퍼링을 안 하는 이유**: Kafka 가 이미 버퍼다. 하나를 더 두면 유실 지점이 하나 더 생긴다.

**`async_insert` 를 안 쓰는 이유**: 유실 지점이 ClickHouse 안으로 숨어 archiver 쪽에서 관측이 안 된다.

**덤으로 얻는 것**: 랙이 클수록 배치가 커지므로, 정작 파트가 문제되는 고부하 구간에서 INSERT 횟수가 저절로 눌린다.

**fetch 하한이 필요했던 이유**(주석 원문):
> 상한만 두면 실제로는 안 묶인다. 브로커 기본값(fetch.min.bytes=1)이라 1건만 있어도 즉시 응답해서 poll 이 몇 건씩만 물고 온다(실측 5건). 하한을 줘야 상한에 가까워진다(실측 219건).

## 결과/영향
- 배치가 작업 단위다. 레코드마다 트랜잭션을 열지 않는다. 쪼개면 INSERT 를 한 번으로 묶은 설계가 깨진다.
- 이 전제 때문에 배치 리스너에 단건 리스너용 헤더 파라미터를 붙였다가 적재가 통째로 멈춘 적이 있다(`docs/design-docs/failures/001-*`).
- 219건은 위 fetch 설정이 있어야 재현된다.

## 관련
- [005](005-clickhouse-and-mysql-split.md), [014](014-partition-by-ingest-day.md)
