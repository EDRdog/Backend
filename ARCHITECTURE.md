# Architecture

수집 → 판정 → 대응·저장·조회로 흐르는 파이프라인이다. 각 단계는 Kafka 토픽으로 끊겨 있고, 서로의 속도에 영향을 주지 않는다.

설계의 서술형 근거(왜 Kafka 인가, 왜 순서를 이렇게 보장하는가 등)는 `README.md` 에 있다. 이 문서는 그 구조를 코드 위치와 의존 방향으로 옮긴 것이고, 개별 결정의 배경은 `docs/design-docs/decisions/` 에 있다.

## 레이어

파이프라인 단계가 곧 레이어다. 모듈 하나가 단계 하나를 맡는다.

| 단계 | 모듈 | 책임 |
|---|---|---|
| 수집 | `agent/` (Go) | 커널 이벤트 구독, 버퍼링, 서버 전송, 조치 실행 |
| 입구 | `collector-service` | 에이전트 인증, tenant 태깅, 검증. 통과한 것만 `events` 로 발행 |
| 판정 | `detector-service` | Kafka Streams 로 host 별 상관분석. `alerts` 발행 |
| 알림 | `alert-service` | `alerts` 소비 → tenant 별 Slack webhook |
| 대응 | `responder-service` | `alerts` 소비(dry-run 권고) + kill 명령 큐를 MySQL 에 보관 |
| 저장 | `archiver-service` | `events`·`alerts` 소비 → ClickHouse 적재, S3 내보내기 |
| 조회 | `api-service` | 프론트용 읽기 API, 인증, tenant·알림 설정 |
| 계약 | `event-schema` | `events` 토픽 스키마(.proto)와 Kafka·관측 유틸. 서비스가 아니라 라이브러리 |

서비스 안쪽은 Spring 의 일반적인 controller → service → repository 다. 판정만 예외로 Kafka Streams 토폴로지(`kafkastreams/topology/`)가 진입점이다.

## 의존 방향

**토픽을 거치는 것이 기본이고, 직접 호출은 예외다.** 예외는 아래 넷뿐이다.

```
Go Agent ──HTTPS──▶ collector ──[events]──▶ detector ──[alerts]──┬──▶ alert ──▶ Slack
                        │                                        ├──▶ responder
                        │                                        └──▶ archiver ──▶ ClickHouse ──▶ S3
                        ▼
                    api-service ◀── 프론트엔드
```

| 직접 호출 | 방향 | 이유 |
|---|---|---|
| tenant 해석 | collector → api-service | enroll 1회만. `X-Internal-Key` |
| 대기 명령 조회·결과 중계 | collector → responder | 에이전트 하트비트에 실어 보낼 명령을 가져온다 |
| webhook 주소 조회 | alert → api-service | tenant 별 Slack 주소. `X-API-Key`, 60초 메모리 캐시 |
| kill 요청·조회 | api-service → responder | 대시보드의 실행 버튼. api-service 가 인증·소유 검증을 맡는 게이트다 |

지켜야 할 방향:

- **detector 는 아무 저장소도 쓰지 않는다.** 상태는 Kafka Streams state store(RocksDB + changelog)뿐이다. DB 를 붙이면 판정이 외부 가용성에 묶인다.
- **ClickHouse 쓰기는 archiver 만 한다.** api-service 는 읽기만 한다. 스키마 출처가 둘이 되면 드리프트가 생긴다.
- **responder 에는 앱 레벨 인증이 없다.** 접근 통제는 api-service 프록시의 세션 인증과 tenant 소유 검증이 전부다. responder 를 외부에 직접 노출하면 안 된다.
- **`events` 를 소비하는 쪽은 서로 다른 컨슈머 그룹을 쓴다**(`detector`, `archiver`). 판정과 저장이 서로를 기다리지 않는다.
- event-schema 는 여섯 서비스가 전부 참조한다. 여기를 고치면 전 모듈을 다시 구워야 한다(CD 의 공통 파일 목록에 들어 있는 이유).

## 핵심 도메인

| 도메인 | 위치 | 한 줄 |
|---|---|---|
| Event | `event-schema/src/main/proto/edrdog/v1/event.proto` | 토픽 계약. 필드 1~12, 16번 미만으로 묶어 태그를 1바이트로 유지 |
| 룰과 판정 | `detector-service/.../rule/`, `kafkastreams/topology/CorrelationProcessor.java` | R1~R4 시퀀스 룰, host 별 5분 윈도우, MITRE 기법 매핑 |
| 상관 버퍼 | `detector-service/.../event_buffer.proto` | detector 내부 상태 스키마. 토픽 계약이 아니다 |
| 대응 명령 | `responder-service/.../command/AgentCommand` | 정해진 종류만(`kill_process`). 상태는 조건부 UPDATE 로만 전이 |
| 테넌트 격리 | `api-service/.../security/TenantScope.java` | 모든 ClickHouse 질의에 tenant 조건을 강제하는 단일 통로 |
| 노드 등록 | `collector-service/.../agent/AgentNode` | node_key 는 SHA-256 해시로만 저장 |
| 적재 스키마 | `archiver-service/.../clickhouse/ClickHouseWriter.java` | `events`, `alerts`, 롤업 `events_hourly` DDL 의 단일 출처 |
| 관측 연결 | `event-schema/.../KafkaTraceLink.java`, `TraceAttribute.java` | 벤더 API 를 쓰는 유일한 두 자리. 백엔드를 옮기면 여기만 걷어낸다 |

## 외부 연동

| 대상 | 쓰는 쪽 | 용도 |
|---|---|---|
| Kafka | 전 서비스 | `events`(3파티션, key=host, Protobuf), `alerts`(3파티션, JSON). 복제 1, 브로커 1대 |
| ClickHouse | archiver(쓰기), api-service(읽기) | `edrdog.events`(TTL 7일), `edrdog.alerts`(ReplacingMergeTree, TTL 90일), `edrdog.events_hourly`(롤업, 180일) |
| MySQL | collector, responder, api-service | 노드 등록, 명령 큐, 계정·tenant·알림 상태. 서비스마다 스키마를 따로 둔다 |
| S3 / MinIO | archiver | 만료 전 원본을 Parquet 으로 내보낸다. 기본 OFF(`ARCHIVE_ENABLED`) |
| Slack Incoming Webhook | alert-service | tenant 별 알림. 전역 폴백은 두지 않는다 |
| New Relic | 전 서비스 | 운영 관측. 자바 에이전트가 전부 맡고 앱의 OTLP 는 꺼져 있다 |
| otel-lgtm (Tempo/Loki/Prometheus) | 전 서비스 | 로컬 전용 관측 |
| MaxMind GeoLite2 | api-service | dest_ip → 국가코드. 빌드 시 번들, 운영은 볼륨에서 읽는다 |
| Infisical | k8s | 시크릿 주입. Operator 가 있을 때만 적용된다 |

Redis 는 쓰지 않는다. 알림 쿨다운은 파티셔닝 덕분에 인스턴스 로컬 메모리로 충분하다(`docs/design-docs/decisions/004-*`).
