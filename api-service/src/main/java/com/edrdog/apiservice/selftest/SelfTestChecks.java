package com.edrdog.apiservice.selftest;

import com.edrdog.apiservice.security.Tokens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import static com.edrdog.apiservice.selftest.SelfTestItem.SELFTEST;

/**
 * 이 서버의 보안 기능이 지금 실제로 동작하는지 보는 항목들. 무엇을 왜 골랐는지는 ADR 029.
 * 결과의 detail 에는 예외 메시지를 싣지 않는다(JDBC URL 등에 비밀이 섞일 수 있다). 예외 클래스 이름만 남긴다.
 */
@Component
public class SelfTestChecks {

    // BCrypt 알려진 값: htpasswd -bnBC 10 "" edrdog-selftest. 인코더가 알고리즘대로 검증하는지 본다.
    private static final String KNOWN_PASSWORD = "edrdog-selftest";
    private static final String KNOWN_BCRYPT = "$2a$10$XLLuFlIMfioRsAREoX4s/e3Y2s3HZ.mN.CjwbwiY6QOmRTtN35fLq";
    // SHA-256("abc") 표준 시험 벡터(FIPS 180-2)
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private final JdbcTemplate jdbc;
    private final BCryptPasswordEncoder encoder;
    private final String apiKey;
    private final String internalKey;

    public SelfTestChecks(JdbcTemplate jdbc,
                          BCryptPasswordEncoder encoder,
                          @Value("${edrdog.api.key}") String apiKey,
                          @Value("${edrdog.internal.key}") String internalKey) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.apiKey = apiKey;
        this.internalKey = internalKey;
    }

    @FunctionalInterface
    private interface Check {
        SelfTestItem run() throws Exception;
    }

    public List<SelfTestItem> run() {
        List<SelfTestItem> items = new ArrayList<>();
        items.add(guard("database", this::database));
        items.add(guard("password_hash", this::passwordHash));
        items.add(guard("random", this::random));
        items.add(guard("sha256", this::sha256));
        items.add(guard("security_config", this::securityConfig));
        return items;
    }

    private static SelfTestItem guard(String name, Check check) {
        try {
            return check.run();
        } catch (Exception e) {
            return SelfTestItem.fail(SELFTEST, name, "시험 중 예외: " + e.getClass().getSimpleName());
        }
    }

    private static SelfTestItem result(String name, boolean ok, String passDetail, String failDetail) {
        return ok ? SelfTestItem.pass(SELFTEST, name, passDetail) : SelfTestItem.fail(SELFTEST, name, failDetail);
    }

    // 세션·계정이 여기 저장된다. 붙지 못하면 로그인이 전부 멈춘다.
    private SelfTestItem database() {
        Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
        return result("database", Objects.equals(one, 1), "MySQL 에 질의가 된다", "SELECT 1 결과가 1이 아니다");
    }

    private SelfTestItem passwordHash() {
        boolean ok = encoder.matches(KNOWN_PASSWORD, KNOWN_BCRYPT) && !encoder.matches("wrong-password", KNOWN_BCRYPT);
        return result("password_hash", ok, "BCrypt 가 알려진 해시를 맞게 판별한다",
                "BCrypt 가 알려진 해시를 맞게 판별하지 못한다");
    }

    // 세션 토큰이 예측되면 로그인 없이 세션을 만들 수 있다.
    private SelfTestItem random() {
        String a = Tokens.newToken();
        String b = Tokens.newToken();
        return result("random", !a.equals(b) && a.length() == 43, "토큰 생성기가 서로 다른 43자 토큰을 낸다",
                "토큰 생성기가 같은 값이나 잘못된 길이를 냈다");
    }

    // 무결성 검증과 node_key 해시가 기대는 알고리즘이다.
    private SelfTestItem sha256() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("abc".getBytes(StandardCharsets.UTF_8));
        return result("sha256", HexFormat.of().formatHex(digest).equals(ABC_SHA256),
                "SHA-256 이 표준 시험 벡터와 같다", "SHA-256 이 표준 시험 벡터와 다르다");
    }

    // 값은 싣지 않고 이름만 적는다. 기본값(dev-api-key 등) 여부는 보지 않는다. 로컬에서는 늘 기본값이다.
    private SelfTestItem securityConfig() {
        List<String> missing = new ArrayList<>();
        if (apiKey == null || apiKey.isBlank()) {
            missing.add("edrdog.api.key");
        }
        if (internalKey == null || internalKey.isBlank()) {
            missing.add("edrdog.internal.key");
        }
        return result("security_config", missing.isEmpty(), "API 키와 내부 키가 설정돼 있다",
                "비어 있는 설정: " + String.join(", ", missing));
    }
}
