package com.edrdog.collectorservice.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * api-service 내부 감사기록 API 호출 계약. api-service 를 흉내 내는 로컬 서버로 확인한다.
 * 몸통 모양과 경로는 다른 묶음(감사기록 서버)이 만드는 계약이다: POST /api/internal/audit, X-Internal-Key.
 */
class AuditClientTest {

    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private String requestUri;
    private String method;
    private String sentKey;
    private JsonNode sentBody;
    private int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUri = exchange.getRequestURI().toString();
            method = exchange.getRequestMethod();
            sentKey = exchange.getRequestHeaders().getFirst("X-Internal-Key");
            sentBody = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(responseStatus, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private AuditClient client() {
        return new AuditClient("http://127.0.0.1:" + server.getAddress().getPort(), KEY);
    }

    @Test
    void forwardPostsContractBodyWithInternalKey() {
        client().forward(7L, "mac-001", List.of(new AuditEvent(1785341400000L, "agent_start", "success", "version=0.1.0")));

        assertThat(method).isEqualTo("POST");
        assertThat(requestUri).isEqualTo("/api/internal/audit");
        assertThat(sentKey).isEqualTo(KEY);
        // tenantId 는 문자열이 아니라 숫자다.
        assertThat(sentBody.get("tenantId").isNumber()).isTrue();
        assertThat(sentBody.get("tenantId").asLong()).isEqualTo(7L);
        assertThat(sentBody.get("host").asText()).isEqualTo("mac-001");
        JsonNode first = sentBody.get("events").get(0);
        assertThat(first.get("ts").asLong()).isEqualTo(1785341400000L);
        assertThat(first.get("type").asText()).isEqualTo("agent_start");
        assertThat(first.get("result").asText()).isEqualTo("success");
        assertThat(first.get("detail").asText()).isEqualTo("version=0.1.0");
    }

    /** 못 넘기면 조용히 삼키지 않는다. 컨트롤러가 503 으로 돌려야 에이전트가 기록을 다시 보낸다. */
    @Test
    void forwardThrowsWhenApiServiceRejects() {
        responseStatus = 500;

        assertThatThrownBy(() -> client().forward(7L, "h", List.of(new AuditEvent(1L, "agent_start", "success", ""))))
                .isInstanceOf(AuditForwardException.class);
    }

    @Test
    void forwardThrowsWhenApiServiceIsDown() {
        AuditClient down = client();
        server.stop(0);

        assertThatThrownBy(() -> down.forward(7L, "h", List.of(new AuditEvent(1L, "agent_start", "success", ""))))
                .isInstanceOf(AuditForwardException.class);
    }
}
