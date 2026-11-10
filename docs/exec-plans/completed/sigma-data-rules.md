# 단일 이벤트 룰을 Sigma 부분집합 파일로 옮기고 룰 28개를 추가한다

결정과 근거는 [020](../../design-docs/decisions/020-sigma-subset-data-rules.md).

## 목표
- 룰 하나를 더하려면 코드를 고치고 다시 배포해야 한다. 단일 이벤트 룰은 파일만 바꿔 반영한다.
- SigmaHQ 공개 룰 중 에이전트가 보내는 필드로 판정되는 것 20~30개를 가져온다.

## 범위
- 한다: Sigma 부분집합 파서, 외부 디렉터리 + 주기 리로드, 번들 룰 28개, k8s ConfigMap, 테스트, 문서.
- 안 한다: 시퀀스 룰(R1, R2) 이전, 새 시퀀스 룰, R3~R5 이전, api-service 한글 카탈로그 항목.

## 단계
1. [x] 코드 읽기: `Rules`, `CorrelationProcessor`, 에이전트 필드, ruleId 소비처(api-service, alert-service)
2. [x] 파서 테스트 먼저(스텁에서 26건 실패 확인) → `SigmaParser`, `SigmaRule`
3. [x] 로더 테스트 먼저(스텁에서 9건 중 9건 실패 확인) → `DataRules`(외부 디렉터리, 주기 리로드, 깨진 파일은 이전 룰 유지)
4. [x] `Rules.evaluate` 에 데이터 룰 합류(가장 심각한 것 채택, 동률은 앞선 것), 토폴로지에 연결
5. [x] 번들 룰 28개 + 룰별 양성/음성 테스트
6. [x] k8s ConfigMap(생성 스크립트 + check.sh 검사), 볼륨 마운트
7. [x] 문서: ADR 020, README, PRD, ERD(DET 행), tech-debt
8. [x] `scripts/check.sh`, `./gradlew build`

## 실제로 된 것
- 계획대로. 번들 룰은 28개다(원본 대비 level 을 낮춘 것과 에이전트 필드에 맞춰 바꾼 것은 ADR 과 룰 파일 주석에 있다).
- 작업 중에 에이전트가 `-enc` 값을 `<redacted>` 로 가리고 인터프리터를 `script` 타입으로 보낸다는 것을 발견해 룰을 맞췄다.
  같은 이유로 R1 이 실데이터로는 안 걸릴 가능성을 찾았고 고치지 않고 tech-debt 에 올렸다(범위 밖).
- 알림 정책: 종료 권고(HIGH)는 명백히 악성인 5개뿐, process 가 아닌 룰은 MEDIUM 으로 깎는다.
