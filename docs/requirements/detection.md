# 탐지 (PRD: `docs/product-specs/README.md` 탐지 룰)

판정 파이프라인이 성립해야 하는 것. 왜 이렇게 정했는지는 `docs/design-docs/decisions/`.

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| DET-1 | 외부에서 받아온 파일을 실행하면 R2 알림이 나간다 | `DetectionTopologyTest.downloadExecute_emitsAlert` | `docs/product-specs/README.md` R2 | 자동 |
| DET-2 | 네트워크 이벤트가 실행보다 늦게 도착해도 R2 가 성립한다 | `DetectionTopologyTest.downloadExecute_networkArrivesLate_emitsAlert` | `docs/design-docs/decisions/006-event-time-ordering.md` | 자동 |
| DET-3 | 도착 순서가 바뀌어도 룰별 집계가 같다 | `DetectionTopologyTest.alertCounter_isIndependentOfArrivalOrder` | `docs/design-docs/decisions/006-event-time-ordering.md` | 자동 |
| DET-4 | 윈도우(5분) 밖의 조합은 알림이 아니다 | `DetectionTopologyTest.outsideWindow_noAlert` | `README.md` "Kafka Streams" | 자동 |
| DET-5 | 다른 host 의 이벤트끼리는 상관되지 않는다 | `DetectionTopologyTest.differentHosts_noAlert` | `docs/design-docs/decisions/001-kafka-between-collection-and-detection.md` | 자동 |
| DET-6 | 같은 시퀀스로 알림이 두 번 나가지 않는다 | `DetectionTopologyTest.sequenceAlert_emittedOnce` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| DET-7 | 늦게 온 트리거도 세고 판정에서 빠지지 않는다 | `DetectionTopologyTest.lateTrigger_isCountedAndStillEvaluated` | `docs/design-docs/decisions/002-kafka-streams-processor-api.md` | 자동 |
| DET-8 | 조용해진 host 의 대기 판정이 flush 된다 | `DetectionTopologyTest.quietHost_pendingIsFlushed` | `docs/design-docs/failures/007-punctuator-publishes-outside-transaction.md` | 자동 |
| DET-9 | 재시작해도 상관 버퍼가 복원된다 | `CorrelationProcessorRestartTest` 전체 | `docs/design-docs/decisions/002-kafka-streams-processor-api.md` | 자동 |
| DET-10 | Streams 가 처리 불가 상태면 health 가 DOWN 이다 | `KafkaStreamsHealthIndicatorTest.notProcessing_down` | `docs/design-docs/failures/008-streams-error-but-health-up.md` | 자동 |
| DET-11 | 룰 파일의 필드 modifier(contains, startswith, endswith, all)와 값 목록이 해석된다 | `SigmaParserTest.substringModifiers`, `SigmaParserTest.valueList_isOr`, `SigmaParserTest.containsAll_requiresEveryValue` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-12 | 룰 파일의 condition(and, or, not, 괄호, 1 of, all of)이 우선순위대로 해석된다 | `SigmaParserTest.condition_andOrNot`, `SigmaParserTest.condition_parenthesesAndPrecedence`, `SigmaParserTest.condition_quantifiers` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-13 | 지원하지 않는 구문의 룰은 거절된다(조용히 넓게 걸리거나 안 걸리지 않는다) | `SigmaParserTest.unsupportedModifier_isRejected`, `SigmaParserTest.wildcardValue_isRejected`, `SigmaParserTest.unknownField_isRejected`, `SigmaParserTest.unsupportedCondition_isRejected`, `SigmaParserTest.emptyValueForSubstringModifier_isRejected`, `SigmaParserTest.duplicateKeys_areRejected`, `SigmaParserTest.multipleMatchModifiers_areRejected` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-14 | 에이전트가 파일명만 보내는 Image 는 파일명 일치로 판정된다 | `SigmaParserTest.imageEndswithWithSeparator_matchesBasenameExactly`, `SigmaParserTest.imageContainsPath_isRejected` | `docs/agent-protocol.md` | 자동 |
| DET-15 | 룰 파일을 바꾸면 재배포 없이 새 룰이 반영된다 | `DataRulesTest.reload_swapsInChanges` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-16 | 깨진 룰 파일은 그 파일의 이전 룰을 유지하고 나머지는 정상 반영된다 | `DataRulesTest.reload_brokenFile_keepsPreviousRule`, `DataRulesTest.unsupportedRule_isSkipped_othersLoaded` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-17 | 룰 디렉터리를 못 읽거나 비어도 룰이 통째로 꺼지지 않는다 | `DataRulesTest.reload_missingOrEmptyDirectory_keepsPrevious`, `DataRulesTest.unreadableExternalDirectory_startsWithBundled`, `DataRulesTest.reload_allFilesBroken_keepsPrevious`, `DataRulesTest.start_allExternalFilesBroken_usesBundled` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-18 | ConfigMap 마운트(심볼릭 링크)로 놓인 룰 파일을 읽고 갱신을 반영한다 | `DataRulesTest.configMapStyleSymlinks_areRead` | `k8s/detector-service.yaml` | 자동 |
| DET-19 | 번들 룰 35개가 각각 걸릴 이벤트에 걸리고 아닌 것에는 안 걸린다 | `BundledRulesTest.rule_firesOnPositives_notOnNegatives`, `BundledRulesTest.everyBundledRule_isLoaded_andTested` | `detector-service/src/main/resources/rules/README.md` | 자동 |
| DET-20 | 데이터 룰이 R2(CRITICAL)를 가리지 않고, 심각도가 같으면 코드 룰이 이긴다 | `RulesDataRulesTest.r2_isNeverShadowedByDataRule`, `RulesDataRulesTest.r1_vsDataRule_mostSevereWins_tieGoesToCode`, `RulesDataRulesTest.r3_vsDataRule` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-21 | 데이터 룰은 시퀀스 트리거와 상관 버퍼 대상을 바꾸지 않는다 | `RulesDataRulesTest.dataRules_doNotChangeBufferBehavior` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-22 | 종료(kill) 권고는 명백히 악성인 번들 룰뿐이고, process 가 아닌 룰은 알림만 보낸다 | `BundledRulesTest.onlyClearlyMaliciousRules_recommendKill`, `SigmaParserTest.nonProcessRules_areCappedAtMedium` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-23 | 데이터 룰이 토폴로지를 거쳐 alerts 에 알림으로 나간다 | `DetectionTopologyTest.dataRule_firesThroughTopology` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-24 | 룰 위치의 같은 문제는 상태가 바뀔 때만 에러로 남는다(없는 ConfigMap 이 로그를 쌓지 않는다) | `DataRulesTest.sameProblem_isLoggedOncePerStateChange` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-25 | 판정 중 예외를 던지는 룰은 건너뛰고 나머지 룰은 판정된다 | `RulesDataRulesTest.throwingRule_isSkipped` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-26 | 기본 로케일에 상관없이 대소문자 비교와 ruleId 가 같다 | `SigmaParserTest.caseHandling_ignoresDefaultLocale` | `docs/design-docs/decisions/020-sigma-subset-data-rules.md` | 자동 |
| DET-27 | R3 는 실행 대상(명령의 첫 토큰, 인터프리터가 읽는 스크립트 파일)만 보고, 인자로만 나온 임시 경로에는 걸리지 않는다 | `RulesTest.r3_tempPathAsPlainArgument_noAlert`, `RulesTest.r3_nestedExecutionTarget_alerts`, `RulesTest.r3_realSystemTempPath_stillAlerts` | `docs/design-docs/decisions/027-r3-checks-execution-target-only.md` | 자동 |
| DET-28 | macOS 룰 7개(SigmaHQ 이식 AppleScript 3개, 자체 인포스틸러 4개: 쿠키, 브라우저 자격증명, 로컬 데이터, curl 압축 파일 업로드)가 ART 명령행과 공개 보고서 명령행에 걸리고 정상 사용에는 안 걸린다. 실기기에서 ART 를 돌린 확인은 아직 없다(자동 열은 단위 테스트까지만이다) | `BundledRulesTest.rule_firesOnPositives_notOnNegatives`(해당 7개 케이스) | `detector-service/src/main/resources/rules/` 룰 파일 머리의 근거 주석 | 자동 |

