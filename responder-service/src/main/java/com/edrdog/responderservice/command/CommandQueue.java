package com.edrdog.responderservice.command;

import com.edrdog.responderservice.response.KillOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 엔드포인트로 내려보낼 명령 큐. 상태는 DB 에만 둔다.
 *
 * <p>에이전트가 방화벽 안쪽이라 서버가 먼저 부를 수 없고, 에이전트가 하트비트로 가져간다.
 * 메모리에 두면 재시작에 명령이 사라지고, 인스턴스를 늘리면 넣은 곳과 꺼내는 곳이 어긋난다.
 */
@Component
public class CommandQueue {

    private static final Logger log = LoggerFactory.getLogger(CommandQueue.class);

    public static final String PENDING = "PENDING";
    public static final String DELIVERED = "DELIVERED";

    private final AgentCommandRepository repository;
    /** 전달 시한. 결과 시한과 같은 값이라, 화면에 TIMEOUT 으로 보인 명령은 더는 내려가지 않는다. */
    private final long deliveryWindowMs;
    private final Clock clock;

    @Autowired
    public CommandQueue(AgentCommandRepository repository,
                        @Value("${edrdog.responder.command.timeout-ms}") long deliveryWindowMs) {
        this(repository, deliveryWindowMs, Clock.systemUTC());
    }

    /** 만료 동작을 결정적으로 검증하려고 시계를 주입받는 생성자. */
    public CommandQueue(AgentCommandRepository repository, long deliveryWindowMs, Clock clock) {
        this.repository = repository;
        this.deliveryWindowMs = deliveryWindowMs;
        this.clock = clock;
    }

    /** 명령을 저장하고 식별자를 돌려준다. */
    public String dispatch(String host, String type, String target) {
        String id = UUID.randomUUID().toString();
        repository.save(new AgentCommand(id, host, type, target, PENDING, clock.instant()));
        log.info("[COMMAND-DISPATCH] id={} host={} type={} target={}", id, host, type, target);
        return id;
    }

    /**
     * 그 호스트의 대기 명령을 가져간 것으로 표시하고 돌려준다. 한 번 가져간 명령은 다시 주지 않는다.
     * 전달 시한을 넘긴 명령은 주지 않는다. TIMEOUT 을 본 사용자가 다시 누른 명령과 겹쳐 두 번 죽이면 안 된다.
     */
    @Transactional
    public List<Command> drainFor(String host) {
        Instant cutoff = clock.instant().minusMillis(deliveryWindowMs);
        return repository.findByHostAndStatusAndCreatedAtAfterOrderByCreatedAt(host, PENDING, cutoff).stream()
                // 다른 인스턴스가 먼저 가져간 행은 0 이 나와 빠진다.
                .filter(c -> repository.transition(c.getId(), PENDING, DELIVERED, null) == 1)
                .map(AgentCommand::toCommand)
                .toList();
    }

    /** 에이전트가 보고한 결과를 저장한다. 가져간(DELIVERED) 명령에만 한 번 기록된다. */
    @Transactional
    public void complete(String id, String status, String message) {
        // 보고 문자열을 그대로 믿으면 임의의 값이 알림 상태를 밀어 올린다. 모르는 값은 FAILED 다.
        String outcome = KillOutcome.of(status).name();
        // 에이전트 메시지는 길이 제한이 없다. 자르지 않으면 저장이 실패해 결과가 TIMEOUT 으로 굳는다.
        String stored = message == null || message.length() <= AgentCommand.MESSAGE_MAX
                ? message : message.substring(0, AgentCommand.MESSAGE_MAX);
        if (repository.transition(id, DELIVERED, outcome, stored) == 0) {
            log.info("[COMMAND-RESULT-IGNORED] id={} status={} (없는 명령이거나 이미 보고됨)", id, status);
            return;
        }
        log.info("[COMMAND-RESULT] id={} status={} message={}", id, outcome, message);
    }

    public Optional<AgentCommand> find(String id) {
        return repository.findById(id);
    }

    /** since 이후 그 호스트에 나간 명령이 있는지. 인스턴스 공통 쿨다운에 쓴다. */
    public boolean dispatchedSince(String host, Instant since) {
        return repository.existsByHostAndCreatedAtAfter(host, since);
    }
}
