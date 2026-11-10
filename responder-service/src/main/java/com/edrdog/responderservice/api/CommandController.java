package com.edrdog.responderservice.api;

import com.edrdog.responderservice.command.Command;
import com.edrdog.responderservice.command.CommandQueue;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 에이전트 명령 채널. 에이전트는 collector-service 만 알고, collector 가 이 경로로 프록시한다.
 *
 * <p>responder 는 클러스터 안에서만 열리고, 모든 요청은 {@link InternalKeyFilter} 의 X-Internal-Key 검사를 거친다.
 * 바깥 노출은 collector/api-service 프록시 경로뿐이다.
 */
@RestController
@RequestMapping("/api/responder/commands")
public class CommandController {

    private final CommandQueue commands;

    public CommandController(CommandQueue commands) {
        this.commands = commands;
    }

    /** 그 tenant 의 그 호스트 대기 명령을 꺼낸다. 하트비트마다 호출된다. tenant·host 는 collector 가 node_key 로 푼 값이다. */
    @GetMapping
    public ResponseEntity<List<Command>> pending(@RequestParam("tenantId") String tenantId,
                                                 @RequestParam("host") String host) {
        if (isBlank(tenantId) || isBlank(host)) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(commands.drainFor(tenantId, host));
    }

    /** 에이전트가 보고한 실행 결과. 보고한 노드가 그 명령의 대상일 때만 kill 결과 조회에 보인다. */
    @PostMapping("/result")
    public ResponseEntity<Void> result(@RequestBody CommandResult body) {
        if (isBlank(body.commandId()) || isBlank(body.tenantId()) || isBlank(body.host())) {
            return ResponseEntity.badRequest().build();
        }
        commands.complete(body.commandId(), body.tenantId(), body.host(), body.status(), body.message());
        return ResponseEntity.ok().build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * 결과 보고 본문. 프로토콜 문서의 command-result 와 같은 필드라 스네이크 표기를 그대로 받는다.
     * tenant_id·host 는 에이전트가 아니라 collector 가 인증된 노드에서 채운다.
     */
    public record CommandResult(@JsonProperty("command_id") String commandId, String status, String message,
                                @JsonProperty("tenant_id") String tenantId, String host) {
    }
}
