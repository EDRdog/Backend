<#
.SYNOPSIS
    EDRdog 에이전트를 Windows 에 설치한다.

.DESCRIPTION
    하는 일:
      1. 에이전트 바이너리를 C:\Program Files\EDRdog 에 놓는다
      2. 수집 서버에서 서버 인증서를 받아, 운영자에게 받은 SHA-256 지문과 같을 때만 저장한다
      3. 설정 파일을 만든다(enroll secret 포함, 관리자만 읽게 ACL 을 건다)
      4. Windows 서비스로 등록해 부팅 시 LocalSystem 으로 기동시킨다

    macOS 와 달리 사람이 승인할 단계가 없다. ETW 는 LocalSystem 이면 권한이 이미 있다.

    주의: 이 스크립트와 Windows ETW 센서는 아직 실제 Windows 기기에서 검증되지 않았다.
    크로스 빌드와 순수 로직 단위 테스트만 통과한 상태다. agent/README.md 의 검증 절차를 참고해라.

.PARAMETER Server
    수집 서버 주소. 호스트:포트 형식이다.

.PARAMETER EnrollSecret
    조직의 enroll secret. 대시보드에서 발급받는다.

.PARAMETER ServerCertSha256
    서버 인증서의 SHA-256 지문. 운영자가 배포서버의 키스토어에서 읽어 준다
    (docs/design-docs/decisions/024). keytool 출력(AB:CD:...) 그대로 넣어도 된다.
    받은 인증서가 이 값과 다르면 설치를 멈춘다.

.PARAMETER Binary
    에이전트 바이너리 경로. 생략하면 이 스크립트 옆의 edrdog-agent.exe 를 쓴다.

.EXAMPLE
    .\install-windows.ps1 -Server edr.example.com:30443 -EnrollSecret abc123 -ServerCertSha256 AB:CD:...
#>
param(
    [Parameter(Mandatory = $true)][string]$Server,
    [Parameter(Mandatory = $true)][string]$EnrollSecret,
    [Parameter(Mandatory = $true)][string]$ServerCertSha256,
    [string]$Binary
)

$ErrorActionPreference = 'Stop'

$InstallDir = 'C:\Program Files\EDRdog'
$ConfDir    = 'C:\ProgramData\EDRdog'
$BinPath    = Join-Path $InstallDir 'edrdog-agent.exe'
$ConfigPath = Join-Path $ConfDir 'config.json'
$CertPath   = Join-Path $ConfDir 'server.pem'
$ServiceName = 'edrdog-agent'

function Fail($message) {
    Write-Error "오류: $message"
    exit 1
}

# --- 관리자 확인 ---
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = New-Object Security.Principal.WindowsPrincipal($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Fail '관리자 권한 PowerShell 에서 실행해야 한다'
}

# 콜론을 빼고 대문자로 맞춘다. keytool 출력을 그대로 붙여 넣어도 되게 한다.
$ServerCertSha256 = ($ServerCertSha256 -replace ':', '').ToUpperInvariant()
if ($ServerCertSha256 -notmatch '^[A-F0-9]{64}$') {
    Fail '-ServerCertSha256 은 SHA-256 지문(16진수 64자, 콜론 허용)이어야 한다'
}

if (-not $Binary) {
    $Binary = Join-Path $PSScriptRoot 'edrdog-agent.exe'
}
if (-not (Test-Path $Binary)) {
    Fail "에이전트 바이너리를 찾을 수 없다: $Binary`n  먼저 빌드해라: GOOS=windows GOARCH=amd64 go build -o packaging/edrdog-agent.exe ./cmd/edrdog-agent"
}

Write-Host '[1/4] 바이너리 설치'
New-Item -ItemType Directory -Force -Path $InstallDir | Out-Null
Copy-Item -Force $Binary $BinPath

Write-Host "[2/4] 서버 인증서 수신과 지문 대조 ($Server)"
New-Item -ItemType Directory -Force -Path $ConfDir | Out-Null
$parts = $Server.Split(':')
$hostName = $parts[0]
$port = if ($parts.Length -gt 1) { [int]$parts[1] } else { 443 }

