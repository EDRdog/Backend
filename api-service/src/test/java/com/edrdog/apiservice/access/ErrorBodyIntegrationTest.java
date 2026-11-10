package com.edrdog.apiservice.access;

import com.edrdog.apiservice.clickhouse.ClickHouseReader;
import com.edrdog.apiservice.auth.AppUser;
import com.edrdog.apiservice.auth.SessionRepository;
import com.edrdog.apiservice.auth.UserRepository;
import com.edrdog.apiservice.auth.UserSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 서버 내부 오류(500)의 응답 본문에 스택트레이스, 예외 이름, 내부 주소가 실리지 않는지(KISA EDR-BF-19.1).
 * MockMvc 는 오류 페이지 디스패치를 안 타서 실제 HTTP 로 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@TestPropertySource(properties = {"spring.kafka.listener.auto-startup=false", "edrdog.selftest.run-on-startup=false"})
class ErrorBodyIntegrationTest {

    private static final String INTERNAL_DETAIL = "jdbc:clickhouse://10.1.2.3:8123/secret-db refused";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository users;
    @Autowired
    private SessionRepository sessions;

    @MockitoBean
    private ClickHouseReader reader;

    @Test
    void unexpectedFailureBodyCarriesNoInternals() {
        when(reader.query(any())).thenThrow(new IllegalStateException(INTERNAL_DETAIL));
        AppUser u = users.save(AppUser.of("err-body@edrdog.com", "hash", 1L, "admin", Instant.now()));
        sessions.save(UserSession.of("err-body-token", u.getId(), 1L, Instant.now().plusSeconds(600)));
        HttpHeaders h = new HttpHeaders();
        h.set("Authorization", "Bearer err-body-token");

        ResponseEntity<String> res = rest.exchange("/api/events", HttpMethod.GET, new HttpEntity<>(h), String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        String body = String.valueOf(res.getBody());
        for (String leak : List.of(INTERNAL_DETAIL, "secret-db", "Exception", "at com.", "trace", "com.edrdog")) {
            assertThat(body).doesNotContain(leak);
        }
    }
}