## 알림 억제

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| SUP-1 | 쿨다운 창 안의 같은 키는 억제된다 | `CooldownTest.withinWindow_suppressed` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| SUP-2 | 창이 지나면 다시 나간다 | `CooldownTest.afterWindow_allowed` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| SUP-3 | 다른 키는 서로 영향을 주지 않는다 | `CooldownTest.differentKeys_independent` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| SUP-4 | 같은 키로 동시에 들어와도 하나만 통과한다 | `CooldownTest.concurrentSameKey_onlyOnePasses` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |

## 테넌트 격리

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| TEN-1 | tenant 없이 조회 질의를 만들 수 없다 | `TenantScopeTest` 의 `tenant_없이_만드는_공개_경로가_없다` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| TEN-2 | tenant 조건이 항상 첫 조건으로 붙는다 | `TenantScopeTest` 의 `tenant_는_항상_첫_조건이고_값은_trim_해서_바인딩된다` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |

## 적재

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| ING-1 | events 가 적재일 단위로 파티션된다 | `ClickHouseWriterTest` 의 `신규_테이블은_적재일_단위로_파티션된다` | `docs/design-docs/decisions/014-partition-by-ingest-day.md` | 자동 |
| ING-2 | 만료된 파트를 통째로 버린다 | `ClickHouseWriterTest` 의 `만료된_파트를_통째로_버리게_설정한다` | `docs/design-docs/decisions/014-partition-by-ingest-day.md` | 자동 |
| ING-3 | 롤업이 SummingMergeTree 이고 180일 보관된다 | `ClickHouseWriterTest` 의 `롤업은_SummingMergeTree_다` | `docs/design-docs/decisions/015-hourly-rollup.md` | 자동 |
| ING-4 | 리스너가 실제 레코드를 받아 적재로 넘어간다 | `AlertIngestListenerKafkaTest` 전체 | `docs/design-docs/failures/001-batch-listener-native-headers.md` | 자동 |

