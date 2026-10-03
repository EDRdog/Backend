# 탐지 (PRD: `docs/product-specs/README.md` 탐지 룰)

판정 파이프라인이 성립해야 하는 것. 왜 이렇게 정했는지는 `docs/design-docs/decisions/`.

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| DET-1 | 외부에서 받아온 파일을 실행하면 R2 알림이 나간다 | `DetectionTopologyTest.downloadExecute_emitsAlert` | `docs/product-specs/README.md` R2 | 자동 |
| DET-2 | 네트워크 이벤트가 실행보다 늦게 도착해도 R2 가 성립한다 | `DetectionTopologyTest.downloadExecute_networkArrivesLate_emitsAlert` | `docs/design-docs/decisions/006-event-time-ordering.md` | 자동 |
| DET-3 | 도착 순서가 바뀌어도 룰별 집계가 같다 | `DetectionTopologyTest.alertCounter_isIndependentOfArrivalOrder` | `docs/design-docs/decisions/006-event-time-ordering.md` | 자동 |
| DET-4 | 윈도우(5분) 밖의 조합은 알림이 아니다 | `DetectionTopologyTest.outsideWindow_noAlert` | `README.md` "Kafka Streams" | 자동 |
| DET-5 | 다른 host 의 이벤트끼리는 상관되지 않는다 | `DetectionTopologyTest.differentHosts_noAlert` | `docs/design-docs/decisions/001-kafka-between-collection-and-detection.md` | 자동 |
| DET-6 | 같은 시퀀스로 알림이 두 번 나가지 않는다 | `DetectionTopologyTest.sequenceAlert_emittedOnce` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| DET-7 | 늦게 온 트리거도 세고 판정에서 빠지지 않는다 | `DetectionTopologyTest.lateTrigger_isCountedAndStillEvaluated` | `docs/design-docs/decisions/002-kafka-streams-processor-api.md` | 자동 |
| DET-8 | 조용해진 host 의 대기 판정이 flush 된다 | `DetectionTopologyTest.quietHost_pendingIsFlushed` | `docs/design-docs/failures/007-punctuator-publishes-outside-transaction.md` | 자동 |
| DET-9 | 재시작해도 상관 버퍼가 복원된다 | `CorrelationProcessorRestartTest` 전체 | `docs/design-docs/decisions/002-kafka-streams-processor-api.md` | 자동 |
| DET-10 | Streams 가 처리 불가 상태면 health 가 DOWN 이다 | `KafkaStreamsHealthIndicatorTest.notProcessing_down` | `docs/design-docs/failures/008-streams-error-but-health-up.md` | 자동 |

## 알림 억제

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| SUP-1 | 쿨다운 창 안의 같은 키는 억제된다 | `CooldownTest.withinWindow_suppressed` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| SUP-2 | 창이 지나면 다시 나간다 | `CooldownTest.afterWindow_allowed` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| SUP-3 | 다른 키는 서로 영향을 주지 않는다 | `CooldownTest.differentKeys_independent` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| SUP-4 | 같은 키로 동시에 들어와도 하나만 통과한다 | `CooldownTest.concurrentSameKey_onlyOnePasses` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |

## 테넌트 격리

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| TEN-1 | tenant 없이 조회 질의를 만들 수 없다 | `TenantScopeTest` 의 `tenant_없이_만드는_공개_경로가_없다` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| TEN-2 | tenant 조건이 항상 첫 조건으로 붙는다 | `TenantScopeTest` 의 `tenant_는_항상_첫_조건이고_값은_trim_해서_바인딩된다` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |

## 적재

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| ING-1 | events 가 적재일 단위로 파티션된다 | `ClickHouseWriterTest` 의 `신규_테이블은_적재일_단위로_파티션된다` | `docs/design-docs/decisions/014-partition-by-ingest-day.md` | 자동 |
| ING-2 | 만료된 파트를 통째로 버린다 | `ClickHouseWriterTest` 의 `만료된_파트를_통째로_버리게_설정한다` | `docs/design-docs/decisions/014-partition-by-ingest-day.md` | 자동 |
| ING-3 | 롤업이 SummingMergeTree 이고 180일 보관된다 | `ClickHouseWriterTest` 의 `롤업은_SummingMergeTree_다` | `docs/design-docs/decisions/015-hourly-rollup.md` | 자동 |
| ING-4 | 리스너가 실제 레코드를 받아 적재로 넘어간다 | `AlertIngestListenerKafkaTest` 전체 | `docs/design-docs/failures/001-batch-listener-native-headers.md` | 자동 |

## 수동 확인

| ID | 확인할 것 | 방법 | 상태 |
|---|---|---|---|
| DET-M1 | Windows 실기기에서 ETW 센서가 이벤트를 낸다 | `agent/README.md` 의 Windows 실기기 검증 절차 | 미구현 |
| DET-M2 | 실제 단말에서 R2 가 끝까지 흐른다 | 에이전트 설치 후 다운로드·실행 시나리오를 돌려 Slack 알림까지 확인 | 수동 |
