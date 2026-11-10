package com.edrdog.apiservice.install;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실제로 배포되는 설치 스크립트 원본을 그대로 렌더링해 본다.
 *
 * <p>InstallScriptTest 가 치환 규칙을 보는 것과 달리, 여기서는 파일이 그 규칙과 맞는지를 본다.
 * 템플릿에 {@code {{SERVERR}}} 같은 오타가 하나 있으면 컴파일도 테스트도 다 지나가고,
 * 엔드포인트에서 설치가 깨진 뒤에야 드러난다.
 */
class InstallTemplatesTest {

    private static final Map<String, String> VALUES = Map.of(
            "SERVER", "edr.example.com:8443",
            "ENROLL_SECRET", "Zm9vYmFy-_09",
            "DOWNLOAD_BASE", "https://github.com/org/repo/releases/latest/download",
            "SERVER_CERT_FINGERPRINT", "C8A43B6EDCA088F8CC65425F7B1069DD762B38C28D843C8D11D1E9AD4F0AB24E");

    private final InstallScripts scripts = new InstallScripts("https://edr.example.com");

    @Test
    void macOS_원본은_채울_수_있는_자리표시자만_쓴다() {
        String out = InstallScript.render(scripts.macos(), VALUES);

        assertNoPlaceholderLeft(out);
        assertTrue(out.contains("edr.example.com:8443"), "서버 주소가 안 들어갔다");
        assertTrue(out.contains("Zm9vYmFy-_09"), "enroll secret 이 안 들어갔다");
    }

    @Test
    void windows_원본은_채울_수_있는_자리표시자만_쓴다() {
        String out = InstallScript.render(scripts.windows(), VALUES);

        assertNoPlaceholderLeft(out);
        assertTrue(out.contains("edr.example.com:8443"), "서버 주소가 안 들어갔다");
        assertTrue(out.contains("Zm9vYmFy-_09"), "enroll secret 이 안 들어갔다");
    }

    @Test
    void 두_원본_모두_등록을_확인하고_끝낸다() {
        // 서비스가 떠 있다는 것과 서버에 붙었다는 것은 다르다. 둘 다 KeepAlive 라 못 붙어도 계속 살아 있어 떠 있는 것만 보면 설치가 조용히 실패한다.
        assertTrue(scripts.macos().contains("등록 완료"), "macOS 원본이 등록 여부를 안 본다");
        assertTrue(scripts.windows().contains("등록 완료"), "windows 원본이 등록 여부를 안 본다");
    }

    @Test
    void macOS_원본은_터미널을_따로_읽는다() {
        // curl 로 받아 실행하면 stdin 이 스크립트 자신이라, 그냥 read 를 쓰면 권한 승인을 안 기다리고 다음 줄을 답으로 먹는다.
        assertTrue(scripts.macos().contains("/dev/tty"), "권한 대기를 /dev/tty 로 읽어야 한다");
    }

    @Test
    void 두_원본_모두_받은_해시의_모양을_먼저_본다() {
        // .sha256 파일은 "<해시>  <파일명>" 이다. 처음에는 공백을 지우고 뒤에서 64자를
        // 잘랐는데, 그러면 파일명 끝을 해시로 읽는다. 실제 릴리스로 받아 보고서야 알았고
        // 그때까지 모든 설치가 해시 불일치로 막혀 있었다.
        //
        // 모양 검사를 먼저 두면 추출이 또 깨져도 "해시가 다르다" 가 아니라 "모양이 다르다"
        // 로 나온다. 릴리스 파일이 깨진 것과 바이너리가 바뀐 것은 대응이 전혀 다르다.
        assertTrue(scripts.macos().contains("[A-Fa-f0-9]{64}"), "macOS 원본이 해시 모양을 안 본다");
        assertTrue(scripts.windows().contains("[A-Fa-f0-9]{64}"), "windows 원본이 해시 모양을 안 본다");
    }

    @Test
    void templatesPinOnlyTheConfiguredFingerprint() {
        // 받은 인증서를 그대로 고정하면(TOFU) 설치 순간 끼어든 쪽이 고정된 서버가 되어 kill 명령까지 보낼 수 있다(KISA H7).
        String mac = scripts.macos();
        String win = scripts.windows();

        assertTrue(mac.contains("{{SERVER_CERT_FINGERPRINT}}"), "macOS 원본이 지문을 안 받는다");
        assertTrue(win.contains("{{SERVER_CERT_FINGERPRINT}}"), "windows 원본이 지문을 안 받는다");
        // macOS: s_client 출력을 고정 파일에 바로 쓰던 것이 TOFU 였다. 대조한 뒤에만 쓴다.
        assertFalse(mac.contains("> \"$CERT_PATH\""), "macOS 원본이 받은 인증서를 대조 없이 고정한다");
        assertTrue(mac.contains("[[ \"$got_fp\" == \"$SERVER_CERT_SHA256\" ]] || fail"), "macOS 원본이 지문을 대조하지 않는다");
        // windows: 검증 콜백이 늘 참이면 아무 인증서나 받는다.
        assertFalse(win.matches("(?s).*param\\([^)]*\\)\\s*\\$true\\s*}.*"), "windows 원본의 검증 콜백이 늘 참이다");
        assertTrue(win.contains("-eq $ServerCertSha256"), "windows 원본이 지문을 대조하지 않는다");
    }

    @Test
    void missingSha256AbortsInstall() {
        // 해시 파일이 없을 때 경고만 하고 넘어가면, 해시 파일을 빼는 것만으로 검사를 건너뛸 수 있다.
        String mac = scripts.macos();
        String win = scripts.windows();

        assertTrue(mac.contains("-o \"$TMP/want\" || fail"), "macOS 원본이 해시 파일이 없어도 이어 간다");
        assertFalse(mac.contains("경고:"), "macOS 원본에 경고하고 넘어가는 길이 남았다");
        assertFalse(win.contains("Write-Warning"), "windows 원본에 경고하고 넘어가는 길이 남았다");
        assertFalse(win.contains("if ($want)"), "windows 원본이 해시가 있을 때만 대조한다");
    }

    @Test
    void templatesRecordIntegrityBaselineAfterWritingConfig() {
        // 기준값이 없으면 에이전트의 무결성 검증이 처음부터 실패로 남는다(ADR 030).
        String mac = scripts.macos();
        String win = scripts.windows();

        assertTrue(mac.indexOf("-record-baseline") > mac.indexOf("chmod 600 \"$CONFIG_PATH\""),
                "macOS 원본이 설정 파일을 쓰기 전에 기준값을 기록한다");
        assertTrue(mac.contains("-record-baseline || fail"), "macOS 원본이 기준값 기록 실패를 넘긴다");
        assertTrue(win.indexOf("-record-baseline") > win.indexOf("WriteAllText($ConfigPath"),
                "windows 원본이 설정 파일을 쓰기 전에 기준값을 기록한다");
        assertTrue(win.contains("@($ConfigPath, $BaselinePath)"), "windows 원본이 기준값 파일에 ACL 을 안 건다");
    }

    private static void assertNoPlaceholderLeft(String out) {
        assertFalse(out.contains("{{"), "안 채운 자리표시자가 남았다: "
                + out.replaceAll("(?s).*?(\\{\\{[A-Z_]*}}).*", "$1"));
    }
}
