# 탐지 룰 (Sigma 부분집합)

detector 가 이벤트 하나만 보고 판정하는 룰들이다. 파일 하나가 룰 하나이고, 파일을 고치면 코드 변경 없이 반영된다.
이 폴더는 기본값(jar 에 번들)이다. `DETECTION_RULES_DIR` 이 있으면 그 디렉터리로 바꿔 끼우고 30초마다 다시 읽는다.
운영에서는 `k8s/detector-rules.yaml`(ConfigMap)이 이 파일들에서 만들어져 마운트된다.
결정의 근거는 `docs/design-docs/decisions/020-sigma-subset-data-rules.md`.

## 출처와 라이선스

룰은 [SigmaHQ/sigma](https://github.com/SigmaHQ/sigma) 의 공개 룰(커밋 `d7c9da1`, 2026-11-10)을 이 에이전트가 보내는 필드에 맞게 옮긴 것이다.
라이선스는 [Detection Rule License (DRL) 1.1](https://github.com/SigmaHQ/Detection-Rule-License)이다. 룰 파일마다 맨 위에 원본 경로, SigmaHQ `id`, 바꾼 점을 적었고
`author` 는 원본 그대로 두었다. `description` 은 원본 문장을 줄인 경우가 있다. 룰을 고치거나 더 가져올 때도 이 표기를 지우지 않는다.
SigmaHQ 에 대응 룰이 없어 직접 쓴 룰(`CLICKFIX_RUN_DIALOG_SHELL`, `GPG_BATCH_ENCRYPT`)은 파일 맨 위에 `EDRdog 자체 룰`이라고 적고 근거를 달았다. 표의 SigmaHQ id 칸도 그렇게 표시한다.

## 파일 형식

Sigma 문법을 따르되 아래만 읽는다. 그 밖의 구문은 그 룰만 빼고 로그에 이유를 남긴다(`SigmaParser` 참고).

| 항목 | 지원 |
|---|---|
| `name` (필수, SigmaHQ `name` 과 같은 자리) | 소문자, 숫자, `_`. 대문자로 바꾼 값이 알림의 `ruleId` 다. 바꾸지 않는다(집계와 쿨다운 키) |
| `tags` | 첫 `attack.tNNNN(.NNN)` 이 알림의 MITRE 다 |
| `level` | `informational/low/medium` → MEDIUM(알림만), `high` → HIGH(프로세스 종료 권고), `critical` → CRITICAL |
| `logsource.category` | `process_creation`(process+script 이벤트), `file_event`, `dns_query`, `network_connection` |
| 필드 | process_creation: `Image`, `ParentImage`, `CommandLine` / file_event: `TargetFilename` / dns_query: `QueryName`, `Image` / network_connection: `DestinationIp`, `DestinationPort`, `Image` |
| modifier | `contains`, `startswith`, `endswith`, `all`. 값 목록은 OR, 대소문자 무시 |
| selection | 필드 맵, 또는 맵 목록(OR) |
| condition | `and` `or` `not` 괄호, `1 of x*`, `all of x*`, `them` |

주의할 점.

- 에이전트는 `Image`, `ParentImage` 를 경로 없이 **파일명만** 보낸다. `Image|endswith: '\cmd.exe'` 처럼 경로가 붙은 값은 파일명 일치로 읽는다. 경로 조각 검색(`Image|contains: '\temp\'`)은 거절된다.
- `OriginalFileName`, 레지스트리, 프로세스 접근 같은 이벤트는 에이전트가 보내지 않아 쓸 수 없다. 옮길 때 그 조건은 뺐다.
- 종료(kill)는 프로세스가 대상이라 `process_creation` 이 아닌 룰은 `high` 로 적어도 MEDIUM 이 된다.
- 에이전트는 `-enc`, `-password` 같은 값을 `<redacted>` 로 가려 보낸다. 가려진 값의 내용으로는 판정할 수 없다.

## 룰 목록

| ruleId | MITRE | 심각도 | SigmaHQ id |
|---|---|---|---|
| `BCDEDIT_BOOT_TAMPER` | T1542.003 | MEDIUM | `c9fbe8e9-119d-40a6-9b59-dd58a5d84429` |
| `BITSADMIN_DOWNLOAD` | T1105 | MEDIUM | `d059842b-6b9d-4ed1-b5c3-5b89143c6ede` |
| `CERTUTIL_DOWNLOAD` | T1105 | MEDIUM | `19b08b1c-861d-4e75-a1ef-ea0c1baf202b` |
| `CLICKFIX_RUN_DIALOG_SHELL` | T1204.004 | MEDIUM | 자체 룰(EDRdog) |
| `CMD_STDIN_REDIRECT` | T1059.003 | MEDIUM | `241e802a-b65e-484f-88cd-c2dc10f9206d` |
| `CURL_SUSP_DOWNLOAD` | T1105 | MEDIUM | `e218595b-bbe7-4ee5-8a96-f32a24ad3468` |
| `DEFENDER_DISABLE_MONITORING` | T1685 | MEDIUM | `a7ee1722-c3c5-aeff-3212-c777e4733217` |
| `DNS_MEGA_USERSTORAGE` | T1567.002 | MEDIUM | `613c03ba-0779-4a53-8a1f-47f914a4ded3` |
| `DNS_TOR_GATEWAY` | T1090.003 | MEDIUM | `b55ca2a3-7cff-4dda-8bdd-c7bfa63bf544` |
| `EVENTLOG_CLEAR` | T1685.005 | MEDIUM | `cc36992a-4671-4f21-a91d-6c2b72a2edf5` |
| `FILE_LSASS_DUMP_NAME` | T1003.001 | MEDIUM | `a5a2d357-1ab8-4675-a967-ef9990a59391` |
| `FILE_MIMIKATZ_ARTIFACTS` | T1558 | MEDIUM | `9e099d99-44c2-42b6-a6d8-54c3545cab29` |
| `FINGER_EXECUTION` | T1105 | MEDIUM | `af491bca-e752-4b44-9c86-df5680533dbc` |
| `FLTMC_UNLOAD_SYSMON` | T1070 | MEDIUM | `4d7cda18-1b12-4e52-b45c-d28653210df8` |
| `FSUTIL_USAGE` | T1070 | MEDIUM | `add64136-62e5-48ea-807e-88638d02df1e` |
| `GPG_BATCH_ENCRYPT` | T1486 | MEDIUM | 자체 룰(EDRdog) |
| `MACOS_CHROMIUM_HEADLESS_DEBUGGING` | T1185 | MEDIUM | `3e8207c5-fcd2-4ea6-9418-15d45b4890e4` |
| `MACOS_CSRUTIL_DISABLE` | T1518.001 | MEDIUM | `3603f18a-ec15-43a1-9af2-d196c8a7fec6` |
| `MACOS_DISABLE_SECURITY_TOOLS` | T1685 | MEDIUM | `ff39f1a6-84ac-476f-a1af-37fcdf53d7c0` |
| `MACOS_DSCL_ADD_ADMIN` | T1078.003 | MEDIUM | `b743623c-2776-40e0-87b1-682b975d0ca5` |
| `MACOS_INFOSTEALER_BROWSER_CREDENTIALS` | T1555.003 | MEDIUM | 자체 룰 |
| `MACOS_INFOSTEALER_COOKIE_THEFT` | T1539 | MEDIUM | 자체 룰 |
| `MACOS_INFOSTEALER_EXFIL_CURL_ARCHIVE` | T1041 | MEDIUM | 자체 룰 |
| `MACOS_INFOSTEALER_LOCAL_DATA` | T1005 | MEDIUM | 자체 룰 |
| `MACOS_JXA_IN_MEMORY_EXECUTION` | T1059.002 | MEDIUM | `f1408a58-0e94-4165-b80a-da9f96cf6fc3` |
| `MACOS_KEYCHAIN_DUMP` | T1555.001 | MEDIUM | `b120b587-a4c2-4b94-875d-99c9807d6955` |
| `MACOS_KEYCHAIN_FILE_COPY` | T1555.001 | MEDIUM | 자체 룰 |
| `MACOS_OFFICE_SUSP_CHILD_PROCESSES` | T1059.002 | MEDIUM | `69483748-1525-4a6c-95ca-90dc8d431b68` |
| `MACOS_OSACOMPILE_RUNONLY_EXECUTION` | T1059.002 | MEDIUM | `b9d9b652-d8ed-4697-89a2-a1186ee680ac` |
| `MACOS_OSASCRIPT_SHELL_DECODE_EXEC` | T1059.002 | MEDIUM | 자체 룰 |
| `MACOS_PLISTBUDDY_LAUNCHD_PERSISTENCE` | T1543.001 | MEDIUM | `65d506d3-fcfe-4071-b4b2-bcefe721bbbb` |
| `MACOS_SYSADMINCTL_ADD_ADMIN` | T1078.003 | MEDIUM | `652c098d-dc11-4ba6-8566-c20e89042f2b` |
| `MACOS_TMUTIL_DELETE_BACKUP` | T1490 | MEDIUM | `452df256-da78-427a-866f-49fa04417d74` |
| `MACOS_XATTR_REMOVE_QUARANTINE` | T1553.001 | MEDIUM | `f5141b6d-9f42-41c6-a7bf-2a780678b29b` |
| `MIMIKATZ_COMMAND_LINE` | T1003.001 | HIGH | `a642964e-bead-4bed-8910-1bb4d63e3b4d` |
| `MPCMDRUN_DOWNLOAD_ARBITRARY_FILE` | T1105 | MEDIUM | `46123129-1024-423e-9fae-43af4a0fa9a5` |
| `MPCMDRUN_REMOVE_DEFINITIONS` | T1685 | MEDIUM | `9719a8aa-401c-41af-8108-ced7ec9cd75c` |
| `MSHTA_REMOTE_HTA` | T1218.005 | MEDIUM | `b98d0db6-511d-45de-ad02-e82a98729620` |
| `MSIEXEC_WEB_INSTALL` | T1105 | MEDIUM | `f7b5f842-a6af-4da5-9e95-e32478f3cd2f` |
| `NETSH_FIREWALL_DISABLE` | T1686.003 | MEDIUM | `57c4bf16-227f-4394-8ec7-1b745ee061c3` |
| `NET_USER_ADD` | T1136.001 | MEDIUM | `cd219ff3-fa99-45d4-8380-a7d15116c6dc` |
| `NTDSUTIL_SNAPSHOT_MOUNT` | T1003.003 | MEDIUM | `a58353df-af43-4753-bad0-cd83ef35eef5` |
| `POWERSHELL_AMSI_INIT_FAILED_BYPASS` | T1685 | MEDIUM | `30edb182-aa75-42c0-b0a9-e998bb29067c` |
| `POWERSHELL_BASE64_INVOKE` | T1027 | MEDIUM | `6385697e-9f1b-40bd-8817-f4a91f40508e` |
| `POWERSHELL_DEFENDER_EXCLUSION` | T1685 | MEDIUM | `17769c90-230e-488b-a463-e05c08e9d48f` |
| `POWERSHELL_DOWNLOAD_PATTERNS` | T1059.001 | MEDIUM | `3b6ab547-8ec2-4991-b9d2-2b06702a48d7` |
| `POWERSHELL_ENCODED_COMMAND` | T1059.001 | MEDIUM | `ca2092a1-c273-4878-9b4b-0d60115bf5ea` |
| `POWERSHELL_FROMBASE64STRING` | T1027 | MEDIUM | `e32d4572-9826-4738-b651-95fa63747e8a` |
| `POWERSHELL_INVOKE_WEBREQUEST_DOWNLOAD` | T1105 | MEDIUM | `5e3cc4d8-3e68-43db-8656-eaaeefdec9cc` |
| `POWERSHELL_SHADOWCOPY_DELETE` | T1490 | HIGH | `21ff4ca9-f13a-41ad-b828-0077b2af2e40` |
| `POWERSHELL_SUSP_DOWNLOAD_PATTERNS` | T1059.001 | MEDIUM | `e6c54d94-498c-4562-a37c-b469d8e9a275` |
| `PROCDUMP_LSASS` | T1003.001 | HIGH | `5afee48e-67dd-4e03-a783-f74259dcf998` |
| `REG_BITLOCKER_POLICY` | T1486 | MEDIUM | `0e0255bf-2548-47b8-9582-c0955c9283f5` |
| `REG_DUMP_SENSITIVE_HIVES` | T1003.002 | HIGH | `fd877b94-9bb5-4191-bb25-d79cbd93c167` |
| `SCHTASKS_CREATE_TEMP` | T1053.005 | MEDIUM | `39019a4e-317f-4ce3-ae63-309a8c6b53c5` |
| `WBADMIN_DELETE_ALL_BACKUPS` | T1490 | HIGH | `639c9081-f482-47d3-a0bd-ddee3d4ecd76` |
| `WMIC_NAMESPACE_DEFENDER` | T1047 | MEDIUM | `51cbac1e-eee3-4a90-b1b7-358efb81fa0a` |
| `WMIC_PROCESS_CREATION` | T1047 | MEDIUM | `526be59f-a573-4eea-b5f7-f0973207634d` |
| `WMIC_SUSP_PROCESS_CREATION` | T1047 | MEDIUM | `3c89a1e8-0fba-449e-8f1b-8409d6267ec8` |
