package com.edrdog.apiservice.install.web;

import com.edrdog.apiservice.auth.AuthException;
import com.edrdog.apiservice.auth.AuthService;
import com.edrdog.apiservice.install.InstallScripts;
import com.edrdog.apiservice.install.InstallService;
import com.edrdog.apiservice.tenant.TenantService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 설치 스크립트를 내줄 때 서버 인증서 지문이 박히는지, 지문이 없으면 아예 안 내주는지 본다.
 *
 * <p>지문 없이 나간 스크립트는 설치 순간 끼어든 쪽의 인증서를 고정한다. 그 뒤로 그쪽이
 * root/SYSTEM 으로 kill 명령을 보낼 수 있다(KISA H7).
 */
class InstallControllerTest {

    private static final String KEYTOOL_FP =
            "c8:a4:3b:6e:dc:a0:88:f8:cc:65:42:5f:7b:10:69:dd:76:2b:38:c2:8d:84:3c:8d:11:d1:e9:ad:4f:0a:b2:4e";
    private static final String EMBEDDED_FP =
            "C8A43B6EDCA088F8CC65425F7B1069DD762B38C28D843C8D11D1E9AD4F0AB24E";

    private static InstallController controller(String fingerprint) {
        InstallService install = mock(InstallService.class);
        when(install.resolve(eq("tok"), any(Instant.class))).thenReturn(1L);
        TenantService tenants = mock(TenantService.class);
        when(tenants.getEnrollSecret(1L)).thenReturn(Optional.of("Zm9vYmFy-_09"));
        return new InstallController(mock(AuthService.class), tenants, install,
                new InstallScripts("https://edr.example.com"),
                "edr.example.com:30443",
                "https://github.com/org/repo/releases/latest/download",
                fingerprint);
    }

    @Test
    void scriptsCarryConfiguredFingerprint() {
        InstallController c = controller(KEYTOOL_FP);

        assertTrue(c.macos("tok").getBody().contains("SERVER_CERT_SHA256=\"" + EMBEDDED_FP + "\""),
                "macOS 스크립트에 지문이 안 들어갔다");
        assertTrue(c.windows("tok").getBody().contains("$ServerCertSha256 = '" + EMBEDDED_FP + "'"),
                "windows 스크립트에 지문이 안 들어갔다");
    }

    @Test
    void refusesToServeWithoutFingerprint() {
        // 지문 없이 내주면 스크립트가 받은 인증서를 그대로 믿는 것 말고는 할 수 있는 게 없다.
        for (String missing : new String[]{"", "  "}) {
            AuthException e = assertThrows(AuthException.class, () -> controller(missing).macos("tok"));
            assertEquals(AuthException.Kind.INVALID_INPUT, e.getKind());
            assertTrue(e.getMessage().contains("edrdog.install.server-cert-sha256"), e.getMessage());

            assertThrows(AuthException.class, () -> controller(missing).windows("tok"));
        }
    }

    @Test
    void refusesToServeWithMalformedFingerprint() {
        assertThrows(IllegalArgumentException.class, () -> controller("not-a-fingerprint").macos("tok"));
        assertThrows(IllegalArgumentException.class, () -> controller(EMBEDDED_FP.substring(2)).windows("tok"));
    }
}
