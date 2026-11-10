# 수집과 등록 (PRD: `docs/product-specs/service.md` F1, F2)

에이전트가 보낸 것이 검증을 거쳐 `events` 토픽에 들어가기까지. 왜 이렇게 정했는지는 `docs/design-docs/decisions/004`, `008`, `010`, `011`, `021`, `023`, `024`, `025`, `026`.

## 발행 계약

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-1 | 발행 본문이 Event 그 자체다 (필드명을 싣지 않는다) | `EventsProducerTest.bodyIsTheEventItself` | `docs/design-docs/decisions/010-protobuf-on-events-topic.md` | 자동 |
| AGT-2 | 파티션 키가 host 다 | `EventsProducerTest.partitionKeyIsHost` | `docs/design-docs/decisions/001-kafka-between-collection-and-detection.md` | 자동 |
| AGT-3 | 레코드 헤더에 스키마 버전·이벤트 타입·tenant 가 찍힌다 | `EventsProducerTest.headersAreStamped` | `docs/design-docs/decisions/010-protobuf-on-events-topic.md` | 자동 |
| AGT-4 | tenantId 가 발행을 건너도 살아남는다 | `EventsProducerTest.tenantIdSurvivesPublishing` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |

## 검증과 정규화

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-5 | 4종 이벤트가 각각 정규화된다 | `RawEventMapperTest` 전체 | `docs/product-specs/service.md` F1 | 자동 |
| AGT-6 | 시계가 틀어진 단말의 이벤트를 버리지 않고 당긴다 | `RawEventMapperTest` 의 ts 관련 케이스 | `docs/design-docs/decisions/006-event-time-ordering.md` | 자동 |
| AGT-7 | 검증 실패한 한 건이 배치 전체를 실패시키지 않는다 | `RawEventMapperTest` 전체 | `README.md` "1. Endpoint" | 자동 |
| AGT-8 | 이벤트에 tenant 가 태깅된다 | `EventTaggerTest` 전체 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| AGT-17 | 이벤트의 host 를 인증된 노드의 host_identifier 로 덮어쓴다 | `EventTaggerTest.overwritesHostWithNodeIdentity`, `AgentIngestIntegrationTest` 의 전체흐름 케이스 | `docs/security/kisa-2021-review.md` H2 | 자동 |

## 등록과 인증

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-9 | enroll → heartbeat → events 가 끝까지 흐른다 | `AgentIngestIntegrationTest` 전체 | `docs/agent-protocol.md` | 자동 |
| AGT-10 | gzip 으로 압축된 요청 본문을 풀어서 받는다 | `AgentIngestIntegrationTest` 의 gzip 케이스 | `docs/design-docs/decisions/013-compression-by-segment.md` | 자동 |
| AGT-11 | 본문이 8MB 를 넘으면 압축 여부와 상관없이 413 이다. Content-Length 가 크면 읽기 전에, 청크 본문은 읽는 도중 끊는다 | `AgentIngestIntegrationTest.압축_안_한_본문이_상한을_넘으면_413_이고_발행하지_않는다`, `AgentIngestIntegrationTest.해제_크기_상한을_넘는_요청은_413_이고_발행하지_않는다`, `GzipRequestFilterTest.청크_본문이_상한을_넘으면_읽는_도중_413_이다`, `GzipRequestFilterTest.청크_본문이_상한_이하면_끝까지_읽힌다` | `docs/design-docs/decisions/021-cap-all-request-bodies-at-8mb.md` | 자동 |
| AGT-18 | 깨진 gzip 본문은 500 이 아니라 400 이다 | `AgentIngestIntegrationTest.깨진_gzip_본문은_400_이다` | `docs/design-docs/decisions/021-cap-all-request-bodies-at-8mb.md` | 자동 |
| AGT-12 | 틀린 node_key 는 거부된다 | `AgentIngestIntegrationTest` 의 node_key 케이스 | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| AGT-13 | 같은 (tenant, host) 로 두 번 등록해도 행이 하나다 | DB 유니크 제약 + `AgentIngestIntegrationTest` | `docs/design-docs/failures/005-enroll-race-duplicate-rows.md` | 자동 |
| AGT-16 | 브로커 ack 를 못 받으면 200 이 아니라 503 이다 | `AgentIngestIntegrationTest.브로커_ack_실패는_503_이다` | `docs/design-docs/decisions/018-collector-waits-for-broker-ack.md` | 자동 |
| AGT-19 | 설치 스크립트에 설정된 서버 인증서 지문이 박히고, 지문이 없거나 모양이 틀리면 스크립트를 내주지 않는다 | `InstallControllerTest.scriptsCarryConfiguredFingerprint`, `InstallControllerTest.refusesToServeWithoutFingerprint`, `InstallControllerTest.refusesToServeWithMalformedFingerprint`, `InstallScriptTest.fingerprintAcceptsKeytoolOutputAsIs`, `InstallScriptTest.fingerprintRejectsAnythingButSha256` | `docs/design-docs/decisions/024-install-pins-configured-cert-fingerprint.md` | 자동 |
| AGT-20 | 설치 스크립트에 받은 인증서를 대조 없이 고정하는 길(TOFU)이 없다. Windows 검증 콜백이 늘 참이 아니다 | `InstallTemplatesTest.templatesPinOnlyTheConfiguredFingerprint` | `docs/design-docs/decisions/024-install-pins-configured-cert-fingerprint.md` | 자동 |
| AGT-21 | 바이너리의 `.sha256` 을 못 받으면 설치를 멈춘다 | `InstallTemplatesTest.missingSha256AbortsInstall` | `docs/design-docs/decisions/024-install-pins-configured-cert-fingerprint.md` | 자동 |

