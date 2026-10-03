# 조회와 설정 (PRD: `docs/product-specs/service.md` F6, F7)

대시보드가 쓰는 읽기 API 와 조직 설정. 왜 이렇게 정했는지는 `docs/design-docs/decisions/005`, `015`.

## 조직 격리

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| QRY-1 | tenant 없이 조회 질의를 만들 수 없다 | `TenantScopeTest` 전체 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| QRY-2 | 이벤트 질의에 tenant 조건이 붙는다 | `EventQueryBuilderTest` 의 tenant 케이스 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| QRY-3 | 알림 질의에 tenant 조건이 붙는다 | `AlertQueryBuilderTest` 의 tenant 케이스 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| QRY-4 | 검색·토폴로지·상관 질의에도 tenant 조건이 붙는다 | `SearchQueryBuilderTest`, `TopologyQueryBuilderTest`, `CorrelateQueryBuilderTest` 의 tenant 케이스 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| QRY-5 | 호스트 위험도 질의에 tenant 조건이 붙는다 | `HostRiskQueryBuilderTest` 의 tenant 케이스 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| QRY-6 | 추세(롤업) 질의에도 tenant 조건이 붙는다 | `EventTrendQueryBuilderTest` 의 tenant 케이스 | `docs/design-docs/decisions/015-hourly-rollup.md` | 자동 |

## 페이지네이션

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| QRY-7 | limit 과 offset 에 상한이 있다 | `QueryGuardsTest` 전체 | `docs/product-specs/service.md` 비기능 요건 | 자동 |
| QRY-8 | 상한을 넘는 offset 은 400 이다 | `EventPaginationApiIntegrationTest` 전체 | `docs/product-specs/service.md` 비기능 요건 | 자동 |
| QRY-9 | 알림 목록도 같은 상한을 쓴다 | `AlertPaginationApiIntegrationTest` 전체 | `docs/product-specs/service.md` 비기능 요건 | 자동 |
| QRY-10 | 인시던트 목록도 같은 상한을 쓴다 | `IncidentPagingTest` 전체 | `docs/product-specs/service.md` 비기능 요건 | 자동 |

## 롤업 조회

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| QRY-11 | 추세 질의가 롤업 테이블을 읽는다 | `EventTrendQueryBuilderTest` 전체 | `docs/design-docs/decisions/015-hourly-rollup.md` | 자동 |
| QRY-12 | 병합 전 행이 남아 있어도 sum 으로 접어 읽는다 | `EventTrendQueryBuilderTest` 전체 | `docs/design-docs/decisions/015-hourly-rollup.md` | 자동 |

## 알림 조회와 트리아지

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| QRY-13 | 판정 기록 위에 MySQL 처리 상태를 겹쳐 보여준다 | `AlertServiceTest` 의 triage 케이스 | `docs/design-docs/decisions/005-clickhouse-and-mysql-split.md` | 자동 |
| QRY-14 | 알림 계보(어떤 이벤트가 근거인지)를 준다 | `LineageGraphBuilderTest` 전체 | `docs/product-specs/service.md` F7 | 자동 |
| QRY-15 | 대응 실행 요청을 responder 로 넘긴다 | `AlertApiIntegrationTest` 의 respond 케이스 | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| QRY-16 | responder 조회 실패는 삼키지 않고 올린다 | `ResponderClientTest` 전체 | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |

## 지도와 호스트

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| QRY-17 | 사설 IP 는 국가 집계에서 뺀다 | `PrivateIpTest` 전체 | `docs/product-specs/service.md` F7 | 자동 |
| QRY-18 | 링크 로컬 주소도 뺀다 | `PrivateIpTest` 전체 | `docs/product-specs/service.md` F7 | 자동 |
| QRY-19 | GeoIP DB 가 없으면 기동을 막지 않고 비활성으로 둔다 | `GeoAggregatorTest` 전체 | `docs/design-docs/decisions/011-collector-as-ingest-entrypoint.md` | 자동 |
| QRY-20 | 호스트 요약이 심각도별로 집계된다 | `HostAggregatorTest` 전체 | `docs/product-specs/service.md` F7 | 자동 |

## 운영 상태

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| QRY-21 | 의존성이 멀쩡하면 기본이 정상이다 | `OperationsHealthApiIntegrationTest.healthyByDefault` | `docs/design-docs/failures/008-streams-error-but-health-up.md` | 자동 |
| QRY-22 | 브로커가 죽어 있어도 예외 대신 오류 결과로 감싼다 | `DependencyStatusesTest` 전체 | `docs/design-docs/failures/008-streams-error-but-health-up.md` | 자동 |

## 인증과 설정

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| QRY-23 | 인증 없이 조회 API 에 닿을 수 없다 | `ApiKeyPolicyTest` 전체 | `docs/product-specs/service.md` F7 | 자동 |
| QRY-24 | 내부 전용 경로는 X-API-Key 로만 열린다 | `ApiKeyPolicyTest` 전체 | `docs/design-docs/decisions/011-collector-as-ingest-entrypoint.md` | 자동 |
| QRY-25 | 조직 설정 조회·변경이 조직 경계를 넘지 않는다 | `TenantServiceTest` 전체 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| QRY-26 | enroll secret 은 못 찾은 이유를 알려주지 않는다 | `TenantServiceTest` 의 getEnrollSecret 케이스 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| QRY-27 | 설치 스크립트가 플랫폼별로 나간다 | `InstallTemplatesTest` 전체 | `docs/product-specs/service.md` F2 | 자동 |

## 수동 확인

| ID | 확인할 것 | 방법 | 상태 |
|---|---|---|---|
| QRY-M1 | 다른 조직 계정으로 로그인했을 때 남의 데이터가 안 보인다 | 조직 2개로 계정을 만들어 알림·이벤트 목록을 교차 확인 | 수동 |
| QRY-M2 | 위협 지도에 국가가 찍힌다 | 외부 IP 로 나가는 이벤트를 만들고 지도 확인 | 수동 |
