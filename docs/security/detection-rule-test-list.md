# 탐지 룰 보강과 공격 시험 리스트 (Windows, macOS)

어떤 탐지 룰을 보강하고 어떤 공격 시험으로 확인할지를 정한다. 작성 2026-11-12, 작성 중이다.

## 1. 왜 기준이 필요한가

EDR 이라면 반드시 갖춰야 한다는 공식 룰셋은 없다. KISA EDR 평가 항목(V1.4)도 탐지 기능이 동작하는지(BF-15)와 공식 시료 탐지율(SE-01)만 보고, 어떤 룰이 있어야 하는지는 정하지 않는다(`kisa-edr-performance-criteria.md`). 그래서 보강 대상을 고르는 근거를 외부 자료에서 가져온다.

## 2. 기준

1. **무엇을 막을지**: 여러 보안 회사의 최신 연간 위협 보고서가 공통으로 많이 관찰했다고 꼽은 ATT&CK 기법. 한 회사 자료만 쓰지 않는다. Windows 와 macOS 를 따로 모은다.
2. **어떤 룰로 막을지**: 그 기법을 잡는 SigmaHQ 공개 룰 중 경보 등급(level medium 이상)이고 status 가 stable 또는 test 인 것. experimental, deprecated 는 쓰지 않는다.
3. **넣지 않는 것**: 단독으로 잡으면 평소 개발·관리 작업에도 걸리는 조회 명령(시스템 정보, 프로세스 목록 등). 경보 대신 다른 탐지와 이어 볼 맥락 정보로 둔다. 뺀 이유는 표에 적는다.
4. **확인 방법**: 룰을 보강한 뒤 같은 기법의 Atomic Red Team(Red Canary 공개 시험 도구) 시험을 실행해 수집부터 알림까지 이어지는지 본다. 룰이 없는 기법은 시험해도 실패가 뻔하므로 보강 전에는 시험하지 않는다. 보강 전 상태는 룰 대응 수로 적는다.

### 근거 자료

조사일 2026-11-12. 회사마다 집계 방식이 달라 순위 대신 "몇 곳이 그 기법을 꼽았는가" 로 합친다. 원문이 ATT&CK ID 를 적지 않은 경우는 본문에 적힌 행위에만 우리가 ID 를 붙였고 표에 따로 표시한다.

**macOS** (12곳)

| 출처 | 판 | 확인 범위 |
|---|---|---|
| Red Canary Threat Detection Report, Mac malware | 2026(2025 데이터) | 웹 전문 |
| Red Canary 블로그 Atomic, Odyssey, Poseidon | 2025-10 | 전문 |
| Microsoft Defender Experts, Hunting infostealers: macOS | 2026-04 | 전문 |
| Objective-See, The Mac Malware of 2025 | 2026-01 | 약 68% |
| Jamf Security 360 Mac 2026, Jamf Threat Labs | 2025-09 | 블로그 전문, 연간 보고서는 요약 |
| Sophos, ClickFix and macOS infostealers | 2026-02 | 전문 |
| Moonlock(MacPaw) macOS 위협 트렌드 | 2025-12, 2026 | 2026 전문 |
| Elastic detection-rules, macOS 룰 저장소 | 2026-11 | 룰 99개(위협 순위가 아니라 대형 벤더가 무엇을 탐지하는지의 참고) |
| Hive Pro 권고(CrowdStrike SHAMOS 분석 기반) | 2025-08 | 2차 요약 |
| Hunt.io, ClickFix on macOS | 2025-07 | 전문 |
| Huntress, 가짜 Claude 페이지와 MacSync | 2026-08 | 전문 |
| Iru, Atomic Stealer returns | 2026-03 | 전문 |

못 본 곳: SentinelOne(접속 제한), CrowdStrike 원문, Red Canary·Jamf 연간 PDF 본문(내려받기에 개인정보 입력이 필요하거나 용량 초과). URL 은 `docs/security/detection-rule-sources.md`(작성 예정)에 남긴다.

**Windows** (기법 순위가 있는 5곳)

