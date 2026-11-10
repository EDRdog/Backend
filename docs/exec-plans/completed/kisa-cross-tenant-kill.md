# 교차 tenant kill 경로 막기 (KISA 점검 H1·H2·H3)

근거: `docs/security/kisa-2021-review.md`. 세 고리가 이어져 있다. 남의 host 이름으로 알림을 만들고(H2 또는 데모 API), 아무 target 을 넣어 respond 하면(H3), tenant 없이 host 로만 찾는 명령 큐가(H1) 다른 tenant 의 같은 이름 기기에 명령을 준다.

## 할 일

1. H1: kill 명령에 tenantId 를 싣는다.
   - api-service `ResponderClient` 의 kill 요청에 tenantId 를 넣는다.
   - responder `AgentCommand` 에 tenantId 를 저장하고, 대기 명령 조회와 결과 보고를 `(tenantId, host)` 로 한다.
   - collector 하트비트와 결과 보고가 인증된 노드의 tenantId 를 넘긴다. 결과 보고는 명령의 tenant·host 가 보고한 노드와 같을 때만 반영한다.
   - responder 가 알림을 받아 스스로 명령을 넣는 경로가 있으면 거기도 알림의 tenantId 를 쓴다.
2. H2: collector 가 이벤트의 `host` 를 인증된 노드의 `hostIdentifier` 로 덮어쓴다. tenantId 를 덮어쓰는 것과 같은 자리다.
3. H3: respond 의 종료 대상을 요청 본문이 아니라 알림에서 꺼낸다. 알림에 대상이 없으면 거절한다.
4. `docs/agent-protocol.md`, `docs/requirements/response.md`, `docs/exec-plans/tech-debt.md` 를 같은 PR 에서 고친다.

## 하지 않는 것

- detector 상태 키를 `tenant|host` 로 바꾸지 않는다. 파티션 키가 host 인 이유가 README 에 있고, 바꾸면 파티션 배치가 달라진다. 서로 다른 tenant 에 같은 host 이름이 있으면 detector 상태가 섞이는 문제는 tech-debt 로 남긴다.
- 운영 `DEMO_SEED` 는 그대로 둔다. 데모 계정은 일부러 공개한 것이다(2026-11-10 사용자 결정).
- 하위 호환: tenantId 가 없는 옛 명령 행은 조회에 걸리지 않게 둔다. 실사용자가 없어 대기 중인 옛 명령을 살릴 이유가 없다.

## 결과 (2026-11-10)

계획대로 됐다. 계획에 없던 것 셋이 붙었다.

- **H3 의 대상을 판정 기록에 저장했다.** detector 의 `Alert.target` 은 있었지만 archiver 가 버리고 있어서 ClickHouse `alerts` 에 대상 칸이 없었다. 그대로 두면 모든 respond 가 400 이 되어 kill 버튼이 통째로 죽는다. `AlertClickHouseWriter.ensureSchema()` 의 `ADDED_COLUMNS` 에 `target String` 을 넣고(손으로 ALTER 하지 않는다), api-service 가 그 칸을 읽는다. 칸이 생기기 전 알림은 대상이 비어 400 이다.
  - 버린 대안: `matched` 요약에서 대상을 거꾸로 뽑기. process 요약은 `process 이름 (parent 부모)` 라 cmdline 이 없어 detector 의 `actTarget`(cmdline 우선)을 되살릴 수 없다.
  - 버린 대안: 상세 조회의 `sourceEvent` 에서 뽑기. 같은 종류 이벤트가 여럿이면 시각으로 고른 추정이라, 엉뚱한 프로세스를 죽일 수 있다.
- **쿨다운도 (tenant, host) 로 센다.** 명령 저장소를 host 로만 세면 다른 조직의 같은 이름 기기에 나간 명령이 이쪽 조치를 60초 막는다.
- **결과 조회가 tenant 도 맞춰 본다.** responder 의 결과에 tenantId 를 싣고 api-service 가 알림의 tenant·host 와 둘 다 비교한다.

responder 가 `alerts` 를 받아 스스로 명령을 넣는 경로는 없었다. `AlertListener` 는 권고를 로그로만 남긴다.

collector 의 결과 보고 중계는 보고한 노드의 tenant·host 를 본문(`tenant_id`, `host`)에 싣고, responder 가 명령의 대상과 다르면 버린다. 에이전트 쪽 계약은 바뀌지 않았다.
