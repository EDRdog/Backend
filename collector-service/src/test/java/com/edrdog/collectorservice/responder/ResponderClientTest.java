package com.edrdog.collectorservice.responder;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * responder 호출 계약 검증. responder 는 X-Internal-Key 가 없으면 401 로 거절하므로(KISA H5) 두 경로 모두 헤더를 실어야 한다.
 */
class ResponderClientTest {

    private static final String KEY = "test-internal-key";

    private HttpServer server;
    private String requestUri;
    private String sentKey;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestUri = exchange.getRequestURI().toString();
            sentKey = exchange.getRequestHeaders().getFirst("X-Internal-Key");
            exchange.getRequestBody().readAllBytes();
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ResponderClient client() {
        return new ResponderClient("http://127.0.0.1:" + server.getAddress().getPort(), KEY);
    }

    @Test
    void 대기_명령_조회는_X_Internal_Key_를_싣는다() {
        client().pendingCommands("7", "h");

        assertThat(requestUri).startsWith("/api/responder/commands?");
        assertThat(sentKey).isEqualTo(KEY);
    }

    @Test
    void 명령_결과_전달은_X_Internal_Key_를_싣는다() {
        client().reportCommandResult("7", "h", "c1", "KILLED", "ok");

        assertThat(requestUri).isEqualTo("/api/responder/commands/result");
        assertThat(sentKey).isEqualTo(KEY);
    }
}
