package com.edrdog.responderservice.response;

import com.edrdog.responderservice.command.AgentCommandRepository;
import com.edrdog.responderservice.command.CommandQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실행 스위치·쿨다운·결과 조회 검증. kill 요청은 기다리지 않고 명령 id 만 돌려준다.
 */
@DataJpaTest
class ResponseExecutorTest {

    private static final long COOLDOWN_MS = 60_000;
    private static final long TIMEOUT_MS = 30_000;

    /** 시한·쿨다운을 결정적으로 검증하려고 시각을 손으로 옮기는 시계. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-07-30T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Autowired
    private AgentCommandRepository repository;

    private final MovableClock clock = new MovableClock();

    private CommandQueue queue() {
        return new CommandQueue(repository, TIMEOUT_MS, clock);
    }

    private ResponseExecutor executor(boolean enabled) {
        return new ResponseExecutor(queue(), enabled, COOLDOWN_MS, TIMEOUT_MS, clock);
    }

    /** 에이전트 한 번의 하트비트: 명령을 가져가 주어진 상태로 보고한다. */
    private void agentReports(String host, String status) {
        CommandQueue queue = queue();
        queue.drainFor(host).forEach(c -> queue.complete(c.id(), status, "테스트"));
    }

    @Test
    @DisplayName("실행 스위치 OFF 면 명령을 만들지 않고 DISABLED")
    void disabled_noCommand() {
        ExecuteResult result = executor(false).killProcess("lab-win", "powershell.exe");

        assertThat(result.status()).isEqualTo("DISABLED");
        assertThat(result.executionId()).isNull();
        assertThat(queue().drainFor("lab-win")).isEmpty();
    }

    @Test
    @DisplayName("kill 요청은 기다리지 않고 PENDING 과 명령 id 를 바로 돌려준다")
    void kill_returnsPendingImmediately() {
        ExecuteResult result = executor(true).killProcess("lab-mac", "/tmp/evil.sh");

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.executionId()).isNotBlank();
        assertThat(queue().drainFor("lab-mac")).singleElement()
                .satisfies(c -> assertThat(c.id()).isEqualTo(result.executionId()));
    }

    @Test
    @DisplayName("같은 호스트가 쿨다운 안에 다시 오면 COOLDOWN. 다른 인스턴스가 보낸 명령도 센다")
    void cooldown_sharedAcrossInstances() {
        ExecuteResult first = executor(true).killProcess("lab-win", "powershell.exe");
        ExecuteResult second = executor(true).killProcess("lab-win", "cmd.exe");

        assertThat(first.status()).isEqualTo("PENDING");
        assertThat(second.status()).isEqualTo("COOLDOWN");
        assertThat(second.executionId()).isNull();
        assertThat(queue().drainFor("lab-win")).hasSize(1);
    }

    @Test
    @DisplayName("쿨다운이 지나면 다시 나간다")
    void cooldown_expires() {
        executor(true).killProcess("lab-win", "powershell.exe");

        clock.advance(Duration.ofMillis(COOLDOWN_MS + 1));

        assertThat(executor(true).killProcess("lab-win", "cmd.exe").status()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("에이전트가 결과를 보고하기 전에는 PENDING")
    void result_pendingBeforeReport() {
        String id = executor(true).killProcess("lab-mac", "curl").executionId();

        assertThat(executor(true).result(id)).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo("PENDING");
            assertThat(r.host()).isEqualTo("lab-mac");
            assertThat(r.target()).isEqualTo("curl");
        });
    }

    @Test
    @DisplayName("에이전트가 KILLED 를 보고하면 다른 인스턴스에서 조회해도 KILLED")
    void result_killed() {
        String id = executor(true).killProcess("lab-mac", "/tmp/evil.sh").executionId();
        agentReports("lab-mac", "KILLED");

        assertThat(executor(true).result(id)).hasValueSatisfying(r -> {
            assertThat(r.status()).isEqualTo("KILLED");
            assertThat(r.executionId()).isEqualTo(id);
        });
    }

    @Test
    @DisplayName("에이전트가 NO_MATCH 를 보고하면 NO_MATCH")
    void result_noMatch() {
        String id = executor(true).killProcess("lab-mac", "curl").executionId();
        agentReports("lab-mac", "NO_MATCH");

        assertThat(executor(true).result(id)).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("NO_MATCH"));
    }

    @Test
    @DisplayName("에이전트가 모르는 상태를 보고하면 FAILED")
    void result_unknownStatusIsFailed() {
        String id = executor(true).killProcess("lab-mac", "curl").executionId();
        agentReports("lab-mac", "KILLED_MAYBE");

        assertThat(executor(true).result(id)).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("FAILED"));
    }

    @Test
    @DisplayName("시한 안에 결과가 없으면 TIMEOUT (에이전트가 안 가져갔거나 보고가 없음)")
    void result_timesOut() {
        String id = executor(true).killProcess("오프라인-호스트", "curl").executionId();

        clock.advance(Duration.ofMillis(TIMEOUT_MS + 1));

        assertThat(executor(true).result(id)).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("TIMEOUT"));
    }

    @Test
    @DisplayName("TIMEOUT 으로 보인 명령은 그 뒤에 호스트가 돌아와도 내려가지 않는다 (재시도와 겹쳐 두 번 죽이지 않는다)")
    void timedOutCommandIsNeverDelivered() {
        String id = executor(true).killProcess("오프라인-호스트", "curl").executionId();
        clock.advance(Duration.ofMillis(TIMEOUT_MS + 1));
        assertThat(executor(true).result(id)).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo("TIMEOUT"));

        clock.advance(Duration.ofMinutes(3));

        assertThat(queue().drainFor("오프라인-호스트")).isEmpty();
    }

    @Test
    @DisplayName("모르는 명령 id 는 빈 값")
    void result_unknownId() {
        assertThat(executor(true).result("없는-id")).isEmpty();
    }

    @Test
    @DisplayName("저장이 터지면 FAILED")
    void queueError_returnsFailed() {
        CommandQueue broken = new CommandQueue(repository, TIMEOUT_MS, clock) {
            @Override
            public String dispatch(String host, String type, String target) {
                throw new IllegalStateException("저장 실패");
            }
        };
        ResponseExecutor executor = new ResponseExecutor(broken, true, COOLDOWN_MS, TIMEOUT_MS, clock);

        assertThat(executor.killProcess("lab-mac", "curl").status()).isEqualTo("FAILED");
    }
}
