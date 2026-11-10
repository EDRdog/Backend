# 024. 설치 스크립트는 설정된 SHA-256 지문과 같은 서버 인증서만 고정하고, 해시 파일이 없으면 설치를 멈춘다

- 날짜: 2026-11-10
- 상태: 채택

## 배경
KISA 2021 점검 H7. 설치 스크립트 네 개(`api-service/src/main/resources/install/macos.sh`, `windows.ps1`, `agent/packaging/install-macos.sh`, `install-windows.ps1`)가 collector 의 서버 인증서를 검증 없이 받아 `server.pem` 으로 고정했다(TOFU). Windows 는 검증 콜백이 늘 `$true` 였다. 설치 순간 중간에 끼어든 쪽의 인증서가 고정되면, 그 뒤로 그쪽이 고정된 서버가 되어 root/SYSTEM 으로 도는 에이전트에 kill 명령을 보낼 수 있다.

같은 스크립트가 바이너리의 `.sha256` 을 못 받으면 경고만 찍고 설치를 이어 갔다. 해시 파일을 빼는 것만으로 검사를 건너뛸 수 있었다.

## 결정
- api-service 가 `edrdog.install.server-cert-sha256`(환경변수 `INSTALL_SERVER_CERT_SHA256`)을 읽어 설치 스크립트에 박는다. 콜론 없는 64자 16진수 또는 keytool 출력처럼 바이트마다 콜론이 있는 꼴만 받고(`InstallScript.normalizeFingerprint`), 콜론 없는 대문자로 바꿔 넣는다. 바꾼 값은 기존 `SAFE_VALUE` 검사를 그대로 지난다.
- 스크립트는 받은 인증서의 SHA-256 지문을 계산해 박힌 값과 다르면 설치를 멈춘다. 같을 때만 그 인증서 한 장을 `server.pem` 으로 쓴다. macOS 는 `openssl x509 -fingerprint -sha256`, Windows 는 검증 콜백 안에서 DER 의 SHA-256 을 비교한다.
- **지문이 설정돼 있지 않으면 설치 스크립트를 내주지 않는다.** `/i/<토큰>` 이 400 과 `edrdog.install.server-cert-sha256 가 설정되지 않았습니다` 를 돌려준다. 모양이 틀린 값은 서버 설정 오류라 500 이다(`render` 의 값 검사와 같은 취급).
- `.sha256` 을 못 받으면 설치를 멈춘다. 경고하고 넘어가는 길은 없앴다.
- 손으로 까는 스크립트(`agent/packaging/install-macos.sh`, `install-windows.ps1`)는 지문을 필수 인자(`--server-cert-sha256`, `-ServerCertSha256`)로 받는다. 지문 없이 받은 것을 믿는 길은 두지 않았다. `bootstrap-macos.sh` 는 받은 지문을 그대로 넘긴다.

## 근거
**지문을 믿을 수 있는 이유**: 설치 스크립트는 `INSTALL_PUBLIC_BASE`(Caddy, 공인 CA 인증서)로 받는다. `curl -fsSL` 과 `irm` 은 시스템 신뢰 저장소로 그 연결을 검증한다. 공인 CA 가 지켜 주는 통로로 지문을 건네고, 그 지문이 self-signed 인 collector 30443 을 지켜 준다. 손으로 까는 스크립트는 이 통로가 없어서 운영자가 지문을 따로 건넨다.

**스크립트 안에서 멈추게 하지 않고 서버가 안 내주게 한 이유**: 두 갈래 중 서버 쪽을 골랐다.
- 이미 `agent-server`, `download-base` 가 같은 방식(`requireConfigured`)이다. 설정 누락을 한 군데서 같은 모양으로 막는다.
- 스크립트 안에서 멈추게 하면 지문 없는 스크립트가 enroll secret 을 실은 채 엔드포인트까지 나간다. 스크립트 쪽 분기 하나가 틀리면 다시 TOFU 가 된다. 서버가 안 내주면 엔드포인트에서 실행될 것이 아예 없다.
- 대가: `curl -f` 는 오류 본문을 보여주지 않아 설치하는 사람은 `error: 400` 만 본다. 이유는 응답 본문과 운영자 쪽에서 확인한다.

**PEM 을 통째로 박지 않은 이유**: PEM 은 여러 줄이라 `SAFE_VALUE` 를 못 지난다. 받으려면 치환 규칙을 넓혀야 하고, 그게 곧 셸 주입 경로다. 지문은 64자 16진수라 규칙을 안 건드린다. 운영자가 keytool 출력에서 그대로 복사할 수 있다.

