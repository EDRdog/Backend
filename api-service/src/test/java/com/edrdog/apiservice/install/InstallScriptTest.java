package com.edrdog.apiservice.install;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InstallScript: 설치 스크립트 템플릿 치환(순수).
 *
 * <p>여기서 만든 문자열은 엔드포인트에서 root 로 실행된다. 값 하나가 잘못 들어가면 그 기기에서
 * 임의의 명령이 도는 것과 같으므로, 치환 규칙을 테스트로 못 박는다.
 */
class InstallScriptTest {

    @Test
    void 자리표시자를_값으로_바꾼다() {
        String out = InstallScript.render("서버는 {{SERVER}} 다", Map.of("SERVER", "edr.example.com:8443"));

        assertEquals("서버는 edr.example.com:8443 다", out);
    }

    @Test
    void 같은_자리표시자가_여러_번_나와도_전부_바꾼다() {
        String out = InstallScript.render("{{A}}-{{A}}", Map.of("A", "x"));

        assertEquals("x-x", out);
    }

    @Test
    void 안_채운_자리표시자가_남으면_던진다() {
        // 그대로 내보내면 엔드포인트가 이미 바이너리 설치된 뒤에야 죽어서 원인 찾기 어렵다. 내보내기 전에 막는다.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> InstallScript.render("{{SERVER}} {{SECRET}}", Map.of("SERVER", "h:1")));

        assertTrue(e.getMessage().contains("SECRET"), e.getMessage());
    }

    @Test
    void 값이_자리표시자를_담고_있으면_거부한다() {
        // 값에 심긴 {{...}} 가 재귀 치환으로 다른 값이 되는 걸 막는다. render 는 한 번만 훑지만, 값 자체를 막아 둬야 나중에 치환이 반복문으로 바뀌어도 안전하다.
        assertThrows(IllegalArgumentException.class, () -> InstallScript.render("{{A}}", Map.of("A", "{{B}}")));
    }

    @Test
    void 셸에서_위험한_값은_거부한다() {
        // enroll secret 은 URL-safe base64 라 정상값은 안 걸린다. 걸리면 우리가 만든 값이 아니란 뜻이라 명령이 되기 전에 막는다.
        for (String bad : new String[]{"a\"b", "a'b", "a b", "a$b", "a`b", "a;b", "a\nb", "a\\b", "a|b"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> InstallScript.render("{{A}}", Map.of("A", bad)), "거부해야 한다: " + bad);
        }
    }

    @Test
    void 정상적인_값들은_통과한다() {
        // 실제로 들어가는 모양들이다. host:port, URL, URL-safe base64 토큰.
        for (String ok : new String[]{
                "edr.example.com:8443",
                "https://github.com/org/repo/releases/latest/download",
                "Zm9vYmFy-_09",
                "192.168.0.15:30443",
        }) {
            assertEquals(ok, InstallScript.render("{{A}}", Map.of("A", ok)));
        }
    }

    @Test
    void 빈_값은_거부한다() {
        // 빈 문자열이 들어가면 "--server" 뒤가 비어 인자 하나가 통째로 밀린다.
        assertThrows(IllegalArgumentException.class, () -> InstallScript.render("{{A}}", Map.of("A", "")));
    }

    private static final String FP_UPPER = "C8A43B6EDCA088F8CC65425F7B1069DD762B38C28D843C8D11D1E9AD4F0AB24E";

    @Test
    void fingerprintAcceptsKeytoolOutputAsIs() {
        // 운영자는 keytool 출력(콜론, 대문자)을 그대로 붙여 넣는다. 스크립트는 콜론 없는 대문자와 비교한다.
        String keytool = "C8:A4:3B:6E:DC:A0:88:F8:CC:65:42:5F:7B:10:69:DD:76:2B:38:C2:8D:84:3C:8D:11:D1:E9:AD:4F:0A:B2:4E";

        assertEquals(FP_UPPER, InstallScript.normalizeFingerprint(keytool));
        assertEquals(FP_UPPER, InstallScript.normalizeFingerprint(keytool.toLowerCase()));
        assertEquals(FP_UPPER, InstallScript.normalizeFingerprint(FP_UPPER.toLowerCase()));
        assertEquals(FP_UPPER, InstallScript.normalizeFingerprint(" " + FP_UPPER + "\n"));
    }

    @Test
    void fingerprintRejectsAnythingButSha256() {
        // 모양이 틀린 값을 받아 주면 스크립트의 비교가 늘 실패하거나, 더 나쁘게는 엉뚱한 값과 비교한다.
        for (String bad : new String[]{
                FP_UPPER.substring(1),                    // 63자
                FP_UPPER + "A",                           // 65자
                "G" + FP_UPPER.substring(1),              // 16진수 아님
                "C8A4:" + FP_UPPER.substring(4),          // 콜론이 띄엄띄엄
                FP_UPPER.substring(0, 32) + " " + FP_UPPER.substring(32),
                FP_UPPER.substring(0, 63) + ";",
                "",
        }) {
            assertThrows(IllegalArgumentException.class,
                    () -> InstallScript.normalizeFingerprint(bad), "거부해야 한다: " + bad);
        }
    }
}
