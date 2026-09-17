package com.edrdog.responderservice.response;

import com.edrdog.responderservice.command.AgentCommand;
import com.edrdog.responderservice.command.CommandQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * 실제 조치(프로세스 kill)를 명령 큐에 넣는 반자동 실행기. 결과는 기다리지 않고 {@link #result} 로 조회한다.
 *
 * 안전장치. 넷 다 지우면 사고가 난다:
 * - enabled 기본 false → 켜기 전엔 실제 실행 안 함(dry-run 유지).
 * - 사람이 트리거(대시보드 버튼 → API)해야 실행 → 오탐 리스크는 사람이 차단.
 * - host 단위 쿨다운 → 동일 호스트 재조치/폭주 차단(무한 루프 2차 방어). 명령 저장소로 판단해 인스턴스 공통이다.
 * - trigger=response 태깅 → 이 조치가 만든 이벤트가 다시 판정에 걸려 무한 루프가 되는 것을 막는다(1차 방어).
 */
@Service
public class ResponseExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResponseExecutor.class);

    /** 명령은 나갔고 결과를 기다리는 중. 서버가 붙이는 상태다. */
    public static final String PENDING = "PENDING";

    private final CommandQueue commands;
    private final boolean enabled;
    private final Duration cooldown;
    private final Duration commandTimeout;
    private final Clock clock;

    @Autowired
    public ResponseExecutor(CommandQueue commands,
                            @Value("${edrdog.responder.execute.enabled}") boolean enabled,
                            @Value("${edrdog.responder.cooldown-ms}") long cooldownMs,
                            @Value("${edrdog.responder.command.timeout-ms}") long commandTimeoutMs) {
        this(commands, enabled, cooldownMs, commandTimeoutMs, Clock.systemUTC());
    }

    /** 시한·쿨다운을 결정적으로 검증하려고 시계를 주입받는 생성자. */
    public ResponseExecutor(CommandQueue commands, boolean enabled, long cooldownMs, long commandTimeoutMs, Clock clock) {
        this.commands = commands;
        this.enabled = enabled;
        this.cooldown = Duration.ofMillis(cooldownMs);
        this.commandTimeout = Duration.ofMillis(commandTimeoutMs);
        this.clock = clock;
    }

    /** host 의 target 프로세스 kill 명령을 넣고 바로 돌아온다. 실행 스위치·쿨다운을 통과할 때만 넣는다. */
    public ExecuteResult killProcess(String host, String target) {
        // 이 스위치를 지우면 켜기 전에 실제 kill 이 나간다. 기본값은 false 다.
        if (!enabled) {
            log.info("[EXECUTE-DISABLED] trigger=response host={} target={} (실행 스위치 꺼짐, 아무것도 안 함)", host, target);
            return new ExecuteResult(host, target, "DISABLED", null);
        }
        try {
            // 이 검사를 빼면 같은 호스트에 재조치가 연달아 나간다.
            // ponytail: 검사와 저장 사이가 원자적이지 않아 두 인스턴스가 동시에 통과할 수 있다. 사람이 누르는 경로라 창이 좁다. 문제가 되면 host 별 락 행으로 바꾼다
            if (commands.dispatchedSince(host, clock.instant().minus(cooldown))) {
                log.info("[EXECUTE-COOLDOWN] trigger=response host={} target={} (쿨다운, 재조치 억제)", host, target);
                return new ExecuteResult(host, target, "COOLDOWN", null);
            }
            String commandId = commands.dispatch(host, "kill_process", target);
            log.info("[EXECUTE] trigger=response host={} target={} cmd={} (명령 대기)", host, target, commandId);
            return new ExecuteResult(host, target, PENDING, commandId);
        } catch (Exception e) {
            log.error("[EXECUTE-FAILED] trigger=response host={} target={} err={}", host, target, e.toString());
            return new ExecuteResult(host, target, "FAILED", null);
        }
    }

    /** 명령의 현재 결과. 모르는 id 면 빈 값. */
    public Optional<ExecuteResult> result(String commandId) {
        return commands.find(commandId)
                .map(c -> new ExecuteResult(c.getHost(), c.getTarget(), statusOf(c), c.getId()));
    }

    /** 아직 보고가 없으면 시한 안에는 PENDING, 넘겼으면 TIMEOUT. 보고가 있으면 그 결과. */
    private String statusOf(AgentCommand c) {
        String status = c.getStatus();
        if (!CommandQueue.PENDING.equals(status) && !CommandQueue.DELIVERED.equals(status)) {
            return status;
        }
        Instant deadline = c.getCreatedAt().plus(commandTimeout);
        return clock.instant().isAfter(deadline) ? "TIMEOUT" : PENDING;
    }
}
