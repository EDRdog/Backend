package com.edrdog.apiservice.selftest;

import com.edrdog.apiservice.ApiApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.edrdog.apiservice.selftest.SelfTestItem.INTEGRITY;

/**
 * 기준값 파일(sha256sum 형식, 한 줄에 "SHA-256 해시, 공백 둘, 절대경로")에 적힌 파일들의 실제 SHA-256 을 대조한다.
 * 기준값 파일은 jar 밖(k8s ConfigMap/Secret 볼륨 등)에 둔다. jar 안에 두면 jar 를 바꾼 사람이 기준값도 바꾼다(ADR 029).
 */
@Component
public class IntegrityVerifier {

    // sha256sum 출력: "<64자리 해시>  <경로>" (바이너리 모드는 공백 하나와 별표)
    private static final Pattern LINE = Pattern.compile("^([0-9a-fA-F]{64}) [ *](.+)$");

    private final Path baselineFile;   // null 이면 기준값 없음
    private final Path runningJar;     // null 이면 jar 로 실행 중이 아님(bootRun, 테스트)

    @Autowired
    public IntegrityVerifier(@Value("${edrdog.selftest.integrity-baseline-file:}") String baselineFile) {
        this(baselineFile.isBlank() ? null : Path.of(baselineFile), runningJar());
    }

    public IntegrityVerifier(Path baselineFile, Path runningJar) {
        this.baselineFile = baselineFile;
        this.runningJar = runningJar == null ? null : runningJar.toAbsolutePath().normalize();
    }

    public List<SelfTestItem> verify() {
        if (baselineFile == null) {
            return List.of(SelfTestItem.skip(INTEGRITY, "baseline",
                    "기준값 파일이 설정되지 않아 검증하지 않았다(INTEGRITY_BASELINE_FILE)"));
        }
        Map<Path, String> expected;
        try {
            expected = parse(Files.readAllLines(baselineFile));
        } catch (IOException | IllegalArgumentException e) {
            return List.of(SelfTestItem.fail(INTEGRITY, "baseline", "기준값 파일을 읽을 수 없거나 형식이 틀리다"));
        }

        List<SelfTestItem> items = new ArrayList<>();
        expected.forEach((path, hash) -> items.add(compare(path, hash)));
        // 기준값 파일에 jar 가 빠져 있으면 위의 대조가 jar 를 건너뛴다. 빠진 채로 통과시키면 안 된다.
        if (runningJar != null && !expected.containsKey(runningJar)) {
            items.add(SelfTestItem.fail(INTEGRITY, "running_jar", "기준값에 실행 중인 jar 가 없다: " + runningJar));
        }
        return items;
    }

    private static SelfTestItem compare(Path path, String expectedHash) {
        String name = path.toString();
        if (!Files.isRegularFile(path)) {
            return SelfTestItem.fail(INTEGRITY, name, "파일이 없다");
        }
        String actual;
        try {
            actual = sha256(path);
        } catch (IOException e) {
            return SelfTestItem.fail(INTEGRITY, name, "파일을 읽을 수 없다");
        }
        return actual.equalsIgnoreCase(expectedHash)
                ? SelfTestItem.pass(INTEGRITY, name, "SHA-256 일치: " + actual)
                : SelfTestItem.fail(INTEGRITY, name, "SHA-256 불일치. 기준 " + expectedHash + ", 실제 " + actual);
    }

    private static Map<Path, String> parse(List<String> lines) {
        Map<Path, String> expected = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.matches()) {
                throw new IllegalArgumentException("형식 오류");
            }
            expected.put(Path.of(m.group(2)).toAbsolutePath().normalize(), m.group(1));
        }
        if (expected.isEmpty()) {
            throw new IllegalArgumentException("비어 있음");
        }
        return expected;
    }

    private static String sha256(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 은 JVM 필수 알고리즘이라 여기 오면 JVM 이 깨진 것이다
        }
    }

    /** 실행 중인 jar. 클래스 디렉터리에서 돌고 있으면(bootRun, 테스트) null. */
    private static Path runningJar() {
        var source = new ApplicationHome(ApiApplication.class).getSource();
        return source != null && source.isFile() ? source.toPath() : null;
    }
}
