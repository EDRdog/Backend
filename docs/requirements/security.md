# 보안 기능 (KISA EDR 성능평가 항목 V1.4 보안 기능, `docs/security/kisa-edr-performance-criteria.md`)

관리 서버와 에이전트가 제품 자체를 지키려고 성립해야 하는 것. 계획은 `docs/exec-plans/active/kisa-edr-security-functions.md`.

## 감사기록 (BF-13)

## 서버 자체시험·무결성 (BF-10, BF-11)

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| SEC-20 | 기동 직후 자체시험과 무결성 검증이 한 번 돈다 | `SelfTestApiIntegrationTest.startupRunIsRecordedWhenTheAppComesUp`, `SelfTestServiceTest.scheduledAndStartupRunsAreRecordedWithTheirTrigger` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-21 | 주기 실행이 스케줄러에 등록되고 실행은 SCHEDULED 로 기록된다(간격은 `SELFTEST_INTERVAL`) | `SelfTestApiIntegrationTest.periodicRunIsRegisteredWithTheScheduler`, `SelfTestServiceTest.scheduledAndStartupRunsAreRecordedWithTheirTrigger` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-22 | 관리자가 요청하면 즉시 한 번 돌고 요청자가 기록된다 | `SelfTestApiIntegrationTest.adminRunsOnRequestAndSeesItInTheList`, `SelfTestServiceTest.manualRunStoresItemsFromSelfTestAndIntegrity` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-23 | 자체시험이 DB 질의, BCrypt 알려진 값, 토큰 난수, SHA-256 시험 벡터, 필수 보안 설정을 실제로 시험하고 어긋나면 FAIL 이다. 예외 메시지와 설정값은 결과에 싣지 않는다 | `SelfTestChecksTest.allChecksPassWhenEverythingWorks`, `SelfTestChecksTest.passwordHashFailsWhenEncoderAcceptsAnything`, `SelfTestChecksTest.passwordHashFailsWhenEncoderRejectsKnownHash`, `SelfTestChecksTest.databaseFailureFailsWithoutLeakingExceptionMessage`, `SelfTestChecksTest.securityConfigFailsWhenAKeyIsBlank` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-24 | 결과가 저장되고 최근 목록(최신순, limit)과 항목별 결과(자체시험, 무결성)를 조회할 수 있다 | `SelfTestApiIntegrationTest.adminRunsOnRequestAndSeesItInTheList`, `SelfTestApiIntegrationTest.listIsNewestFirstAndLimited` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-25 | 조회와 요청은 로그인한 admin 만 된다(토큰 없으면 401, admin 이 아니면 403) | `SelfTestApiIntegrationTest.withoutTokenGetAndPostReturn401`, `SelfTestApiIntegrationTest.nonAdminGetAndPostReturn403` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-26 | FAIL 항목이 하나라도 있으면 그 실행은 FAIL 이고 SKIP 은 실패로 세지 않는다 | `SelfTestServiceTest.anyFailedItemMakesTheRunFail`, `SelfTestServiceTest.manualRunStoresItemsFromSelfTestAndIntegrity` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-27 | 실패하면 경고 로그를 남기고, 실패 항목이 새로 바뀐 때만 Slack 으로 알린다. webhook 이 없거나 발송이 실패해도 실행은 끝난다 | `SelfTestServiceTest.failureIsLoggedAsWarningWithTheFailedItemNames`, `SelfTestServiceTest.failureIsNotifiedOnlyWhenTheFailedSetChanges`, `SelfTestServiceTest.noWebhookMeansNoNotification`, `SelfTestServiceTest.notificationFailureDoesNotBreakTheRun` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-28 | 시험이 못 돌아도(DB 장애 등) 기동과 스케줄러는 죽지 않는다 | `SelfTestServiceTest.startupAndScheduledRunsSwallowFailuresSoTheAppKeepsRunning` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-29 | 운영 상태 화면의 `dependencies` 에 `selftest` 가 나온다(최근 실행이 PASS 면 up, FAIL 이면 down 과 실패 항목, 실행 전이거나 조회 실패면 unknown) | `SelfTestServiceTest.dependencyIsUpWhenLatestRunPassed`, `SelfTestServiceTest.dependencyIsDownAndNamesTheFailedItems`, `SelfTestServiceTest.dependencyIsUnknownBeforeTheFirstRun`, `SelfTestServiceTest.dependencyIsUnknownWhenTheLookupFails`, `OperationsHealthApiIntegrationTest.selfTestDependencyIsListedWithTheLatestRunStatus` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-30 | 기준값 파일에 적힌 파일의 SHA-256 이 실제와 같으면 PASS 다(실행 중인 jar 포함) | `IntegrityVerifierTest.matchingFilePasses`, `IntegrityVerifierTest.runningJarCoveredByBaselinePasses` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-31 | SHA-256 이 다르거나 파일이 없으면 FAIL 이고 다르면 기준과 실제 해시를 보여 준다 | `IntegrityVerifierTest.modifiedFileFailsAndShowsBothHashes`, `IntegrityVerifierTest.missingFileFails`, `IntegrityVerifierTest.tamperedRunningJarFails` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-32 | 기준값 파일이 설정되지 않았으면 SKIP 이다(실패가 아니다. 로컬 개발) | `IntegrityVerifierTest.noBaselineIsSkippedNotFailed` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-33 | 기준값 파일을 읽을 수 없거나 형식이 틀리거나 비어 있으면 FAIL 이다 | `IntegrityVerifierTest.unreadableBaselineFails`, `IntegrityVerifierTest.malformedBaselineLineFails`, `IntegrityVerifierTest.emptyBaselineFails` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |
| SEC-34 | jar 로 실행 중인데 기준값에 그 jar 가 없으면 FAIL 이다 | `IntegrityVerifierTest.runningJarMissingFromBaselineFails` | `docs/design-docs/decisions/029-server-selftest-and-integrity.md` | 자동 |

## 인증·세션·보안정책 (BF-01~05, BF-08, BF-12)

## 관리 IP 제한·접근통제·입력값 검증 (BF-07, BF-19, BF-20)

## 수동 확인