## 센서 설정

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-14 | 플랫폼별로 켤 센서가 갈린다 | `SensorConfigTest` 전체 | `docs/design-docs/decisions/008-own-go-agent.md` | 자동 |
| AGT-15 | 켤 센서가 하나도 없으면 에이전트가 기동을 거부한다 | `agent/README.md` 의 기동 절차. 조용히 0건으로 도는 것을 막는다 | `docs/design-docs/decisions/008-own-go-agent.md` | 자동 |
| AGT-22 | eslogger 줄이 4MB 를 넘으면 그 줄만 건너뛰고(개수를 로그로 남기고) 뒤의 이벤트를 계속 읽는다 | `agent/internal/sensor/eslogger_darwin_test.go::TestESLoggerPumpSkipsOverlongLineAndContinues`, `agent/internal/sensor/eslogger_darwin_test.go::TestESLoggerSensorSurvivesOverlongLine` | `docs/design-docs/decisions/023-skip-overlong-eslogger-line.md` | 자동 |
| AGT-23 | 읽기가 끝나면 자식(eslogger)을 먼저 죽이므로 센서가 영원히 막히지 않는다 | `agent/internal/sensor/eslogger_darwin_test.go::TestESLoggerSensorDoesNotHangWhenPumpEndsWhileChildAlive` | `docs/design-docs/decisions/023-skip-overlong-eslogger-line.md` | 자동 |
| AGT-24 | 센서가 전부 오류로 멈추면 에이전트가 오류로 끝난다. 일부만 멈추면 나머지로 계속 수집한다 | `agent/internal/runtime/runtime_test.go::TestRunFailsWhenEverySensorStops`, `agent/internal/runtime/runtime_test.go::TestRunKeepsGoingWhenOneSensorStops` | `docs/design-docs/decisions/025-vendor-patched-golang-etw.md` | 자동 |
| AGT-25 | 서버 등록과 첫 하트비트가 실패해도 에이전트가 끝나지 않고 간격을 늘려 가며 다시 시도한다 | `agent/cmd/edrdog-agent/retry_test.go::TestRetryKeepsTryingUntilSuccess`, `agent/cmd/edrdog-agent/retry_test.go::TestRetryStopsWhenContextEnds` | `docs/design-docs/decisions/026-windows-cmdline-from-system-process-provider.md` | 자동 |
| AGT-26 | Windows 프로세스 시작은 같은 PID 의 시스템 프로세스 시작과 합쳐 명령행을 싣는다. 어느 쪽이 먼저 와도 합치고, 짝이 2초 안에 안 오거나 프로세스가 끝나면 시작 시각 그대로 내보낸다 | `agent/internal/sensor/etw_map_test.go::TestProcessJoinMergesCommandLineIntoHeldStart`, `agent/internal/sensor/etw_map_test.go::TestProcessJoinKeepsCommandLineThatArrivesFirst`, `agent/internal/sensor/etw_map_test.go::TestProcessJoinReleasesHeldStartOnStop`, `agent/internal/sensor/etw_map_test.go::TestProcessJoinExpiresStartsHeldTooLong`, `agent/internal/sensor/etw_map_test.go::TestProcessJoinEmitsAtOnceWhenPIDUnreadable` | `docs/design-docs/decisions/026-windows-cmdline-from-system-process-provider.md` | 자동 |
| AGT-27 | x64 Windows 에서 ETW 센서와 pktmon 이 실제로 열려 다섯 종(script, network, dns, file, l7)을 내고, 곧 끝나는 프로세스 50개 중 45개 이상의 명령행을 싣는다 | `agent/internal/sensor/etw_live_windows_test.go::TestETWLive` (`-tags etwlive`, 관리자 권한 Windows 에서 손으로 돌린다. 2026-11-11 Windows Server 2025 x64 러너에서 통과) | `docs/design-docs/decisions/026-windows-cmdline-from-system-process-provider.md` | 수동 |
| AGT-28 | root 데몬으로 돌아도 감시 경로의 `~` 가 `/Users` 아래 사용자 홈마다 펴진다(Shared, 숨김 항목 제외) | `agent/internal/sensor/eslogger_map_test.go::TestExpandWatchPathsForEveryHome`, `agent/internal/sensor/eslogger_map_test.go::TestUserHomesSkipsSharedAndHidden` | `docs/security/detection-test-2026-11-10/README.md` 결함 1 | 자동 |

