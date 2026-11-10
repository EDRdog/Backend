# 보안 기능 (KISA EDR 성능평가 항목 V1.4 보안 기능, `docs/security/kisa-edr-performance-criteria.md`)

관리 서버와 에이전트가 제품 자체를 지키려고 성립해야 하는 것. 계획은 `docs/exec-plans/active/kisa-edr-security-functions.md`.

## 감사기록 (BF-13)

서버 감사기록(BF-13.1)과 에이전트 감사기록을 받는 서버 쪽(BF-13.2). 왜 이렇게 정했는지는 `docs/design-docs/decisions/028-audit-log-in-api-service.md`.

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| SEC-1 | 가입, 로그인 성공, 로그아웃이 시각, tenant, 행위자(종류, 이메일, 유저 id), 유형, 결과, 접속 IP 와 함께 남는다 | `AuditApiIntegrationTest.signupLoginAndLogoutAreRecordedWithActorAndIp`, `AuditServiceTest.recordStoresEveryField` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-2 | 로그인 실패가 남는다. 가입된 이메일이면 그 tenant 의 기록으로 남는다 | `AuditApiIntegrationTest.failedLoginOfARegisteredEmailIsRecordedInThatTenant` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-3 | 대응(kill) 요청이 요청자, 대상 host, 프로세스, 명령 id, 결과와 함께 남는다. 명령이 나가지 못했으면 실패로 남는다 | `AuditApiIntegrationTest.killRequestRecordsTheRequesterAndTheHost`, `AuditApiIntegrationTest.killRequestThatTheResponderFailedIsRecordedAsFailure` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-4 | 에이전트 enroll 결과(api-service 가 보는 범위: 시크릿이 맞았는지)가 남고 시크릿 값은 남지 않는다 | `AuditApiIntegrationTest.agentEnrollResultIsRecordedWithoutTheSecret` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-5 | 기록 저장이 실패해도 본 요청은 실패하지 않는다 | `AuditServiceTest.recordNeverThrowsEvenWhenTheStoreFails` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-6 | 감사기록을 수정하거나 지우는 코드와 API 가 없다 | `AuditServiceTest.auditCodeHasNoUpdateOrDeletePath`, `AuditApiIntegrationTest.thereIsNoApiToChangeOrDeleteRecords` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-7 | 감사기록 조회는 로그인한 admin 역할만 된다. 비로그인은 401, admin 이 아니면 403 | `AuditApiIntegrationTest.requestWithoutTokenIsRejected`, `AuditApiIntegrationTest.nonAdminRoleIsForbidden` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-8 | 다른 tenant 의 기록은 조회되지 않는다 | `AuditApiIntegrationTest.otherTenantOnlySeesItsOwnRecords`, `AuditServiceTest.searchStaysInsideTheTenant` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-9 | 기간, 사건 유형, 행위자, 결과로 거를 수 있다 | `AuditServiceTest.searchFiltersByRangeTypeActorAndResult`, `AuditApiIntegrationTest.searchSortAndPageByQueryParameters` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-10 | 시각 오름차순과 내림차순으로 정렬할 수 있다 | `AuditServiceTest.searchSortsByTimeInBothDirections` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-11 | limit, offset 페이지로 읽고 상한 밖 offset 과 잘못된 파라미터는 400 이다 | `AuditServiceTest.searchPagesWithLimitOffsetHasMoreAndTotal`, `AuditApiIntegrationTest.badSearchParametersAreRejected` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-12 | 패스워드, 세션 토큰, 비밀번호 해시, enroll secret 이 감사기록(응답과 테이블)에 남지 않는다 | `AuditApiIntegrationTest.secretsNeverReachTheAuditTable`, `AuditApiIntegrationTest.agentEnrollResultIsRecordedWithoutTheSecret` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-13 | `POST /api/internal/audit` 는 X-Internal-Key 가 없거나 틀리면, 프론트 X-API-Key 만 있으면 401 이고 저장하지 않는다 | `InternalAuditApiIntegrationTest.withoutInternalKeyItIsUnauthorizedAndNothingIsStored`, `InternalAuditApiIntegrationTest.frontendApiKeyAloneIsNotEnough`, `InternalAuditApiIntegrationTest.wrongInternalKeyIsUnauthorized` | `docs/security/kisa-edr-performance-criteria.md` BF-13.2 | 자동 |
| SEC-14 | 에이전트 감사기록을 받아 collector 가 푼 tenant 와 host 의 에이전트 행위자 기록으로 저장하고, 그 tenant 의 admin 이 조회한다 | `InternalAuditApiIntegrationTest.acceptedEventsAreStoredAsAgentRecordsOfThatTenantAndHost`, `InternalAuditApiIntegrationTest.agentRecordsAreVisibleToTheTenantAdmin` | `docs/security/kisa-edr-performance-criteria.md` BF-13.2 | 자동 |
| SEC-15 | 한 번에 받는 사건은 100건까지다. 넘으면 전체를 거절하고 100건은 받는다. host 와 사건 유형이 길이 상한을 넘으면 거절하고 detail 은 500자로 잘라 저장한다 | `InternalAuditApiIntegrationTest.moreThanTheEventLimitIsRejectedWholesale`, `InternalAuditApiIntegrationTest.exactlyTheEventLimitIsAccepted`, `InternalAuditApiIntegrationTest.overlongDetailIsCutAndOverlongHostOrTypeIsRejected` | `docs/security/kisa-edr-performance-criteria.md` BF-13.2 | 자동 |
| SEC-16 | 형식이 틀린 사건(결과, 유형, 시각, host, tenantId, 빈 배열)이 하나라도 있으면 400 이고 일부만 저장하지 않는다 | `InternalAuditApiIntegrationTest.malformedBatchesAreRejectedAndNothingIsPartiallyStored` | `docs/security/kisa-edr-performance-criteria.md` BF-13.2 | 자동 |
| SEC-17 | 칸 길이를 넘는 글자는 잘라서 저장해 사건이 사라지지 않는다 | `AuditServiceTest.recordCutsOverlongTextToTheColumnLimits` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |
| SEC-18 | 서버 시각보다 앞선 에이전트 ts 는 받은 시각으로 맞춘다 | `InternalAuditApiIntegrationTest.futureTimestampsAreClampedToReceiveTime` | `docs/security/kisa-edr-performance-criteria.md` BF-13.2 | 자동 |
| SEC-19 | 감사기록 조회 경로 `/api/audit` 는 X-API-Key 없이 세션 Bearer 로 인증한다 | `ApiKeyPolicyTest.auditQueryIsExemptBecauseItUsesSessionBearer` | `docs/security/kisa-edr-performance-criteria.md` BF-13.1 | 자동 |

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