| 출처 | 판 | 집계 기준 | 확인 범위 |
|---|---|---|---|
| Red Canary Threat Detection Report | 2026(2025 데이터) | 고객 환경의 확인된 위협 탐지 | 공개 PDF 차트(기법 ID 는 이름으로 추론) |
| Picus Red Report | 2026 | 악성 파일 110만 개의 행위 | 공개 요약 페이지 |
| Mandiant M-Trends | 2026(2025 사고) | 침해사고 대응 | PDF 표(제3자 호스팅 사본) |
| Palo Alto Networks Unit 42 Global Incident Response Report | 2026(2025, 750건 이상) | 침해사고 대응, 전술별 비율 | PDF 부록(제3자 호스팅 사본) |
| CrowdStrike Threat Hunting Report | 2026(2025-07~2026-06) | 위협 헌팅, 전술별 상위 기법 | PDF 그림(제3자 호스팅 사본) |

기법 순위가 없어 합산에서 뺀 곳: Sophos Active Adversary 2026(도구 순위만), Elastic Global Threat Report 2025(전술 수준만), CrowdStrike Global Threat Report 2026(요약 수치만), Microsoft Digital Defense Report 2025, Huntress 2026, Kaspersky 2026(요약만).

### 예상 질문

| 질문 | 답 |
|---|---|
| 왜 이 룰들인가 | 여러 회사의 최신 위협 보고서가 공통으로 꼽은 기법이고, 커뮤니티 표준 룰셋(SigmaHQ)이 경보 등급으로 잡는 룰이다 |
| 왜 한 회사 자료가 아닌가 | 회사마다 고객군과 집계 방식이 달라 한 곳만 쓰면 치우친다 |
| 왜 조회 명령은 안 잡나 | 정상 작업과 구분이 안 돼 단독 경보는 오탐이 많다. 상용 EDR 도 보통 맥락 정보로 쓴다 |
| 시험이 룰에 맞춰 고른 것 아닌가 | 맞다. 그래서 결과는 "이 기준 범위 안의 대응률" 로만 적고, 범위 밖 기법의 탐지율이라고 하지 않는다 |
| KISA 시험과 같은가 | 아니다. KISA 시나리오와 시료는 비공개다. KISA 평가 항목(수집, 탐지, 대응)을 기준으로 한 자체 시험이다 |

## 3. 절차

| 단계 | 내용 | 상태 |
|---|---|---|
| 1 | 위협 보고서에서 OS 별 상위 기법 모으기 | 완료 |
| 2 | 우리 룰셋과 SigmaHQ 룰을 기법별로 대조 | 완료 |
| 3 | 보강 리스트 확정(위험도로 필수, 권장, 후순위, 보류로 나눔) | 초안 |
| 4 | 룰 보강과 단위 테스트(걸리는 경우, 안 걸리는 경우) | 대기 |
| 5 | 시험 목록 고정(6절), Atomic Red Team 시험 실행, 결과 문서화 | 목록 고정, 실행 대기 |

## 4. 지금 룰 대응 현황 (2026-11-12, dev `2e497b2`)

### macOS

SigmaHQ macOS 룰 중 경보 등급 48개(SigmaHQ `d7c9da1`, 2026-11-10, status 구분 전) 가운데 같은 룰이 우리에게 있는 것은 7개다.

| 우리 룰 | 대응 SigmaHQ 룰 |
|---|---|
| `macos_csrutil_disable.yml` | SIP 해제 |
| `macos_disable_security_tools.yml` | 보안 도구 끄기 |
| `macos_dscl_add_admin.yml` | 관리자 그룹 추가(dscl) |
| `macos_sysadminctl_add_admin.yml` | 관리자 그룹 추가(sysadminctl) |
| `macos_keychain_dump.yml` | 키체인 자격증명 |
| `macos_plistbuddy_launchd_persistence.yml` | PlistBuddy 자동실행 등록 |
| `macos_tmutil_delete_backup.yml` | Time Machine 백업 삭제 |