## 수동 확인

| ID | 확인할 것 | 방법 | 상태 |
|---|---|---|---|
| AGT-M1 | macOS 실기기에서 4종 이벤트가 올라온다 | 에이전트 설치 후 전체 디스크 접근 권한을 켜고 `-selftest` 로 확인 | 수동 |
| AGT-M2 | Windows 실기기에서 ETW 센서가 이벤트를 낸다 | `agent/README.md` 의 Windows 실기기 검증 절차. Windows 11 ARM64 VM(UTM, 26300.9711)에서 다섯 종(process, network, dns, file, l7)이 로컬 서버까지 오는 것을 봤다(2026-11-11). `packaging/install-windows.ps1` 설치, 서비스 재시작, 재부팅 후 기동, 포트 값, kill 도 봤다. 곧 끝나는 프로세스 명령행은 50건 중 1건이었다가 ADR 026 뒤 50건 모두 실렸다. amd64 빌드도 x64 에뮬레이션으로 같은 시험을 통과했다. IPv6, x64 실기기, 서버가 내려주는 설치 스크립트는 아직 안 봤다 | 수동 |
| AGT-M3 | 인증서 고정이 실제로 다른 서버를 거부한다 | 다른 인증서로 서명된 서버를 가리켜 붙지 않는 것을 확인 | 수동 |
| AGT-M4 | 설치 스크립트가 지문이 다른 서버에서 `server.pem` 을 쓰기 전에 멈춘다 | macOS 두 스크립트의 대조 줄은 로컬 `openssl s_server` 로 일치, 불일치, 서버 없음을 돌려 확인했다(2026-11-10). Windows 두 스크립트는 실기기에서 틀린 지문으로 돌려 지문이 다르다로 멈추는지 봐야 한다(아직 안 봤다) | 수동 |

## 에이전트 보안 기능 (KISA BF-10, BF-11, BF-13)

자체시험, 무결성 검증, 감사기록. 왜 이렇게 정했는지는 `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md`. 서버 쪽 감사기록 저장과 조회는 `security.md` 의 감사기록 절이다.

