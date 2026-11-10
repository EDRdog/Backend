package com.edrdog.apiservice.selftest;

import com.edrdog.apiservice.selftest.SelfTestItem.Status;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 기준값 파일(sha256sum 형식)과 실제 파일의 SHA-256 을 비교하는 무결성 검증. */
class IntegrityVerifierTest {

    @TempDir
    Path dir;

    private Path file(String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content);
    }

    private static String sha256(String content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes()));
    }

    /** sha256sum 이 만드는 "해시  경로" 한 줄씩. */
    private Path baseline(String... lines) throws IOException {
        return file("baseline.sha256", String.join("\n", lines) + "\n");
    }

    @Test
    void matchingFilePasses() throws Exception {
        Path config = file("newrelic.yml", "license: x");
        Path baseline = baseline(sha256("license: x") + "  " + config);

        List<SelfTestItem> items = new IntegrityVerifier(baseline, null).verify();

        assertEquals(1, items.size());
        assertEquals(Status.PASS, items.get(0).status());
        assertEquals(SelfTestItem.INTEGRITY, items.get(0).category());
        assertEquals(config.toString(), items.get(0).name());
    }

    @Test
    void modifiedFileFailsAndShowsBothHashes() throws Exception {
        Path config = file("newrelic.yml", "license: tampered");
        Path baseline = baseline(sha256("license: x") + "  " + config);

        SelfTestItem item = new IntegrityVerifier(baseline, null).verify().get(0);

        assertEquals(Status.FAIL, item.status());
        assertTrue(item.detail().contains(sha256("license: x")));
        assertTrue(item.detail().contains(sha256("license: tampered")));
    }

    @Test
    void missingFileFails() throws Exception {
        Path baseline = baseline(sha256("x") + "  " + dir.resolve("gone.yml"));

        assertEquals(Status.FAIL, new IntegrityVerifier(baseline, null).verify().get(0).status());
    }

    @Test
    void noBaselineIsSkippedNotFailed() {
        List<SelfTestItem> items = new IntegrityVerifier(null, null).verify();

        assertEquals(1, items.size());
        assertEquals("baseline", items.get(0).name());
        assertEquals(Status.SKIP, items.get(0).status());
    }

    @Test
    void unreadableBaselineFails() {
        SelfTestItem item = new IntegrityVerifier(dir.resolve("none.sha256"), null).verify().get(0);

        assertEquals("baseline", item.name());
        assertEquals(Status.FAIL, item.status());
    }

    @Test
    void malformedBaselineLineFails() throws Exception {
        SelfTestItem item = new IntegrityVerifier(baseline("not-a-hash /app/app.jar"), null).verify().get(0);

        assertEquals("baseline", item.name());
        assertEquals(Status.FAIL, item.status());
    }

    @Test
    void emptyBaselineFails() throws Exception {
        SelfTestItem item = new IntegrityVerifier(file("baseline.sha256", "# 비어 있다\n"), null).verify().get(0);

        assertEquals(Status.FAIL, item.status());
    }

    @Test
    void runningJarMissingFromBaselineFails() throws Exception {
        Path jar = file("app.jar", "jar-bytes");
        Path config = file("newrelic.yml", "license: x");
        Path baseline = baseline(sha256("license: x") + "  " + config);

        List<SelfTestItem> items = new IntegrityVerifier(baseline, jar).verify();

        SelfTestItem jarItem = items.stream().filter(i -> i.name().equals("running_jar")).findFirst().orElseThrow();
        assertEquals(Status.FAIL, jarItem.status());
    }

    @Test
    void runningJarCoveredByBaselinePasses() throws Exception {
        Path jar = file("app.jar", "jar-bytes");
        Path baseline = baseline(sha256("jar-bytes") + "  " + jar);

        List<SelfTestItem> items = new IntegrityVerifier(baseline, jar).verify();

        assertEquals(1, items.size());
        assertEquals(Status.PASS, items.get(0).status());
    }

    @Test
    void tamperedRunningJarFails() throws Exception {
        Path jar = file("app.jar", "tampered");
        Path baseline = baseline(sha256("jar-bytes") + "  " + jar);

        assertEquals(Status.FAIL, new IntegrityVerifier(baseline, jar).verify().get(0).status());
    }
}