그 밖에 Sigma 에서 low 등급인 `macos_xattr_remove_quarantine.yml` 과 코드 룰 5개(`Rules.java`: DOWNLOAD_AND_EXECUTE, SCRIPT_FROM_TEMP_PATH, FILE_IN_AUTORUN_PATH, WEAK_TLS_HANDSHAKE, SUSPICIOUS_PROCESS_CHAIN)가 있다.

### Windows

우리 Windows 룰은 25개다(Sigma 형식 20, 코드 룰 5). 보고서 상위 기법의 SigmaHQ 룰 중 같은 룰을 가진 것은 18개다(5절 보유).

## 5. 보강 리스트

### 나누는 기준: 위험도 = 빈도 × 피해

NIST SP 800-30 의 위험 평가처럼 발생 가능성(빈도)과 영향(피해) 두 축으로 나눈다.

| 축 | 높음 | 근거 |
|---|---|---|
| 빈도 | 조사한 보고서의 과반이 꼽은 기법. Windows 5곳 중 3곳 이상, macOS 12곳 중 7곳 이상 | 2절 근거 자료 |
| 피해 | SigmaHQ 룰 등급이 high 이상이거나, 룰이 피해가 생기는 공격 단계(자격증명 탈취, 방어 무력화, 내부 확산, 유출, 파괴·암호화)를 잡는 것 | 룰 등급은 SigmaHQ 작성자 판단, 공격 단계는 룰의 ATT&CK 전술 태그 |

| 등급 | 조건 |
|---|---|
| 필수 | 빈도 높음, 피해 높음 |
| 권장 | 둘 중 하나만 높음 |
| 후순위 | 둘 다 낮음 |
| 보류 | 등급과 관계없이 에이전트가 수집하지 않는 정보(레지스트리, PowerShell 스크립트 내용, Windows 이벤트 로그, DLL 로드, 실행 파일 원래 이름 등)가 필요한 룰. 수집 기능을 늘리는 별도 작업으로 넘긴다 |
| 제외 | 조회(Discovery) 전술만 잡는 룰. 단독 경보로는 오탐이 많아 맥락 정보로만 쓴다 |
| 보유 | 이미 같은 룰이 있다 |

"바로 넣을 수 있음" 에는 Sigma 룰의 OR 조건 중 우리가 받지 못하는 필드(OriginalFileName 등)만 빼면 나머지로 동작하는 룰을 포함한다. 기존 Windows 룰도 이 방식으로 옮겼다.

### 개수 (SigmaHQ `d7c9da1`, level medium 이상, status stable·test, 보고서에 나온 기법만)

| OS | 필수 | 권장 | 후순위 | 보류 | 제외 | 보유 |
|---|---|---|---|---|---|---|
| Windows | 189 | 264 | 106 | 957 | 45 | 18 |
| macOS SigmaHQ | 6 | 7 | 5 | 0 | 2 | 2 |
| macOS 직접 작성 | 3 | 5 | 0 | 0 | 0 | 0 |

### Windows 필수 189개 (high 170, critical 3, medium 16)

기법 분포: T1685 방어 무력화 36, T1059 PowerShell·명령 셸 33, T1027 난독화 21, T1036 위장 18, T1105 내려받기 17, T1021 원격 서비스 13, T1047 WMI 9, T1055 프로세스 주입 8, T1070 흔적 삭제 4, 그 밖 30.

### Windows 권장 264개

기법 분포: T1218 시스템 바이너리 악용 38, T1059 27, T1003 자격증명 덤프 21, T1105 15, T1047 14, T1036 10, T1546 9, T1548 8, T1053 8, T1112 8, T1219 8, 그 밖 98. 자격증명 덤프(T1003)는 보고서 5곳 중 2곳만 꼽아 빈도는 낮음이지만 피해가 커서 권장이다.

### macOS 필수 9개

