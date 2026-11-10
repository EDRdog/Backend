# 020. 단일 이벤트 룰은 Sigma 부분집합 파일로 두고, 외부 디렉터리에서 주기적으로 다시 읽는다

- 날짜: 2026-11-10
- 상태: 채택

## 배경
탐지 룰이 `Rules.java` 의 R1~R5 로 박혀 있었다. 룰 하나를 더하려면 코드를 고치고 detector 를 다시 배포해야 한다. 공개된 탐지 룰(SigmaHQ)은 수천 개인데 가져다 쓸 길이 없었다.
R1, R2 는 이벤트 여러 개를 시간 순으로 엮는 시퀀스 룰이다. R3~R5 는 이벤트 하나로 판정하지만 토큰 분리, 사용자 홈 경로 매칭, 삭제 제외, `detail.tlsVersion` 같은 Sigma 로 못 쓰는 논리가 들어 있다.

## 결정
- **단일 이벤트(point) 룰만** Sigma 부분집합 파일로 뺀다. 이 에이전트가 보내는 필드로 판정되는 SigmaHQ 룰 28개를 번들로 넣는다(`detector-service/src/main/resources/rules/`).
- **형식은 Sigma 부분집합**이다. `logsource.category` 넷(process_creation, file_event, dns_query, network_connection), 필드 modifier `contains/startswith/endswith/all`, 값 목록(OR), `and/or/not/괄호/1 of/all of`. 지원하지 않는 구문(re, windash, 와일드카드 값, 키워드, 집계 condition, 에이전트가 안 보내는 필드)은 **그 룰만** 거절하고 로그에 이유를 남긴다.
- **로딩**은 jar 번들 룰로 시작하고, `DETECTION_RULES_DIR` 이 있으면 그 디렉터리(k8s ConfigMap 마운트)로 바꿔 끼운 뒤 30초마다 다시 읽는다. 읽는 쪽은 불변 스냅샷 참조(`volatile List`) 하나만 읽어서 Kafka Streams 스레드가 리로드에 막히지 않는다.
- **깨진 파일은 파일 단위로** 처리한다. 읽지 못한 파일은 로그를 남기고 그 파일의 이전 룰을 유지한다. 디렉터리를 못 읽거나 비어 있으면 통째로 이전 룰을 유지한다.
- **R1, R2 는 코드에 둔다. 새 시퀀스 룰도 만들지 않는다.** R3, R4, R5 도 코드에 둔다. 같은 동작으로 옮길 수 없는 논리가 있고, 기존 `RulesTest` 45건이 그대로 통과해야 한다.
- **ruleId** 는 룰 파일의 `name`(소문자, 숫자, `_`)을 대문자로 바꾼 값이다. SigmaHQ `id`(UUID)는 출처 표기로만 남긴다. UUID 는 알림 화면, 집계, 쿨다운 키로 쓰기에 읽을 수 없다. **MITRE** 는 `tags` 의 첫 `attack.tNNNN`, 없으면 거절한다.

### 판정 합류와 우선순위
`Rules.evaluate(prior, current, dataRules)` 는 후보(R2, R1, R3, R4, R5, 데이터 룰 파일 순)를 모아 **가장 심각한 것**을 고른다. 동률이면 앞선 것이 이긴다. 그래서
- R2(CRITICAL)는 어떤 데이터 룰에도 가려지지 않는다(데이터 룰이 CRITICAL 이어도 동률이라 R2 가 이긴다).
- R3~R5(MEDIUM)와 같은 심각도의 데이터 룰이 겹치면 코드 룰이 이긴다. 기존 동작이 그대로다.
- 더 심각한 데이터 룰은 R3~R5 나 R1 을 이긴다.
- 데이터 룰은 `isSequenceTrigger`, `isCorrelatable` 에 관여하지 않는다. 상관 버퍼 상한과 윈도우가 데이터 룰 때문에 달라지지 않는다.

### 심각도와 kill 정책
`Alert.actionFor` 는 HIGH, CRITICAL 이면 kill 을 권고한다. 데이터 룰은 보수적으로 둔다.
- Sigma `level` 매핑: informational, low, medium → **MEDIUM**(알림만), high → HIGH, critical → CRITICAL.
- **프로세스가 대상이 아닌 룰(file_event, dns_query, network_connection)은 `high` 로 적어도 MEDIUM** 이다. 종료할 프로세스를 특정할 수 없다(responder 는 `target` 을 프로세스로 쓴다).
- 번들 룰의 `level` 은 원본이 아니라 **이 시스템의 대응 수준**이다. HIGH 는 명백히 악성이라 오탐 비용이 낮은 5개뿐이다: mimikatz 명령행, reg 로 SAM/SYSTEM/SECURITY 덤프, PowerShell 로 섀도 복사본 삭제, wbadmin 전체 백업 삭제, procdump 로 lsass 덤프. 원본이 high 인 mshta 원격 HTA, schtasks 임시 폴더, PlistBuddy 지속성은 정상 사용과 겹칠 수 있어 MEDIUM 으로 낮췄다. 낮춘 룰은 파일 주석에 원본 level 을 적었다.
- CRITICAL 인 번들 룰은 없다.

