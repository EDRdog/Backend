package com.edrdog.responderservice.command;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DB 에 둔 명령의 수령·결과 보고·만료·인스턴스 간 공유 검증. */
@DataJpaTest
class CommandQueueTest {

    private static final long WINDOW_MS = 30_000;

    /** 만료를 결정적으로 검증하려고 시각을 손으로 옮기는 시계. */
    static final class MovableClock extends Clock {
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
        return new CommandQueue(repository, WINDOW_MS, clock);
    }

    @Test
    @DisplayName("dispatch 한 명령이 그 호스트의 drainFor 로 나온다")
    void dispatchThenDrain() {
        CommandQueue queue = queue();
        String id = queue.dispatch("lab-mac", "kill_process", "/tmp/evil.sh");

        List<Command> drained = queue.drainFor("lab-mac");

        assertThat(drained).singleElement().satisfies(c -> {
            assertThat(c.id()).isEqualTo(id);
            assertThat(c.type()).isEqualTo("kill_process");
            assertThat(c.target()).isEqualTo("/tmp/evil.sh");
        });
    }

    @Test
    @DisplayName("한 번 꺼낸 명령은 다시 나오지 않는다")
    void drainIsOnce() {
        CommandQueue queue = queue();
        queue.dispatch("lab-mac", "kill_process", "curl");

        assertThat(queue.drainFor("lab-mac")).hasSize(1);
        assertThat(queue.drainFor("lab-mac")).isEmpty();
    }

    @Test
    @DisplayName("다른 인스턴스가 넣은 명령도 꺼낼 수 있고, 한쪽이 꺼내면 다른 쪽엔 안 나온다")
    void sharedAcrossInstances() {
        CommandQueue a = queue();
        CommandQueue b = queue();
        String id = a.dispatch("lab-mac", "kill_process", "curl");

        assertThat(b.drainFor("lab-mac")).singleElement().extracting(Command::id).isEqualTo(id);
        assertThat(a.drainFor("lab-mac")).isEmpty();
    }

    @Test
    @DisplayName("다른 호스트의 명령은 섞이지 않는다")
    void hostsAreIsolated() {
        CommandQueue queue = queue();
        queue.dispatch("lab-mac", "kill_process", "curl");
        queue.dispatch("lab-win", "kill_process", "evil.exe");

        assertThat(queue.drainFor("lab-mac")).singleElement()
                .extracting(Command::target).isEqualTo("curl");
        assertThat(queue.drainFor("lab-win")).singleElement()
                .extracting(Command::target).isEqualTo("evil.exe");
    }

    @Test
    @DisplayName("명령이 없는 호스트는 빈 목록")
    void unknownHostIsEmpty() {
        assertThat(queue().drainFor("없는-호스트")).isEmpty();
    }

    @Test
    @DisplayName("꺼내 간 명령에 결과를 보고하면 find 로 보인다")
    void completeIsVisible() {
        CommandQueue queue = queue();
        String id = queue.dispatch("lab-mac", "kill_process", "curl");
        queue.drainFor("lab-mac");

        queue.complete(id, "KILLED", "pid 4242 종료");

        assertThat(queue.find(id)).hasValueSatisfying(c -> {
            assertThat(c.getStatus()).isEqualTo("KILLED");
            assertThat(c.getMessage()).isEqualTo("pid 4242 종료");
        });
    }

    @Test
    @DisplayName("모르는 상태 문자열은 FAILED 로 저장한다 (엔드포인트 값을 그대로 믿지 않는다)")
    void unknownStatusStoredAsFailed() {
        CommandQueue queue = queue();
        String id = queue.dispatch("lab-mac", "kill_process", "curl");
        queue.drainFor("lab-mac");

        queue.complete(id, "KILLED_MAYBE", "?");

        assertThat(queue.find(id)).hasValueSatisfying(c -> assertThat(c.getStatus()).isEqualTo("FAILED"));
    }

    @Test
    @DisplayName("긴 보고 메시지는 컬럼 길이로 잘라 저장한다 (결과가 유실되지 않는다)")
    void longMessageTruncated() {
        CommandQueue queue = queue();
        String id = queue.dispatch("lab-mac", "kill_process", "curl");
        queue.drainFor("lab-mac");

        queue.complete(id, "FAILED", "x".repeat(5000));

        assertThat(queue.find(id)).hasValueSatisfying(c -> {
            assertThat(c.getStatus()).isEqualTo("FAILED");
            assertThat(c.getMessage()).hasSize(AgentCommand.MESSAGE_MAX);
        });
    }

    @Test
    @DisplayName("아직 꺼내 가지 않은 명령에 온 결과는 버린다")
    void completeBeforeDeliveryIgnored() {
        CommandQueue queue = queue();
        String id = queue.dispatch("lab-mac", "kill_process", "curl");

        queue.complete(id, "KILLED", "위조");

        assertThat(queue.find(id)).hasValueSatisfying(c -> assertThat(c.getStatus()).isEqualTo(CommandQueue.PENDING));
    }

    @Test
    @DisplayName("이미 결과가 있는 명령에 다시 온 보고는 덮어쓰지 않는다")
    void secondReportIgnored() {
        CommandQueue queue = queue();
        String id = queue.dispatch("lab-mac", "kill_process", "curl");
        queue.drainFor("lab-mac");
        queue.complete(id, "KILLED", "첫 보고");

        queue.complete(id, "FAILED", "두 번째");

        assertThat(queue.find(id)).hasValueSatisfying(c -> assertThat(c.getStatus()).isEqualTo("KILLED"));
    }

    @Test
    @DisplayName("모르는 명령 id 에 온 보고는 터지지 않는다")
    void unknownIdIgnored() {
        queue().complete("없는-id", "KILLED", "늦은 보고");

        assertThat(queue().find("없는-id")).isEmpty();
    }

    @Test
    @DisplayName("전달 시한을 넘긴 명령은 drainFor 에 나오지 않는다")
    void expiredIsNotDrained() {
        CommandQueue queue = queue();
        queue.dispatch("lab-mac", "kill_process", "curl");

        clock.advance(Duration.ofMillis(WINDOW_MS + 1));

        assertThat(queue.drainFor("lab-mac")).isEmpty();
    }

    @Test
    @DisplayName("전달 시한 안이면 그대로 남는다")
    void notYetExpiredStays() {
        CommandQueue queue = queue();
        queue.dispatch("lab-mac", "kill_process", "curl");

        clock.advance(Duration.ofMillis(WINDOW_MS - 1));

        assertThat(queue.drainFor("lab-mac")).hasSize(1);
    }

    @Test
    @DisplayName("주어진 시각 이후 그 호스트에 나간 명령이 있는지 안다 (인스턴스 공통 쿨다운)")
    void dispatchedSince() {
        CommandQueue queue = queue();
        Instant before = clock.instant();
        queue.dispatch("lab-mac", "kill_process", "curl");

        assertThat(queue().dispatchedSince("lab-mac", before.minusMillis(1))).isTrue();
        assertThat(queue().dispatchedSince("lab-win", before.minusMillis(1))).isFalse();
        assertThat(queue().dispatchedSince("lab-mac", before.plusMillis(1))).isFalse();
    }
}