| 출처 | 룰 | 빈도(12곳 중) | 피해 근거 |
|---|---|---|---|
| SigmaHQ | 실행 전용 osacompile | 10 | high |
| SigmaHQ | JXA 메모리 실행(osascript) | 10 | high |
| SigmaHQ | Office 의 의심 자식 프로세스 | 10 | high |
| SigmaHQ | chflags 숨김 표시 | 10 | 자격증명 탈취 전술 |
| SigmaHQ | 스크립트 편집기 의심 실행 | 10 | 방어 무력화 전술 |
| SigmaHQ | Binary Padding(해시 바꾸기) | 7 | high |
| 직접 작성 | 브라우저 자격증명(`Login Data`, `Web Data`) 복사 | 9 | 자격증명 탈취 |
| 직접 작성 | 쿠키 탈취(`Cookies.binarycookies`, 브라우저 Cookies) | 7 | 자격증명 탈취 |
| 직접 작성 | `/tmp` 압축 파일을 `curl -X POST`/`-F` 로 전송 | 8 | 유출 |

### macOS 권장 12개

SigmaHQ 7개: Credentials In Files(high, 빈도 5), AppleScript 실행, 의심 애플릿의 osacompile, 클립보드 접근(osascript), 메모리 내려받기 후 컴파일, nscurl 내려받기, hdiutil 디스크 이미지 마운트(모두 medium, 빈도 7~10).

직접 작성 5개: 붙여넣기 실행(ClickFix, `curl ... | base64 -d | bash`), 셸 로더(`sh -c` 안의 base64·gunzip 해독 후 실행), 가짜 비밀번호 창(osascript `display dialog` + `hidden answer`, 빈도 6), 민감 파일 수집(`NoteStore.sqlite` 등 복사), 압축 스테이징(`ditto -c -k` 로 `/tmp` 에 압축).

직접 작성 룰은 보고서에 적힌 실제 명령을 근거로 만들고 Elastic 공개 macOS 룰을 참고한다. 에이전트는 macOS 에서 파일 읽기 이벤트를 내지 않으므로(exec, create, rename, unlink 만) 명령행에 파일 경로가 드러나는 경우만 잡는다.

### macOS 후순위, 제외

후순위: sysctl·system_profiler 조회, launchctl 실행, 브라우저와 설치 패키지의 의심 자식 프로세스(모두 medium, 빈도 5~6). 제외: ioreg, sw_vers 조회.

전체 후보 목록(룰 파일 경로 포함)은 확정되면 `detection-rule-candidates.md` 로 옮긴다.

## 5-1. 이번 범위 (2026-11-12 결정)

5절 전체(Windows 필수·권장 453개, macOS 21개)는 1인 작업으로 4~6일이 걸려 이번에는 하지 않는다. 이번에는 단말 EDR 이 볼 수 있는 기법 15개만 한다.

### 기본 10개: Red Canary 2026 상위 10

| OS | 기법 | 근거 |
|---|---|---|
| Windows | T1059.001 PowerShell, T1059.003 명령 셸, T1105 파일 내려받기, T1047 WMI, T1204.004 붙여넣기 실행(ClickFix), T1027 난독화 | Red Canary 2026 상위 10 중 단말 기법. 나머지 4개(클라우드 계정, 클라우드 저장소, 메일 전달 규칙, 메일 숨김 규칙)는 클라우드와 메일 서버에서 일어나 단말 EDR 이 볼 수 없다. 6개 모두 Windows 보고서 5곳 중 3곳 이상이 꼽았다 |
| macOS | T1059.002 AppleScript, T1555.001 키체인, T1539 쿠키 탈취, T1005 로컬 데이터 수집 | Red Canary 2026 macOS 부분이 기법 번호를 직접 적은 것 |

### 추가 5개: 다른 보고서 과반이 꼽았고 피해가 큰 것

**Windows 3개** (근거: 기법 순위표가 있는 연간 보고서)

