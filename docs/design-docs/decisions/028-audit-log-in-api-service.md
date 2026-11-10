# 028. 감사기록은 api-service 의 MySQL 테이블 하나에 남기고, 진입점은 AuditService.record 하나로 둔다

- 날짜: 2026-11-11
- 상태: 채택

## 배경
KISA EDR-BF-13.1 은 서버가 주요 감사사건을 기록하고 인가된 관리자가 조회하게 하라고 한다. BF-13.2 는 에이전트가 만든 감사기록이 서버로 오게 하라고 한다. 지금은 감사 테이블도 조회 API 도 없다(`docs/security/nis-requirements-review.md` 서버 7.1~7.3). 이어지는 묶음(인증, 접근통제, 서버 자체점검)이 같은 서비스에서 사건을 남겨야 하므로 부르기 쉬운 단일 진입점이 필요하다.

## 결정
- 저장은 api-service 의 MySQL 테이블 `audit_log`(JPA 엔티티 `AuditRecord`, `ddl-auto: update`)다. 칸은 시각, tenantId, 행위자 종류(user, agent, system), 행위자 식별(유저는 이메일과 유저 id, 에이전트는 host), 유형, 대상, 결과(success, failure), 상세(500자), 접속 IP 다.
- 남기는 입구는 `AuditService.record(AuditEntry)` 하나다. 사건 유형은 `AuditType` 상수(소문자 snake_case 문자열)로 모은다. 다른 묶음이 쓸 유형(패스워드 변경, 연속 인증 실패 잠금, 보안정책 변경, 관리 IP 차단, 세션 잠금·종료, 자체시험 결과, 무결성 검증 결과)도 상수만 미리 뒀다.
- `record` 는 실패해도 던지지 않고 로그만 남긴다(기록 실패가 로그인이나 kill 요청을 깨면 안 된다). 호출한 쪽 트랜잭션과 따로 커밋(`REQUIRES_NEW`)해서, 기록 쪽 오류가 호출자의 트랜잭션을 롤백 전용으로 만들지 않게 한다. 대신 본 작업이 커밋된 뒤에 부른다. 가입 기록을 컨트롤러에서 남기는 이유다.
- 에이전트 기록을 받는 `AuditService.recordAll` 은 반대로 실패하면 던진다. 에이전트가 이미 만든 사건은 잃으면 안 되므로 호출자(collector)가 재시도하게 한다.
- 수정·삭제는 코드에도 API 에도 없다. 저장소는 `JpaRepository` 가 아니라 `Repository` 를 상속해 `save`, `saveAll` 만 둔다. 엔티티에는 setter 가 없고 `@Immutable` 이다. 이 구조는 `AuditServiceTest.auditCodeHasNoUpdateOrDeletePath` 가 고정한다.
- 조회 `GET /api/audit` 는 세션 Bearer 로 인증하고 같은 tenant 의 `admin` 역할만 읽는다(아니면 403). 이 경로는 `ApiKeyPolicy` 의 예외 목록에 있다. 필터는 기간(양끝 포함), 유형, 행위자, 결과, 정렬은 시각 오름·내림, 페이지는 알림 목록과 같은 limit·offset·응답 헤더 방식이다(`QueryGuards` 상한 재사용).
- 에이전트 기록 수신은 `POST /api/internal/audit` 이고 `X-Internal-Key` 로 인증한다. tenantId 와 host 는 collector 가 node_key 로 푼 값이라 에이전트가 우길 수 없다. 한 번에 100건, 형식이 틀린 건이 하나라도 있으면 배치 전체를 거절한다. 계약은 `docs/agent-protocol.md` 에 있다.
- 비밀값은 어떤 칸에도 넣지 않는다. 가입 안 된 이메일로 로그인을 시도하면 입력값을 남기지 않고 행위자를 `unknown` 으로 적는다(이메일 칸에 비밀번호를 잘못 붙여 넣는 일이 있다). enroll 은 시크릿 값 없이 결과만 남긴다.

## 근거
**MySQL 을 고른 이유**: 계정, tenant, 알림 상태가 이미 api-service 의 MySQL 에 있고(ADR 005), 감사기록은 행위자와 같은 트랜잭션 경계에서 읽고 쓰는 소량의 관리 데이터다. ClickHouse 는 archiver 만 쓴다는 방향(`ARCHITECTURE.md`)과 맞지 않고, TTL 로 기록이 사라지는 점도 감사에 맞지 않는다.

**진입점을 하나로 둔 이유**: 사건마다 저장소를 직접 부르면 길이 상한, 예외 삼키기, 시각 채우기가 호출처마다 달라진다. 한곳에 두면 다음 묶음은 `AuditEntry.user(...)` 한 줄이다.

**`@Aspect` 나 이벤트 발행을 버린 이유**: 어떤 사건을 남길지가 호출처마다 다르다(결과, 대상, 상세). 횡단 관심사로 가려 놓으면 무엇이 남는지 코드에서 안 보이고, 한 곳에서 한 줄로 쓰는 것보다 얻는 것이 없다.

**에이전트 기록을 events 토픽으로 보내지 않은 이유**: 감사기록은 detector 상관분석 대상이 아니고 7일 TTL 로 지워지는 ClickHouse 에 들어가면 안 된다. 양도 적다. collector 가 api-service 로 직접 넘기는 호출은 enroll 의 tenant 해석과 같은 방식이다(`ARCHITECTURE.md` 직접 호출 표).

**실패 시 잘라 저장하는 이유**: 칸 길이를 넘으면 INSERT 가 실패해 사건이 통째로 사라진다. 자르는 쪽이 낫다. 다만 에이전트 API 에서 detail 만 자르고 host, 유형은 거절하는 이유는 detail 이 자유 문장이라 잘려도 의미가 남고, 나머지는 길이가 넘으면 잘못 만든 입력이기 때문이다.

## 결과/영향
- 이 묶음이 바로 남기는 사건은 `signup`, `login_success`, `login_failure`, `logout`, `kill_request`, `agent_enroll` 이다.
- 가입 안 된 이메일의 로그인 실패와 enroll 실패는 tenant 를 알 수 없어 `tenant_id` 가 비고 조회 API 에는 나오지 않는다. 다른 tenant 의 admin 에게 보일 수는 없기 때문이다. DB 에는 남는다.
- 접속 IP 는 `request.getRemoteAddr()` 다. 로드밸런서나 인그레스 뒤에서는 그 장비 주소가 찍힌다. 앞단이 생기면 `server.forward-headers-strategy` 를 함께 정해야 한다(`docs/exec-plans/tech-debt.md`).
- 감사기록 보관 기간, 용량 임계 통보, 포화 시 대응은 아직 없다(`tech-debt.md`).
- 배포하면 `audit_log` 테이블이 `ddl-auto: update` 로 생긴다. 환경변수 추가는 없다.

## 관련
- `docs/requirements/security.md` 감사기록 절(SEC-1 ~ SEC-19)
- `docs/agent-protocol.md` 감사기록 절
- `docs/security/nis-requirements-review.md` 서버 7.1~7.5
