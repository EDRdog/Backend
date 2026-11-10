package com.edrdog.apiservice.selftest;

import com.edrdog.apiservice.selftest.SelfTestItem.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 이 서버의 보안 기능이 실제로 동작하는지 보는 항목들. */
class SelfTestChecksTest {

    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
    }

    private static SelfTestItem item(List<SelfTestItem> items, String name) {
        return items.stream().filter(i -> i.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void allChecksPassWhenEverythingWorks() {
        List<SelfTestItem> items = new SelfTestChecks(jdbc, new BCryptPasswordEncoder(), "k1", "k2").run();

        assertEquals(Set.of("database", "password_hash", "random", "sha256", "security_config"),
                items.stream().map(SelfTestItem::name).collect(Collectors.toSet()));
        assertEquals(Set.of(Status.PASS), items.stream().map(SelfTestItem::status).collect(Collectors.toSet()));
        assertEquals(Set.of(SelfTestItem.SELFTEST), items.stream().map(SelfTestItem::category).collect(Collectors.toSet()));
    }

    @Test
    void passwordHashFailsWhenEncoderAcceptsAnything() {
        BCryptPasswordEncoder broken = mock(BCryptPasswordEncoder.class);
        when(broken.matches(anyString(), anyString())).thenReturn(true);

        List<SelfTestItem> items = new SelfTestChecks(jdbc, broken, "k1", "k2").run();

        assertEquals(Status.FAIL, item(items, "password_hash").status());
    }

    @Test
    void passwordHashFailsWhenEncoderRejectsKnownHash() {
        BCryptPasswordEncoder broken = mock(BCryptPasswordEncoder.class);
        when(broken.matches(anyString(), anyString())).thenReturn(false);

        List<SelfTestItem> items = new SelfTestChecks(jdbc, broken, "k1", "k2").run();

        assertEquals(Status.FAIL, item(items, "password_hash").status());
    }

    @Test
    void databaseFailureFailsWithoutLeakingExceptionMessage() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("jdbc:mysql://user:secret@db"));

        SelfTestItem db = item(new SelfTestChecks(jdbc, new BCryptPasswordEncoder(), "k1", "k2").run(), "database");

        assertEquals(Status.FAIL, db.status());
        assertFalse(db.detail().contains("secret"));
    }

    @Test
    void securityConfigFailsWhenAKeyIsBlank() {
        List<SelfTestItem> items = new SelfTestChecks(jdbc, new BCryptPasswordEncoder(), "k1", " ").run();

        SelfTestItem config = item(items, "security_config");
        assertEquals(Status.FAIL, config.status());
        assertFalse(config.detail().contains("k1"));
    }
}
