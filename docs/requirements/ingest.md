# 수집과 등록 (PRD: `docs/product-specs/service.md` F1, F2)

에이전트가 보낸 것이 검증을 거쳐 `events` 토픽에 들어가기까지. 왜 이렇게 정했는지는 `docs/design-docs/decisions/004`, `008`, `010`, `011`.

## 발행 계약

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-1 | 발행 본문이 Event 그 자체다 (필드명을 싣지 않는다) | `EventsProducerTest.bodyIsTheEventItself` | `docs/design-docs/decisions/010-protobuf-on-events-topic.md` | 자동 |
| AGT-2 | 파티션 키가 host 다 | `EventsProducerTest.partitionKeyIsHost` | `docs/design-docs/decisions/001-kafka-between-collection-and-detection.md` | 자동 |
| AGT-3 | 레코드 헤더에 스키마 버전·이벤트 타입·tenant 가 찍힌다 | `EventsProducerTest.headersAreStamped` | `docs/design-docs/decisions/010-protobuf-on-events-topic.md` | 자동 |
| AGT-4 | tenantId 가 발행을 건너도 살아남는다 | `EventsProducerTest.tenantIdSurvivesPublishing` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |

## 검증과 정규화

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-5 | 4종 이벤트가 각각 정규화된다 | `RawEventMapperTest` 전체 | `docs/product-specs/service.md` F1 | 자동 |
| AGT-6 | 시계가 틀어진 단말의 이벤트를 버리지 않고 당긴다 | `RawEventMapperTest` 의 ts 관련 케이스 | `docs/design-docs/decisions/006-event-time-ordering.md` | 자동 |
| AGT-7 | 검증 실패한 한 건이 배치 전체를 실패시키지 않는다 | `RawEventMapperTest` 전체 | `README.md` "1. Endpoint" | 자동 |
| AGT-8 | 이벤트에 tenant 가 태깅된다 | `EventTaggerTest` 전체 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |

## 등록과 인증

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-9 | enroll → heartbeat → events 가 끝까지 흐른다 | `AgentIngestIntegrationTest` 전체 | `docs/agent-protocol.md` | 자동 |
| AGT-10 | gzip 으로 압축된 요청 본문을 풀어서 받는다 | `AgentIngestIntegrationTest` 의 gzip 케이스 | `docs/design-docs/decisions/013-compression-by-segment.md` | 자동 |
| AGT-11 | 압축 해제에 상한이 있어 큰 본문을 거부한다 | `GzipRequestFilter.MAX_DECOMPRESSED_BYTES` (8MB) 상수와 `AgentIngestIntegrationTest` | `docs/design-docs/decisions/013-compression-by-segment.md` | 자동 |
| AGT-12 | 틀린 node_key 는 거부된다 | `AgentIngestIntegrationTest` 의 node_key 케이스 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| AGT-13 | 같은 (tenant, host) 로 두 번 등록해도 행이 하나다 | DB 유니크 제약 + `AgentIngestIntegrationTest` | `docs/design-docs/failures/005-enroll-race-duplicate-rows.md` | 자동 |

## 센서 설정

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-14 | 플랫폼별로 켤 센서가 갈린다 | `SensorConfigTest` 전체 | `docs/design-docs/decisions/008-own-go-agent.md` | 자동 |
| AGT-15 | 켤 센서가 하나도 없으면 에이전트가 기동을 거부한다 | `agent/README.md` 의 기동 절차. 조용히 0건으로 도는 것을 막는다 | `docs/design-docs/decisions/008-own-go-agent.md` | 자동 |

## 수동 확인

| ID | 확인할 것 | 방법 | 상태 |
|---|---|---|---|
| AGT-M1 | macOS 실기기에서 4종 이벤트가 올라온다 | 에이전트 설치 후 전체 디스크 접근 권한을 켜고 `-selftest` 로 확인 | 수동 |
| AGT-M2 | Windows 실기기에서 ETW 센서가 이벤트를 낸다 | `agent/README.md` 의 Windows 실기기 검증 절차 | 미구현 |
| AGT-M3 | 인증서 고정이 실제로 다른 서버를 거부한다 | 다른 인증서로 서명된 서버를 가리켜 붙지 않는 것을 확인 | 수동 |
