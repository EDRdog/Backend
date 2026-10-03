# 알림 (PRD: `docs/product-specs/service.md` F4)

판정이 Slack 까지 가는 경로. 왜 이렇게 정했는지는 `docs/design-docs/decisions/003`.

## 수신 대상 결정

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| ALR-1 | 호스트 소유자의 개인 webhook 이 있으면 그쪽으로 간다 | `AlertRouterTest.route_userTarget` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-2 | 소유자가 없으면 조직 webhook 으로 간다 | `AlertRouterTest.route_tenantFallback` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-3 | 둘 다 없으면 보내지 않는다 (전역 폴백 없음) | `AlertRouterTest.route_none_empty` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| ALR-4 | 개인 webhook 이 있으면 조직 조회를 건너뛴다 | `AlertRouterTest.route_userTarget_skipsTenantLookup` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-5 | 호스트를 이미 다른 사람이 가져갔으면 409 다 | `UserNotifyServiceTest.registerHost_ownedByOther_409` | `docs/product-specs/service.md` F7 | 자동 |
| ALR-6 | 같은 호스트 등록이 동시에 들어와도 하나만 된다 | `UserNotifyServiceTest.registerHost_raceOnUnique_409` | `docs/design-docs/failures/005-enroll-race-duplicate-rows.md` | 자동 |
| ALR-7 | 소유자가 있고 webhook 도 있을 때만 대상이 된다 | `UserNotifyServiceTest.resolveTarget_ownerWithWebhook` | `docs/product-specs/service.md` F4 | 자동 |

## 발송

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| ALR-8 | 대상이 정해지면 발송한다 | `AlertListenerTest.routed_sends` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-9 | 대상이 없으면 건너뛴다 | `AlertListenerTest.noRoute_skips` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-10 | 메시지에 필요한 필드가 전부 들어간다 | `SlackNotifierTest.format_containsAllFields` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-11 | 지정된 webhook 으로 POST 한다 | `SlackNotifierTest.send_postsToGivenWebhook` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-12 | 한 건 실패로 컨슈머가 멈추지 않는다 (예외 대신 false) | `SlackNotifierTest.send_returnsFalseOnFailure` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| ALR-13 | webhook 주소는 https 여야 한다 | `WebhookValidationTest.https` | `docs/product-specs/service.md` F7 | 자동 |
| ALR-14 | 잘못된 webhook 설정은 400 이다 | `UserNotifyServiceTest.setWebhook_invalid_400` | `docs/product-specs/service.md` F7 | 자동 |
| ALR-15 | Slack 이 에러를 주면 502 로 올린다 | `UserNotifyServiceTest.sendTestWebhook_slackError_502` | `docs/product-specs/service.md` F7 | 자동 |

## 중복 억제

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| ALR-16 | 발송에 성공하면 창 안의 같은 알림이 억제된다 | `AlertListenerTest.sendSucceeded_withinWindow_suppressed` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| ALR-17 | 발송에 실패하면 쿨다운을 되돌린다 | `AlertListenerTest.sendFailed_cooldownRolledBack` | `docs/design-docs/decisions/003-no-eos-suppress-at-alert.md` | 자동 |
| ALR-18 | 조직이 다르면 같은 host·rule 이어도 서로 영향이 없다 | `CooldownTest.differentTenant_sameHostRule_independent` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |

## webhook 주소 조회 캐시

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| ALR-19 | 조회 결과를 캐시해 HTTP 를 한 번만 친다 | `TenantWebhookClientTest.resolve_cached_singleHttpCall` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-20 | 없음도 캐시한다 | `TenantWebhookClientTest.resolve_notFound_cached_singleCall` | `docs/product-specs/service.md` F4 | 자동 |
| ALR-21 | 일시적 오류는 캐시하지 않고 다시 시도한다 | `TenantWebhookClientTest.resolve_transientError_notCached_retries` | `docs/product-specs/service.md` F4 | 자동 |