| 기법 | 꼽은 보고서 | 보고서가 적은 내용 |
|---|---|---|
| T1685 보안 도구 무력화 | Picus Red Report 2026, Unit 42 2026, CrowdStrike Threat Hunting Report 2026 | Picus 8위(악성코드의 14.18%), Unit 42 방어 회피 기법 중 20%(T1562 Impair Defenses), CrowdStrike 방어 무력화 1위(T1685 Disable or Modify Tools) |
| T1486 랜섬웨어 암호화 | Picus, Unit 42, CrowdStrike | Picus 10위(12.94%), Unit 42 피해 단계 기법 중 60%로 1위, CrowdStrike 피해 단계 1위. Mandiant 는 2025년 상위 10 에서 빠졌다고 적었다(암호화 대신 탈취 후 협박으로 이동) |
| T1070 흔적 삭제 | Mandiant M-Trends 2026, Unit 42, CrowdStrike | Mandiant 7위(사고의 24.8%, 세부 기법은 파일 삭제 T1070.004), Unit 42 방어 회피 기법 중 31%로 1위, CrowdStrike 은닉 단계에 파일 시각 위조(T1070.006). 보고서마다 세부 기법이 달라 룰도 세부 기법별로 고른다 |

**macOS 2개** (근거: 악성코드 분석 글. macOS 는 기법 순위를 내는 대형 연간 보고서가 거의 없어, 주요 보안 회사 분석 글에서 공통으로 나온 행동을 기준으로 삼았다)

| 기법 | 출처(12곳 중) | 보고서가 적은 내용 |
|---|---|---|
| T1555.003 브라우저 비밀번호 탈취 | 8곳. 확실 6곳: Objective-See, Sophos, Elastic 탐지 룰(기법 번호 직접 기재), Huntress, Hunt.io, Iru. 간접 2곳: Red Canary 2026(차단 규칙 예시의 `login data`, `web data`), Red Canary 블로그(브라우저 확장의 자격증명) | 브라우저 쿠키·로그인 정보 수집, 키체인에서 브라우저 암호 키를 꺼내 복호화. Microsoft 는 "브라우저 데이터" 라고만 적어 뺐다. 확실한 근거만 세면 과반(7곳)에 하나 모자란다 |
| T1041 탈취 데이터 유출 | 8곳: Red Canary 블로그, Microsoft, Objective-See, Sophos, CrowdStrike 분석(Hive Pro 2차 요약), Hunt.io, Huntress, Iru | `curl` 로 압축한 탈취 파일을 HTTP POST·PUT 으로 업로드. 기법 번호를 직접 적은 곳은 CrowdStrike 분석 1곳이고 나머지 7곳은 행동에 우리가 번호를 붙였다. Elastic 은 같은 행동을 T1567 로 분류한다. 룰은 기법 번호보다 행동(`/tmp` 압축 파일을 curl POST·PUT 으로 업로드) 기준으로 쓴다 |

### 넣지 않는 것 (5-2 확장 계획으로)

보고서 과반이 꼽았지만 이번에는 넣지 않는다. 원격 제어 도구(T1219), 위장(T1036), 원격 서비스(T1021)는 정상 관리 도구와 구분이 어려워 오탐 조정이 필요하다. 프로세스 주입(T1055)은 대부분 우리가 수집하지 않는 정보가 필요하다. 조회 명령(T1083, T1033, T1016)은 처음부터 제외다.

### 규모

기법마다 대표 룰 2~3개. 고르는 기준은 high 등급 이상이고 그 기법의 Atomic Red Team 시험에 실제로 걸리는 룰이다. SigmaHQ 룰이 없는 기법은 보고서에 적힌 실제 명령을 근거로 직접 만든다.

| OS | 기법 | SigmaHQ 에서 옮김 | 직접 작성 | 새 룰 |
|---|---|---|---|---|
| Windows | 9 | 약 19~22 | 1~2(ClickFix, 랜섬웨어 보조) | 약 21~24 |
| macOS | 6 | 3(AppleScript) | 4(쿠키, 로컬 데이터, 브라우저 비밀번호, 유출). 키체인은 이미 있다 | 7 |
| 합계 | 15 | 약 22~25 | 약 5~6 | 약 28~31 |

## 5-2. 확장 계획 (나중)