## 수동 확인

| ID | 확인할 것 | 방법 | 상태 |
|---|---|---|---|
| DET-M1 | Windows 실기기에서 ETW 센서가 이벤트를 낸다 | `agent/README.md` 의 Windows 실기기 검증 절차. ARM64 VM 에서만 봤다(`docs/requirements/ingest.md` AGT-M2) | 수동 |
| DET-M2 | 실제 단말에서 R2 가 끝까지 흐른다 | 에이전트 설치 후 다운로드·실행 시나리오를 돌려 Slack 알림까지 확인 | 수동 |
| DET-M3 | 운영 파드에서 ConfigMap 을 고치면 재배포 없이 새 룰이 적용된다 | 배포 후 `detector-rules` 에서 룰 하나를 고쳐 apply 하고, 약 2분 뒤 detector 로그의 `탐지 룰 N개 적용` 과 해당 룰의 알림을 확인(배포서버 명령은 사람이 실행) | 미구현 |
| DET-M4 | KISA 탐지 항목(BF-14/15/16, SE-01 취지)을 자체 시험으로 재면 수집→탐지→대응이 돌고 탐지율이 나온다 | 실단말에서 `scripts/detection-test/run.sh --run` 을 돌려 결과표와 탐지율을 확인. 설계와 분모·측정창 규칙은 `docs/exec-plans/completed/kisa-edr-detection-test.md` (재시작 후 10분 안에는 재지 않는다) | 수동 |