## 근거
**에이전트가 보내는 필드에 맞춰 옮긴 것들** (원문 그대로는 안 걸리거나 영영 안 걸린다)
- `Image`, `ParentImage` 는 경로 없이 파일명만 온다. `Image|endswith: '\cmd.exe'` 는 파일명 일치로 읽는다(`xcmd.exe` 는 안 걸린다). 경로 조각 검색은 거절한다.
- 인터프리터(powershell, cmd, wscript, cscript, mshta, bash 등)는 `script` 타입으로 온다. `process_creation` 은 process 와 script 둘 다 본다.
- 에이전트는 `-enc`, `-encodedcommand` 뒤의 값을 `<redacted>` 로 가려 보낸다. 원본 `powershell_encoded_command` 는 base64 패턴을 보므로 `-enc` 로는 영영 안 걸린다. 가려진 표시 자체를 증거로 보는 조건을 더했다.
- argv[0] 은 실행 파일 전체 경로로 바뀌어 온다. `' msiexec'`(앞 공백) 조건은 직접 실행을 놓쳐서 공백을 뺐다.
- 레지스트리, 프로세스 접근, 인증 로그 이벤트는 없다. `OriginalFileName` 조건도 뺐다.

**버린 대안**
- **자체 YAML 형식**: 공개 룰을 못 가져온다. 형식을 우리가 정의하고 유지해야 한다. Sigma 는 문법이 이미 있고 룰이 쌓여 있다. 부분집합만 읽으므로 파서는 작다(약 300줄).
- **jar 리소스만(외부 파일 없이)**: 룰을 바꿀 때마다 이미지를 구워 재배포한다. 이 문서가 풀려는 문제 그대로다.
- **Sigma correlation 으로 시퀀스 룰까지 이전**: R1, R2 는 pid/ppid 계보 확인, 이벤트 시각 순서(워터마크), 선행 버퍼 상한 같은 detector 내부 동작에 묶여 있다. Sigma correlation 은 이를 표현하지 못하고, 옮기면 [006](006-event-time-ordering.md) 의 순서 보장을 다시 증명해야 한다.
- **pySigma 같은 전체 Sigma 엔진**: 자바 서비스에 외부 런타임을 붙여야 하고 백엔드 변환기도 쓸 일이 없다.
- **R3~R5 를 Sigma 로 이전**: 토큰 분리, 사용자명 한 칸을 강제하는 홈 경로 매칭, 삭제 제외, TLS 버전 집합 판정은 Sigma 문법에 없다. 비슷하게 옮기면 조각 검색으로 바꿨을 때 생긴 오탐(#174)이 돌아온다.
- **ConfigMap 을 손으로 쓰기**: 번들 룰과 ConfigMap 이 갈라진다. 룰 파일에서 생성하는 스크립트를 두고 `scripts/check.sh` 가 어긋남을 잡는다.

## 결과/영향
- 룰을 더하거나 고치려면 룰 파일 하나를 고치고 `scripts/gen-rules-configmap.sh > k8s/detector-rules.yaml` 를 돌린다. CD 가 `k8s/*.yaml` 을 apply 하므로 ConfigMap 이 갱신되고, 파드는 kubelet 동기화(최대 1~2분)와 리로드 주기(30초) 뒤에 새 룰을 쓴다. 재시작은 없다.
- ConfigMap 마운트는 `optional: true` 다. 없거나 비어 있으면 이미지에 든 번들 룰로 돈다. 룰 전달 경로가 깨져도 탐지가 꺼지지 않는다.
- 새 ruleId 는 api-service `ThreatCatalog` 에 없어서 화면에 ruleId 원문, 카테고리 "기타" 로 보인다. 한글 이름이 필요하면 따로 넣는다(기술부채).
- `cep.alerts` 지표의 `rule` 태그가 룰 수만큼 늘어난다(28개 안팎).
- file 룰은 에이전트가 감시하는 경로의 파일 이벤트만 받아서 실제로 걸릴 일이 적다. 번들에 network_connection 룰은 없다(파서는 지원한다).
- 이벤트마다 룰 전체를 훑는다(type 색인 없음). 룰이 수백 개가 되면 type 별로 색인한다.
- 시퀀스 트리거 이벤트(office 자식 shell 등)에 걸리는 데이터 룰은 그 이벤트가 워터마크를 기다리는 만큼 늦게 나간다.

## 관련
- [002](002-kafka-streams-processor-api.md), [006](006-event-time-ordering.md), `detector-service/src/main/resources/rules/README.md`