5절 전체를 넣을 때의 순서와 예상 시간이다. 시간은 1인 작업, Claude 병렬 작업 기준 추정치다.

| 단계 | 내용 | 예상 시간 | 사람이 할 일 |
|---|---|---|---|
| 1. 필수 198개 | 변환 스크립트, 룰 옮기기, 룰마다 테스트, PR | 1~2일 | PR 확인 |
| 2. 권장 중 피해 큰 것 | 자격증명 덤프 등 high 등급 | 반나절~1일 | 없음 |
| 3. 권장 나머지 | 오탐 위험이 큰 것을 하나씩 확인하며 넣기 | 1~2일 | 없음 |
| 오탐·성능 확인 | 로컬 스택을 띄워 평소처럼 쓰면서 걸리는 것 보기, 처리 지연 재기 | 단계마다 반나절 | 맥북 평소 사용, Windows VM 켜 두기 |
| Atomic Red Team 시험 | 보강한 기법을 실제로 실행해 수집부터 알림까지 확인 | 단계마다 반나절 | 에이전트 전체 디스크 접근 권한 재설정이 필요할 수 있음 |

시간이 늘 수 있는 곳: 오탐 조정(넣어 봐야 개수를 안다), Windows VM 확인, 룰 수가 늘어 처리 지연이 커지면 룰 검사 방식 변경(하루 정도). 보류 957개는 에이전트 수집 기능(레지스트리, PowerShell 스크립트 내용, Windows 이벤트 로그, DLL 로드)을 먼저 늘려야 한다.

## 6. 시험 설계

포스터와 발표에 쓰는 숫자는 이 절의 방식으로만 낸다. 원칙은 네 가지다.

1. **시험지를 먼저 고정한다.** 룰을 다 만들기 전에 시험 목록을 이 문서에 확정하고 커밋한다. 룰에 걸리는 시험만 골라 넣지 않는다. 고른 기법의 Atomic Red Team macOS 시험 중 안전한 것은 전부 넣고, 못 잡은 것도 그대로 적는다.
2. **시험지는 우리가 만들지 않는다.** Red Canary 의 공개 시험 도구 Atomic Red Team(커밋 `a0e3f9b`, 2026-11-06)의 시험을 고치지 않고 쓴다. 해당 시험이 없는 기법만 직접 만들고 따로 표시한다.
3. **놓친 것과 잘못 잡은 것을 같이 잰다.** 탐지율만이 아니라 평소 작업에서 생긴 오탐 수도 잰다.
4. **보강 전과 후를 같은 시험지로 비교한다.**

### 6-1. 판정 방법

MITRE ATT&CK Evaluations 의 탐지 분류를 줄여 쓴다. 시험마다 하나로 판정한다.

| 판정 | 뜻 |
|---|---|
| 기법 탐지 | 그 기법을 가리키는 알림이 났다(룰의 ATT&CK 태그가 시험 기법과 같다) |
| 일반 탐지 | 알림은 났지만 다른 기법 룰이 잡았다 |
| 수집만 | 이벤트는 들어왔지만 알림이 없다 |
| 미탐 | 이벤트도 알림도 없다 |

탐지율 = (기법 탐지 + 일반 탐지) / 시험 수. 기법 탐지만 센 값도 같이 적는다.

### 6-2. 시험 목록 (macOS, 고정)

대상: 5-1절의 macOS 6개 기법. Atomic Red Team macOS 시험 중 일반 사용자 권한으로 돌고 정리(cleanup)로 되돌릴 수 있는 것. 실행 전에 명령을 한 번 더 확인한다.