| ID | 성립해야 할 것 | 검증 | 출처 | 상태 |
|---|---|---|---|---|
| AGT-40 | 자체시험이 서버 인증서 고정(https, 읽을 수 있는 CA)이 설정됐는지 본다. 고정이 없으면 실패다 | `agent/internal/selfcheck/selfcheck_test.go::TestPinningPassesWithHTTPSAndLoadableCA`, `agent/internal/selfcheck/selfcheck_test.go::TestPinningFailsWithoutCACert`, `agent/internal/selfcheck/selfcheck_test.go::TestPinningFailsOnPlainHTTP`, `agent/internal/selfcheck/selfcheck_test.go::TestPinningFailsWhenCACertUnreadable` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-41 | 자체시험이 설정 파일을 다른 사용자가 읽을 수 있는지, 고정 인증서를 다른 사용자가 쓸 수 있는지 본다(macOS). Windows 는 ACL 을 읽지 않아 건너뛴다 | `agent/internal/selfcheck/perm_unix_test.go::TestConfigPermissionPassesAt0600`, `agent/internal/selfcheck/perm_unix_test.go::TestConfigPermissionFailsWhenOthersCanRead`, `agent/internal/selfcheck/perm_unix_test.go::TestCACertPermissionFailsWhenOthersCanWrite`, `agent/internal/selfcheck/perm_unix_test.go::TestCACertPermissionPassesAt0644` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-42 | 자체시험이 띄운 센서가 전부 살아 있는지 본다. 하나라도 멈추면 실패이고 멈춘 센서 이름이 사유에 있다 | `agent/internal/runtime/runtime_test.go::TestSensorStateReportsStoppedSensors`, `agent/internal/selfcheck/selfcheck_test.go::TestSensorsFailWhenAnyStopped`, `agent/internal/selfcheck/selfcheck_test.go::TestSensorsPassWhenAllRunning`, `agent/internal/selfcheck/selfcheck_test.go::TestSensorsSkippedBeforeStart` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-43 | 자체시험과 무결성 검증을 기동 시와 설정한 주기마다 돌려 감사기록 한 건씩 남긴다. 실패해도 에이전트는 계속 돈다 | `agent/cmd/edrdog-agent/securitycheck_test.go::TestRunChecksRecordsOneAuditEventPerKind`, `agent/cmd/edrdog-agent/lifecycle_test.go::TestEveryCallsRepeatedlyAndStopsWithContext`, `agent/internal/config/config_test.go::TestLoadKeepsExplicitSelfCheckInterval` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-44 | 관리자가 엔드포인트에서 `-security-check` 로 요청하면 항목별 결과를 출력하고, 실패가 있으면 종료 코드 1 이다 | `agent/cmd/edrdog-agent/securitycheck_test.go::TestSecurityCheckCommandReturnsFalseOnFailureAndPrintsEachItem` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-45 | 자기 실행 파일과 설정 파일의 SHA-256 을 기준값과 비교한다. 바뀐 쪽만 실패한다. 통과한 항목은 현재 해시를 결과에 싣는다 | `agent/internal/selfcheck/integrity_test.go::TestIntegrityPassesRightAfterBaseline`, `agent/internal/selfcheck/integrity_test.go::TestIntegrityFailsWhenBinaryChanged`, `agent/internal/selfcheck/integrity_test.go::TestIntegrityFailsWhenConfigChanged`, `agent/internal/selfcheck/integrity_test.go::TestIntegrityOKDetailCarriesHashForServerSideComparison`, `agent/internal/selfcheck/integrity_test.go::TestIntegrityFailsWhenBinaryMissing` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-46 | 기준값 파일이 없거나 깨졌으면 무결성이 실패다. 지우는 것이 우회가 되지 않는다 | `agent/internal/selfcheck/integrity_test.go::TestIntegrityFailsWhenBaselineMissing`, `agent/internal/selfcheck/integrity_test.go::TestIntegrityFailsWhenBaselineCorrupt` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-47 | `-record-baseline` 이 설정 파일 옆에 기준값을 쓴다. 설치 스크립트 두 개가 설정 파일을 쓴 뒤 이것을 부른다 | `agent/cmd/edrdog-agent/securitycheck_test.go::TestRecordBaselineCommandWritesBaselineNextToConfig`, `agent/internal/selfcheck/integrity_test.go::TestBaselineSitsNextToConfig` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-48 | 감사기록이 서버 계약(`ts` epoch millis, `type`, `result`, `detail`)대로 직렬화된다 | `agent/internal/audit/audit_test.go::TestEventSerializesToServerContract`, `agent/internal/transport/client_test.go::TestSendAuditPostsEventsWithNodeKey` | `docs/agent-protocol.md` | 자동 |
| AGT-49 | 전송에 실패하면 감사기록을 순서대로 되돌려 다시 보낸다. 로그는 상한이 있고 넘치면 오래된 것부터 버린다. 한 요청은 100건 이하다 | `agent/internal/audit/audit_test.go::TestFlushKeepsRecordsInOrderWhenSendFails`, `agent/internal/audit/audit_test.go::TestLogDropsOldestWhenFull`, `agent/internal/audit/audit_test.go::TestFlushSplitsIntoServerSizedBatches`, `agent/internal/transport/client_test.go::TestSendAuditFailsOnServerError` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-50 | 주요 사건이 감사기록으로 서버에 간다: 시작, 서버 등록, 센서 정지, 대응 명령 결과, 종료(마지막 전송으로) | `agent/cmd/edrdog-agent/lifecycle_test.go::TestRunAgentSendsLifecycleAuditRecords`, `agent/internal/runtime/runtime_test.go::TestOnSensorStopReceivesSensorName`, `agent/internal/command/loop_test.go::TestOnResultIsCalledForExecutedCommand`, `agent/internal/command/loop_test.go::TestOnResultIsNotRepeatedForReplayedCommand` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-51 | `POST /api/agent/audit` 는 node_key 가 없거나 틀리면 401 이고 api-service 로 넘기지 않는다 | `AgentAuditIntegrationTest.auditRejectsMissingNodeKeyWith401`, `AgentAuditIntegrationTest.auditRejectsWrongNodeKeyWith401` | `docs/agent-protocol.md` | 자동 |
| AGT-52 | 요청은 100건, 본문 8MB, `detail` 1000자가 상한이다. 100건 초과와 events 누락은 400, 본문 초과는 413 | `AgentAuditIntegrationTest.auditRejectsMoreThan100EventsWith400`, `AgentAuditIntegrationTest.auditAcceptsExactly100Events`, `AgentAuditIntegrationTest.auditRejectsMissingEventsWith400`, `AgentAuditIntegrationTest.auditRejectsBodyOver8MbWith413`, `AgentAuditIntegrationTest.auditTruncatesOverlongDetail` | `docs/design-docs/decisions/021-cap-all-request-bodies-at-8mb.md` | 자동 |
| AGT-53 | api-service 로 넘기는 tenantId 와 host 는 node_key 로 푼 값이다. 본문에 다른 값이 있어도 믿지 않는다. 몸통과 `X-Internal-Key` 가 계약대로다 | `AgentAuditIntegrationTest.auditForwardsEventsWithTenantAndHostResolvedFromNodeKey`, `AuditClientTest.forwardPostsContractBodyWithInternalKey` | `docs/design-docs/decisions/004-server-resolves-tenant-from-node-key.md` | 자동 |
| AGT-54 | 모양이 틀린 한 건(`type` 모양, `result` 값)은 그 건만 버리고 나머지는 넘긴다. 남는 것이 없으면 api-service 를 부르지 않는다 | `AgentAuditIntegrationTest.auditDropsMalformedEventsAndKeepsTheRest`, `AgentAuditIntegrationTest.auditWithOnlyMalformedEventsDoesNotCallApiService` | `docs/design-docs/decisions/030-agent-selftest-integrity-audit.md` | 자동 |
| AGT-55 | api-service 로 못 넘기면 200 이 아니라 503 이다. 에이전트가 같은 기록을 다시 보낸다 | `AgentAuditIntegrationTest.auditReturns503WhenApiServiceUnavailable`, `AuditClientTest.forwardThrowsWhenApiServiceRejects`, `AuditClientTest.forwardThrowsWhenApiServiceIsDown` | `docs/design-docs/decisions/018-collector-waits-for-broker-ack.md` | 자동 |

| ID | 확인할 것 | 방법 | 상태 |
|---|---|---|---|
| AGT-M5 | 설치 스크립트가 기준값을 쓰고, 바이너리나 설정 파일을 바꾸면 무결성 실패가 서버 감사기록에 올라온다 | macOS 에서 `install-macos.sh` 로 설치, `sudo edrdog-agent -config /etc/edrdog/config.json -security-check` 가 통과하는 것을 보고, 설정 파일을 한 글자 고쳐 다시 돌려 `config_integrity` 가 실패하는 것을 본다. 에이전트를 재기동해 서버 감사기록 조회에서 `integrity_check` 가 `failure` 로 보이는 것까지 확인한다. 서버 감사기록 API 가 배포된 뒤에 한다 | 수동 |
| AGT-M6 | Windows 설치 스크립트가 `-record-baseline` 을 부르고 `integrity.json` 에 ACL 을 건다 | Windows 실기기에서 `install-windows.ps1` 를 돌려 `C:\ProgramData\EDRdog\integrity.json` 이 생기고 `icacls` 가 SYSTEM 과 Administrators 만 보여 주는지 본다(아직 안 봤다) | 수동 |
