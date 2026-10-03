# 기술부채

알고 있으면서 아직 안 고친 것. 고치면 줄을 지우고, 고치기로 결정하면 `active/` 에 계획을 쓴다.

## 유실·가용성

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| Kafka 파드에 영속 볼륨이 없다 | `k8s/kafka.yaml` | 파드가 새로 뜨면 토픽과 `__consumer_offsets` 가 통째로 사라진다. 실제로 테이블 교체 때 offset 0 재소비로 이어졌다 | 2026-08-24 |
| 브로커 1대, 복제 계수 1 | `k8s/kafka.yaml` | 컨슈머 장애는 견디지만 브로커 장애는 대비돼 있지 않다. 의도된 선택이지만 한계는 한계다 | 2026-08-24 |
| 에이전트 송신 상한이 단말당 초당 100건 | `agent/internal/runtime/runtime.go` | 5초마다 `Drain(500)` 한 배치만 보낸다. 공격 피크(약 480건/초)면 버퍼 1만 건이 약 26초 뒤 넘쳐 오래된 것부터 버린다. 병목이 브로커가 아니라 에이전트다 | 2026-09-23 |
| 서버 쪽 발행 실패가 재전송되지 않는다 | collector `EventsProducer.publish` | `template.send` 를 비동기로 던지고 200 을 준다. 에이전트는 응답의 accepted 를 안 본다 | 2026-09-23 |
| 전송이 막히면 수집도 멈춘다 | `agent/internal/runtime/runtime.go` | flush 가 Run 루프에서 동기로 돈다(HTTP 타임아웃 5초 = FlushInterval). 막히면 채널(500)이 약 1분 안에 차고 센서가 멈춰 OS 단에서 유실된다 | 2026-09-23 |
| S3 아카이빙이 기본 OFF | `archiver-service` `ARCHIVE_ENABLED` | 운영에서 꺼져 있어 TTL 7일이 지난 원본은 복구할 수 없다 | 2026-10-04 |

## 성능

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| alerts 조회가 `FINAL` 을 쓴다 | `api-service/.../host/HostRiskQueryBuilder.java` | ReplacingMergeTree 에 `FINAL` 은 조회마다 중복 제거 병합을 강제한다. alerts 가 쌓이면 여기가 먼저 느려진다. 중복 삽입이 실제로 있는지 보고, 없으면 떼고 있으면 `argMax` 로 바꾼다 | 2026-08-24 |
| actuator 프로브가 트랜잭션으로 잡힌다 | 뉴렐릭 설정 | 서비스당 시간 500건대가 트랜잭션으로 들어가 인제스트를 소모한다. 현재 7GB / 월 100GB 라 여유가 있어 미뤘다. 3GB/일을 넘으면 손본다 | 2026-08-24 |

## 미구현·미검증

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 격리(isolate)가 구현돼 있지 않다 | `ACTION_ISOLATE` 상수만 존재 | 대응 수단이 프로세스 종료 하나뿐이다. 관리 채널 예외와 자동 해제 타이머가 함께 있어야 안전해서 미뤘다 | 2026-10-04 |
| Windows ETW 센서가 실기기에서 검증되지 않았다 | `agent/internal/sensor/etw_windows.go`, `packaging/install-windows.ps1` | 크로스 컴파일과 단위 테스트까지만 통과했다. 확인 목록은 `agent/README.md` | 2026-10-04 |
| EOS 를 켰을 때의 비용이 측정되지 않았다 | detector | 측정 인프라만 넣어 두고 켜 보지는 않았다. 켜자는 얘기가 나오면 먼저 재야 한다 | 2026-10-04 |

## 문서·구조

| 항목 | 위치 | 영향 | 발견일 |
|---|---|---|---|
| 하위 모듈 CLAUDE.md 가 코드와 어긋난다 | `alert-service/CLAUDE.md`, `responder-service/CLAUDE.md` | "미구현/스켈레톤" 이라고 적혀 있는데 실제로는 구현돼 있다. 읽은 사람이 틀린 전제로 출발한다. 지금은 gitignore 로 추적에서 빠져 있다 | 2026-10-04 |
| 빈 껍데기 디렉터리가 남아 있다 | `archiver/`, `detector/`, `responder/`, `api-server/` | 빌드 산출물만 있고 실제 모듈이 아니다. 모듈로 착각하기 쉽다 | 2026-10-04 |
| 자바 쪽에 린터가 없다 | 루트 `build.gradle` | spotless·checkstyle 설정이 없어 `scripts/check.sh` 의 lint 자리가 Go `vet` 뿐이다 | 2026-10-04 |

## 측정값 전제

ClickHouse 파티션(#180)과 롤업(#298) 수치는 **로컬 docker 단일 인스턴스 + 합성 데이터**다. 운영 실측이 아니다. 문서나 이력서에 쓰려면 이 전제를 같이 말해야 한다. 운영 수치가 필요하면 같은 질의를 운영에서 다시 돌려야 한다.