| # | 기법 | Atomic Red Team 시험 | 비고 |
|---|---|---|---|
| 1 | T1059.002 AppleScript | 1. AppleScript | |
| 2 | T1555.001 키체인 | 2. Export Certificate Item(s) | |
| 3 | T1555.001 키체인 | 4. Copy Keychain using cat utility | 내 키체인 파일 사본을 임시 폴더에 만들고 지운다. 실행 전 확인 |
| 4 | T1539 쿠키 탈취 | 3. Steal Chrome Cookies via Remote Debugging (Mac) | Chrome 필요 |
| 5 | T1539 쿠키 탈취 | 5. Copy Safari BinaryCookies files using AppleScript | |
| 6 | T1005 로컬 데이터 | 3. Copy Apple Notes database files using AppleScript | |
| 7 | T1555.003 브라우저 비밀번호 | 2. Search macOS Safari Cookies | |
| 8 | T1555.003 브라우저 비밀번호 | 14. Simulating Access to Chrome Login Data - MacOS | |
| 9 | T1555.003 브라우저 비밀번호 | 18. Query Chrome Login Data In Place with sqlite3 - MacOS | |
| 10 | T1041 유출 | 직접 작성: `/tmp` 의 zip 을 로컬 수신기(127.0.0.1)로 `curl -X POST` 업로드 | Atomic Red Team 에 macOS 시험이 없다. 외부로 보내지 않는다 |

뺀 것: T1555.001 1번(root 필요), T1555.001 3번(키체인에 인증서를 넣어 상태를 바꾼다).

### 6-3. 재는 것

| 지표 | 방법 | 조건 |
|---|---|---|
| 탐지율(보강 전) | 시험 명령을 보강 전 룰셋(dev `2e497b2`)에 넣은 단위 테스트 결과 | 룰만 보는 값 |
| 탐지율(보강 후) | 6-2 목록을 맥북에서 실제 실행, 수집부터 알림까지 확인 | 로컬 스택, 에이전트 1대 |
| 탐지 시간 | 시험 시작부터 알림까지 초, 중앙값 | 로컬 스택 |
| 오탐 | 보강 후 룰로 평소 개발 작업을 일정 시간 하며 난 알림 수 | 시간과 작업 내용을 같이 적는다 |
| Windows 룰 확인 | Atomic Red Team Windows 시험 명령을 넣은 단위 테스트 통과 수 | 에이전트 수집은 확인 안 됨(Windows VM 시험은 나중) |

### 6-4. 남기는 증거

시험마다 실행 명령, 시작 시각, 알림 원본(ClickHouse `alerts`), Grafana 화면을 남긴다. 룰셋 커밋, Atomic Red Team 커밋, SigmaHQ 커밋, 에이전트 버전을 결과 문서 첫머리에 적는다. 형식은 `detection-test-2026-11-10/` 을 따른다.

### 6-5. 포스터 문구 (결과가 나온 뒤 숫자를 채운다)

포스터에는 결과만 쓴다. 기법을 고른 기준은 출처 표기로 작게 둔다.

> **KISA EDR 평가 항목(수집·탐지·대응) 기준 자체 시험**
> Atomic Red Team(Red Canary 공개 공격 재현 도구) 시험 **n / m 탐지**

출처 표기(작게): 시험 기법은 Red Canary·Unit 42·CrowdStrike·Mandiant·Picus 등 위협 보고서 공통 상위 기법. 시험 목록은 룰 작성 전에 고정. 실행 환경 macOS 단말 1대(Windows 는 룰 단위 테스트만).

탐지 시간과 오탐 수는 포스터에서 뺀다. 결과 문서에는 남겨 질문이 오면 답한다.

쓰지 않는 표현: "KISA 시험 통과", "KISA 시나리오", "탐지율 100%"(분모 없이). KISA 시나리오와 시료는 비공개라 평가 항목만 기준으로 썼다.

## 7. 한계

- 공식 성능평가가 아니다. 위협 보고서 순위는 회사마다 집계 방식(사고 건수, 탐지 건수, 고객군)이 달라 단순 합산은 근사치다.
- SigmaHQ 룰을 옮겨도 우리 에이전트가 그 이벤트를 수집하지 않으면 동작하지 않는다. 그래서 분류에 수집 추가 필요를 따로 둔다.
- 시험 대상과 룰을 같은 자료로 고르므로 결과는 이 기준 범위 안의 대응률이다. 범위 밖 기법의 탐지율이 아니다.
