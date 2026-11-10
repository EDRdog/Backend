package com.edrdog.collectorservice.responder;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * responder-service 내부 API 호출 래퍼(에이전트 명령 중계).
 * 하트비트가 대기 명령을 가져오고 command-result 가 실행 결과를 되돌려주는 두 경로만 쓴다.
 */
@Component
public class ResponderClient {

    private static final Logger log = LoggerFactory.getLogger(ResponderClient.class);

    private final RestClient http;

    public ResponderClient(@Value("${edrdog.responder.url}") String baseUrl,
                           @Value("${edrdog.internal.key}") String internalKey) {
        // 모든 요청에 내부 키를 싣는다. responder 는 이 헤더가 없으면 401 이다(KISA H5)
        this.http = RestClient.builder().baseUrl(baseUrl).defaultHeader("X-Internal-Key", internalKey).build();
    }

    /** tenant 의 host 에 대기 중인 대응 명령을 responder 에서 가져온다. 여기서 예외를 올리면 하트비트가 실패해 수집까지 멈춘다. */
    public List<AgentCommand> pendingCommands(String tenantId, String host) {
        try {
            AgentCommand[] commands = http.get()
                    .uri(uri -> uri.path("/api/responder/commands")
                            .queryParam("tenantId", tenantId).queryParam("host", host).build())
                    .retrieve()
                    .body(AgentCommand[].class);
            return commands == null ? List.of() : List.of(commands);
        } catch (RestClientException e) {
            log.error("responder 대기 명령 조회 실패 tenant={} host={} err={}", tenantId, host, e.toString());
            return List.of();
        }
    }

    /**
     * 에이전트가 보고한 명령 실행 결과를 보고한 노드(tenantId, host)와 함께 responder 로 넘긴다.
     * 예외를 올려도 이미 실행된 조치를 되돌릴 수 없어 로그만 남긴다.
     */
    public void reportCommandResult(String tenantId, String host, String commandId, String status, String message) {
        try {
            http.post()
                    .uri("/api/responder/commands/result")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new CommandResult(commandId, status, message, tenantId, host))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            log.error("responder 명령 결과 전달 실패 commandId={} status={} err={}", commandId, status, e.toString());
        }
    }

    /** responder 명령 결과 보고 본문. 프로토콜과 같은 snake_case 로 보낸다. */
    private record CommandResult(
            @JsonProperty("command_id") String commandId,
            @JsonProperty("status") String status,
            @JsonProperty("message") String message,
            @JsonProperty("tenant_id") String tenantId,
            @JsonProperty("host") String host
    ) {
    }
}
