package com.edrdog.apiservice.responder;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * responder 호출 계약 검증(경로·응답 매핑)과 실패 처리. 가짜 HTTP 서버를 띄워 responder 없이 확인한다.
 */
class ResponderClientTest {

    private HttpServer server;
    private String requestMethod;
    private String requestUri;
    private String responseBody;
    private int responseStatus;

    @BeforeEach
    void startServer() throws IOException {
        responseBody = "{}";
        responseStatus = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestMethod = exchange.getRequestMethod();
            requestUri = exchange.getRequestURI().toString();
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ResponderClient client() {
        return new ResponderClient("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void kill_은_202_응답의_PENDING_과_명령id를_그대로_돌려준다() {
        responseStatus = 202;
        responseBody = "{\"host\":\"h\",\"target\":\"t\",\"status\":\"PENDING\",\"executionId\":\"exec-1\"}";

        KillResult result = client().kill("h", "t");

        assertThat(requestMethod).isEqualTo("POST");
        assertThat(requestUri).isEqualTo("/api/responder/kill");
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.executionId()).isEqualTo("exec-1");
    }

    @Test
    void kill_이_실패하면_FAILED() {
        responseStatus = 500;

        assertThat(client().kill("h", "t").status()).isEqualTo("FAILED");
    }

    @Test
    void 결과_조회는_명령id_경로로_묻고_응답을_매핑한다() {
        responseBody = "{\"host\":\"h\",\"target\":\"t\",\"status\":\"KILLED\",\"executionId\":\"exec-1\"}";

        assertThat(client().killResult("exec-1")).hasValueSatisfying(r -> assertThat(r.killed()).isTrue());
        assertThat(requestMethod).isEqualTo("GET");
        assertThat(requestUri).isEqualTo("/api/responder/kill/exec-1");
    }

    @Test
    void 결과_조회가_404_면_빈_값() {
        responseStatus = 404;
        responseBody = "";

        assertThat(client().killResult("없음")).isEmpty();
    }

    @Test
    void 결과_조회가_실패하면_502_로_올린다() {
        // FAILED 로 삼키면 대시보드가 조회를 멈추고, 실제로는 종료된 프로세스를 실패로 보여준다.
        responseStatus = 500;

        assertThatThrownBy(() -> client().killResult("exec-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("502");
    }
}
