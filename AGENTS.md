# EDRdog Backend

엔드포인트에서 수집한 행위 로그로 공격 시퀀스를 탐지하고, 그 기기의 프로세스를 실제로 종료하는 EDR 백엔드.
자체 Go 에이전트가 수집하고, Kafka Streams 가 host 별로 상관분석해 판정하며, 판정은 Slack 알림과 프로세스 종료 명령으로 이어진다.

## 명령어

- 빌드: `./gradlew build`
- 검증(lint + test + 문서): `scripts/check.sh` ← 작업을 끝내기 전에 반드시 실행
- 단일 모듈 테스트: `./gradlew :detector-service:test`
- 에이전트(Go): `cd agent && go test ./...`
- 로컬 인프라: `k8s/README.md` 의 기동 절차(kind + Kafka + ClickHouse + otel-lgtm)
- 로컬 실행: `./gradlew :collector-service:bootRun` (인프라가 먼저 떠 있어야 한다)

자바 쪽에는 린터를 두지 않았다(spotless·checkstyle 설정 없음). `check.sh` 의 lint 자리는 에이전트의 `go vet` 이다.

## 금지사항

- **운영 비밀을 git 에 넣지 않는다.** 시크릿은 Infisical 과 GitHub Actions secrets 로만 간다. API 키를 대화에 붙여넣게 하지도 않는다.
- **PR 은 `dev` 로 연다.** `main` 으로 직접 PR 하지 않는다. `main` 반영은 별도의 dev→main PR 이다.
- **부하 테스트(`scripts/loadtest`, k6)를 임의로 돌리지 않는다.** 돌리는 순간 지표 카운터가 리셋돼 앞선 측정과 섞인다. 스크립트를 먼저 보여주고 승인을 받는다.
- **배포서버 명령은 사람이 실행한다.** `sudo kubectl` 이 필요하고 노트북에 SSH 키가 없다.
- **ClickHouse 테이블을 손으로 `CREATE` 하지 않는다.** `ClickHouseWriter.ensureSchema()` 가 단일 스키마 출처다. 바꿀 수 없는 스키마는 RENAME 후 archiver 재기동으로 교체한다(`docs/design-docs/failures/`).
- **Kafka 파티션 수를 바꾸지 않는다.** host 키 기반이라 바꾸는 순간 한 기기의 이벤트가 두 파티션으로 갈라져 순서 보장이 깨진다.
- **`.proto` 의 필드 번호를 재사용하지 않는다.** 옛 데이터를 엉뚱한 필드로 읽으면서 에러도 안 난다.
- **detector·Kafka·ClickHouse 구조를 고치자고 하기 전에 `README.md` 의 해당 절을 먼저 읽는다.** 비효율로 보이는 것 중 근거와 함께 일부러 남긴 것이 있다(실제로 리파티션 제거 작업을 통째로 폐기한 적이 있다).
- 위험 명령(`rm -rf`, `git push --force`, `git reset --hard`, `DROP TABLE`)은 직접 실행하지 말고 사용자에게 요청. `scripts/guard.sh` 가 막는다.

## 문서 지도

- 구조, 레이어, 의존 방향: `ARCHITECTURE.md`
- 서비스가 무엇이고 왜 이렇게 생겼나(설계 서술 원문): `README.md`
- 에이전트와 서버의 계약: `docs/agent-protocol.md`
- 에이전트 내부 구조와 플랫폼별 한계: `agent/README.md`
- 로컬 인프라 기동과 접속: `k8s/README.md`
- 무엇을, 왜 (PRD): `docs/product-specs/`
- 검증 가능한 수용 기준 (ERD, Engineering Requirements Document): `docs/requirements/`
- 왜 이렇게 정했나 (ADR): `docs/design-docs/decisions/`
- 무엇이 깨졌고 왜 (실패 기록): `docs/design-docs/failures/`
- 진행 중인 작업 계획: `docs/exec-plans/active/`
- 끝난 작업 기록: `docs/exec-plans/completed/`
- 기술부채: `docs/exec-plans/tech-debt.md`

## 작업 방식

- 여러 단계 작업은 `docs/exec-plans/active/`에 계획을 먼저 쓰고, 끝나면 `completed/`로 옮긴다.
- 같은 실수가 두 번 나오면 이 문서에 규칙을 적고, 가능하면 `scripts/check.sh`에 검사로 추가한다.
- 결정(설계, 라이브러리 선택, 버린 대안)은 묻지 않고 ADR 로 남긴다. 실패는 실패 기록으로 남긴다.
- 구현, 배포, 삭제로 상태가 바뀌면 같은 PR 에서 `docs/requirements/`(기준, 검증, 상태 칸)와 `docs/exec-plans/tech-debt.md` 를 고친다. 문서의 "현재 ~만 됨" 같은 문장도 같이 고친다.
- **측정값을 문서에 적을 때는 측정 조건을 같이 적는다.** 이 레포의 수치 상당수가 로컬 합성 데이터이고, 운영 실측과 섞이면 근거가 못 된다.
- 리스너 시그니처처럼 기동에서는 티가 안 나고 레코드가 올 때만 터지는 변경은 임베디드 브로커 테스트로 확인한다(#247 에서 적재가 통째로 멈춘 적이 있다).