$tcp = $null
$ssl = $null
# 검증 콜백이 본 지문. 대조에 실패했을 때 무엇을 받았는지 보여 주려고 남긴다.
$seen = @{}
try {
    $tcp = [System.Net.Sockets.TcpClient]::new($hostName, $port)
    # 에이전트가 이 인증서로 서버를 고정한다. 받은 것을 그대로 고정하면 설치 순간 끼어든 쪽이
    # 고정된 서버가 되어 kill 명령까지 보낼 수 있다. 운영자에게 받은 지문과 같을 때만 받아들인다.
    $callback = [System.Net.Security.RemoteCertificateValidationCallback] {
        param($s, $c, $ch, $e)
        $hash = [System.Security.Cryptography.SHA256]::Create().ComputeHash($c.GetRawCertData())
        $seen.Fp = [BitConverter]::ToString($hash) -replace '-', ''
        $seen.Fp -eq $ServerCertSha256
    }
    $ssl = [System.Net.Security.SslStream]::new($tcp.GetStream(), $false, $callback)
    $ssl.AuthenticateAsClient($hostName)
    $der = $ssl.RemoteCertificate.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Cert)
    $b64 = [Convert]::ToBase64String($der, [Base64FormattingOptions]::InsertLineBreaks)
    "-----BEGIN CERTIFICATE-----`n$b64`n-----END CERTIFICATE-----" |
        Set-Content -Path $CertPath -Encoding ascii
} catch {
    if ($seen.Fp -and $seen.Fp -ne $ServerCertSha256) {
        Fail "서버 인증서 지문이 다르다 (기대 $ServerCertSha256, 실제 $($seen.Fp)). 중간에서 가로챈 연결일 수 있어 설치를 멈춘다"
    }
    Fail "서버 인증서를 받지 못했다: $($_.Exception.Message)"
} finally {
    if ($ssl) { $ssl.Dispose() }
    if ($tcp) { $tcp.Dispose() }
}

Write-Host '[3/4] 설정 파일 작성'
$config = @"
{
  "base_url": "https://$Server",
  "enroll_secret": "$EnrollSecret",
  "ca_cert_path": "$($CertPath -replace '\\', '\\\\')"
}
"@
[System.IO.File]::WriteAllText($ConfigPath, $config)

# enroll secret 이 들어 있으므로 일반 사용자가 읽으면 안 된다.
# 상속을 끊고 SYSTEM 과 Administrators 만 남긴다.
$acl = Get-Acl $ConfigPath
$acl.SetAccessRuleProtection($true, $false)
$acl.Access | ForEach-Object { $acl.RemoveAccessRule($_) | Out-Null }
foreach ($who in @('NT AUTHORITY\SYSTEM', 'BUILTIN\Administrators')) {
    $rule = New-Object System.Security.AccessControl.FileSystemAccessRule(
        $who, 'FullControl', 'Allow')
    $acl.AddAccessRule($rule)
}
Set-Acl -Path $ConfigPath -AclObject $acl

Write-Host '[4/4] 서비스 등록'
$existing = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
if ($existing) {
    if ($existing.Status -ne 'Stopped') { Stop-Service -Name $ServiceName -Force }
    # sc.exe delete 는 비동기라 곧바로 New-Service 를 부르면 아직 지워지지 않아 실패한다.
    sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 2
}

# New-Service 를 쓰는 이유: sc.exe 는 PowerShell 5.1 에서 공백이 든 경로를 binPath 로 넘길 때
# 따옴표가 그대로 전달되지 않아 등록이 실패하고, 그 실패가 화면에 안 남아
# 다음 줄 Start-Service 에서 "서비스를 찾을 수 없다"는 엉뚱한 오류로만 드러난다.
$binaryPathName = '"{0}" -config "{1}" -service' -f $BinPath, $ConfigPath
New-Service -Name $ServiceName `
    -BinaryPathName $binaryPathName `
    -DisplayName 'EDRdog Agent' `
    -Description '엔드포인트 행위를 수집해 EDRdog 서버로 보낸다' `
    -StartupType Automatic | Out-Null

Start-Service -Name $ServiceName
Start-Sleep -Seconds 3
$service = Get-Service -Name $ServiceName
if ($service.Status -ne 'Running') {
    Fail "서비스가 뜨지 않았다 (상태: $($service.Status)). 이벤트 뷰어의 애플리케이션 로그를 확인해라"
}

Write-Host ''
Write-Host '설치 완료.'
Write-Host ''
Write-Host "상태 확인:  Get-Service $ServiceName"
Write-Host "제거:       Stop-Service $ServiceName; sc.exe delete $ServiceName; Remove-Item -Recurse '$InstallDir','$ConfDir'"
