# 대응 (PRD: `docs/product-specs/service.md` F5)

판정에서 프로세스 종료까지. 왜 이렇게 정했는지는 `docs/design-docs/decisions/007`, `016`.

## 요청과 응답

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| RSP-1 | kill 요청이 기다리지 않고 바로 PENDING 으로 돌아온다 | `ResponseExecutorTest.kill_returnsPendingImmediately` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-2 | 보고 전에는 PENDING 이다 | `ResponseExecutorTest.result_pendingBeforeReport` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-3 | 종료되면 KILLED 다 | `ResponseExecutorTest.result_killed` | `docs/product-specs/service.md` F5 | 자동 |
| RSP-4 | 대상을 못 찾으면 NO_MATCH 다 | `ResponseExecutorTest.result_noMatch` | `docs/product-specs/service.md` F5 | 자동 |
| RSP-5 | 모르는 id 조회는 구분되어 돌아온다 | `ResponseExecutorTest.result_unknownId` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-6 | 큐가 실패하면 FAILED 로 돌아온다 | `ResponseExecutorTest.queueError_returnsFailed` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-7 | 대응이 꺼져 있으면 명령을 만들지 않는다 | `ResponseExecutorTest.disabled_noCommand` | `docs/design-docs/decisions/007-fixed-command-types.md` | 자동 |

## 상태 전이와 신뢰

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| RSP-8 | 시한이 지나면 TIMEOUT 으로 조회된다 (저장하지 않고 조회 시각으로 판단) | `ResponseExecutorTest.result_timesOut` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-9 | 시한이 지난 명령은 에이전트에게 내려가지 않는다 | `ResponseExecutorTest.timedOutCommandIsNeverDelivered` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-10 | 에이전트가 모르는 상태를 보고하면 FAILED 로 받는다 | `ResponseExecutorTest.result_unknownStatusIsFailed` | `docs/design-docs/decisions/007-fixed-command-types.md` | 자동 |
| RSP-11 | 서버만 붙일 수 있는 상태는 에이전트 보고로 받지 않는다 | `KillOutcomeTest.serverOnlyStatusesAreRejected` | `docs/design-docs/decisions/007-fixed-command-types.md` | 자동 |
| RSP-12 | 정해진 상태만 받는다 | `KillOutcomeTest.knownStatuses` | `docs/design-docs/decisions/007-fixed-command-types.md` | 자동 |

## 명령 큐 (여러 인스턴스)

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| RSP-13 | 같은 명령이 한 번만 내려간다 | `CommandQueueTest.drainIsOnce` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-14 | 인스턴스가 여럿이어도 큐를 공유한다 | `CommandQueueTest.sharedAcrossInstances` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-15 | host 끼리 명령이 섞이지 않는다 | `CommandQueueTest.hostsAreIsolated` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-16 | 같은 결과를 두 번 보고해도 한 번만 반영된다 | `CommandQueueTest.secondReportIgnored` | `docs/design-docs/decisions/007-fixed-command-types.md` | 자동 |
| RSP-17 | 내려가기 전에 온 완료 보고는 무시한다 | `CommandQueueTest.completeBeforeDeliveryIgnored` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-18 | 만료된 명령은 drain 되지 않는다 | `CommandQueueTest.expiredIsNotDrained` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-19 | 긴 메시지는 잘라서 저장한다 | `CommandQueueTest.longMessageTruncated` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |
| RSP-20 | 쿨다운이 인스턴스 사이에 공유된다 | `ResponseExecutorTest.cooldown_sharedAcrossInstances` | `docs/design-docs/decisions/016-async-kill-command.md` | 자동 |

## 권고 (dry-run)

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| RSP-21 | kill 권고가 사람이 읽을 문장으로 나온다 | `ResponsePlannerTest.kill_mapsToText` | `docs/product-specs/service.md` F5 | 자동 |
| RSP-22 | 같은 host·action 은 쿨다운 안에서 억제된다 | `ResponsePlannerTest.sameHostAction_withinCooldown_suppressed` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| RSP-23 | 같은 host 라도 action 이 다르면 따로 보여준다 | `ResponsePlannerTest.sameHost_differentAction_shown` | `docs/product-specs/service.md` F5 | 자동 |

## 수동 확인

| ID | 확인할 것 | 방법 | 상태 |
|---|---|---|---|
| RSP-M1 | 실제 단말에서 프로세스가 죽는다 | 테스트 프로세스를 띄우고 대시보드에서 실행 → KILLED 확인 | 수동 |
| RSP-M2 | 자기 자신과 PID 1 은 죽이지 않는다 | 에이전트 자신과 launchd 를 대상으로 지정해 거부되는 것을 확인 | 수동 |
| RSP-M3 | 격리(isolate)가 동작한다 | 구현되면 작성한다 | 미구현 |