**collector 에 공인 CA 인증서를 쓰는 대안을 지금 안 한 이유**: 그러면 고정이 필요 없지만, 인증서가 Caddy 에 있고 collector 는 PKCS12 키스토어를 읽는다. 90일 갱신마다 키스토어와 Secret 을 갈아야 하고, 이미 깔린 에이전트는 `ca_cert_path` 로 옛 인증서를 고정하고 있어 갱신 때마다 끊긴다. 바꾸는 범위가 이 점검 항목보다 크다.

**지문을 매니페스트가 아니라 Infisical 에 두는 이유**: 비밀은 아니다(30443 에 붙으면 누구나 인증서를 받는다). 다만 환경마다 다르고, `scripts/bootstrap-oracle.sh` 가 돌 때마다 키스토어를 새로 만들어(`rm -f` 뒤 `keytool -genkeypair`) 지문이 바뀐다. 코드 배포 없이 바꿀 수 있어야 해서 `edrdog-secrets` 로 주입한다. api-service 는 이미 그 Secret 을 `envFrom` 으로 받아 매니페스트는 고치지 않았다.

**바이너리 서명(cosign, minisign)은 지금 안 한다**: `.sha256` 과 바이너리가 같은 GitHub Release 에서 온다. 릴리스를 바꿀 수 있는 쪽은 해시도 같이 바꾼다. 그래서 `.sha256` 은 끊긴 다운로드와 깨진 파일만 막는다. 남은 틈은 `docs/exec-plans/tech-debt.md` 에 적었다.

## 운영자가 지문을 얻는 법
서버 인증서는 `scripts/bootstrap-oracle.sh` 5단계가 배포서버에서 만든 self-signed 인증서다. 키스토어는 배포서버의 `/etc/edrdog/agent-tls/agent-keystore.p12` 에 있고, 같은 파일이 `agent-tls` Secret(`keystore.p12`)으로 collector 에 마운트된다(`k8s/collector-service.yaml`). 로컬은 `scripts/gen-dev-keystore.sh` 가 만든 `./dev-tls/agent-keystore.p12` 다.

배포서버에서 사람이 실행한다.

```bash
sudo keytool -list -keystore /etc/edrdog/agent-tls/agent-keystore.p12 \
  -storepass "<AGENT_TLS_KEYSTORE_PASSWORD>" -alias agent
# Certificate fingerprint (SHA-256): C8:A4:3B:...:B2:4E
```

1. 위 줄의 값을 Infisical prod 의 `INSTALL_SERVER_CERT_SHA256` 에 넣는다. 콜론을 지우지 않아도 된다.
2. `edrdog-secrets` 가 동기화된 뒤(`resyncInterval: 60`) `sudo kubectl -n edrdog rollout restart deploy/api-service`. `envFrom` 은 파드가 뜰 때만 읽힌다.
3. 확인: 설치 링크로 받은 스크립트에 `SERVER_CERT_SHA256="C8A43B..."` 가 들어 있다.

인터넷 너머에서 `openssl s_client` 로 지문을 뽑지 않는다. 그 순간이 TOFU 다. 키스토어 파일이 없으면 배포서버 안에서 Secret 의 `keystore.p12` 를 꺼내 같은 명령을 돌린다.

`bootstrap-oracle.sh` 를 다시 돌리면 인증서가 바뀐다. 그때마다 1, 2 를 다시 해야 하고, 안 하면 새 설치가 전부 지문 불일치로 멈춘다(이미 깔린 기기는 옛 인증서를 고정하고 있어 그와 별개로 끊긴다).

## 결과/영향
- 지문을 넣기 전까지 설치 링크가 400 을 낸다. 배포 직후 설치가 막히는 것은 의도한 동작이다.
- 이미 깔린 기기의 `server.pem` 은 예전 방식(TOFU)으로 받은 것이다. 이 변경은 앞으로의 설치만 지킨다. 의심되면 기기에서 `openssl x509 -in /etc/edrdog/server.pem -noout -fingerprint -sha256` 을 운영자 값과 대조한다.
- Windows 두 스크립트의 콜백은 실기기에서 돌려 보지 않았다(PowerShell 이 개발 기기에 없다). 기존 Windows 설치 경로 전체가 미검증인 것과 같은 상태다.

## 관련
- `docs/security/kisa-2021-review.md` H7
- [011](011-collector-as-ingest-entrypoint.md)
