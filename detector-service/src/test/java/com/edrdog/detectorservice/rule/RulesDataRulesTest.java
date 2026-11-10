package com.edrdog.detectorservice.rule;

import com.edrdog.detectorservice.dto.Alert;
import com.edrdog.detectorservice.support.TestEvents;
import com.edrdog.schema.Event;
import com.edrdog.schema.EventTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** 데이터 룰이 코드 룰(R1~R5)과 한 판정으로 합쳐지는 방식. */
class RulesDataRulesTest {

    private static final String HOST = "host-1";

    private static SigmaRule dataRule(String name, String level, String category, String field, String needle) {
        return SigmaParser.parse("name: " + name + "\nlevel: " + level + "\ntags: [attack.t1003.001]\nlogsource:\n  category: "
                + category + "\ndetection:\n  selection:\n    " + field + "|contains: '" + needle + "'\n  condition: selection\n");
    }

    private static SigmaRule procRule(String name, String level, String needle) {
        return dataRule(name, level, "process_creation", "CommandLine", needle);
    }

    private static Event proc(String name, String parent, String cmdline, long ts) {
        return TestEvents.of(HOST, EventTypes.PROCESS, ts, name, parent, cmdline, null, 0, null, null, null, "tenant-a");
    }

    private static Event network(int port, long ts) {
        return TestEvents.of(HOST, EventTypes.NETWORK, ts, null, null, null, "203.0.113.9", port, null, null, null, "tenant-a");
    }

    private static Optional<Alert> eval(List<Event> prior, Event current, SigmaRule... rules) {
        return Rules.evaluate(prior, current, List.of(rules));
    }

    @Test
    @DisplayName("데이터 룰이 맞으면 그 룰의 ruleId, MITRE, 심각도로 알림이 나간다")
    void dataRule_fires() {
        Event e = proc("tool.exe", "explorer.exe", "tool.exe dumpit", 1000);

        Alert a = eval(List.of(), e, procRule("dump_tool", "medium", "dumpit")).orElseThrow();

        assertThat(a.ruleId()).isEqualTo("DUMP_TOOL");
        assertThat(a.mitre()).isEqualTo("T1003.001");
        assertThat(a.severity()).isEqualTo(Alert.SEV_MEDIUM);
        assertThat(a.action()).isEqualTo(Alert.ACTION_NOTIFY);
        assertThat(a.host()).isEqualTo(HOST);
        assertThat(a.tenantId()).isEqualTo("tenant-a");
        assertThat(a.ts()).isEqualTo(1000);
        assertThat(a.matched()).containsExactly("process tool.exe (parent explorer.exe)");
    }

    @Test
    @DisplayName("high 룰은 종료(kill)를 권고하고, 맞지 않으면 알림이 없다")
    void highDataRule_recommendsKill_andNoMatchNoAlert() {
        Event hit = proc("tool.exe", "explorer.exe", "tool.exe dumpit", 1000);
        Event miss = proc("tool.exe", "explorer.exe", "tool.exe hello", 1000);

        assertThat(eval(List.of(), hit, procRule("dump_tool", "high", "dumpit")).orElseThrow().action())
                .isEqualTo(Alert.ACTION_KILL);
        assertThat(eval(List.of(), miss, procRule("dump_tool", "high", "dumpit"))).isEmpty();
    }

    @Test
    @DisplayName("룰 없이 부르면 코드 룰만 돈다 (기존 2인자 호출과 같다)")
    void withoutDataRules_onlyCodeRules() {
        assertThat(Rules.evaluate(List.of(), proc("tool.exe", "explorer.exe", "tool.exe dumpit", 1000))).isEmpty();
    }

    @Test
    @DisplayName("R2(CRITICAL)는 같은 이벤트에 걸린 CRITICAL 데이터 룰에도 가려지지 않는다")
    void r2_isNeverShadowedByDataRule() {
        Event download = network(443, 1000);
        Event run = proc("evil.exe", "explorer.exe", "/tmp/evil.exe", 2000);

        Alert a = eval(List.of(download), run, procRule("evil_tool", "critical", "evil")).orElseThrow();

        assertThat(a.ruleId()).isEqualTo("DOWNLOAD_AND_EXECUTE");
    }

    @Test
    @DisplayName("R1(HIGH)과 같은 HIGH 데이터 룰이 겹치면 R1 이 이긴다, 더 심각한 데이터 룰은 R1 을 이긴다")
    void r1_vsDataRule_mostSevereWins_tieGoesToCode() {
        Event office = proc("winword.exe", "explorer.exe", "winword.exe", 1000);
        Event shell = proc("powershell.exe", "winword.exe", "powershell.exe -x", 2000);

        assertThat(eval(List.of(office), shell, procRule("shell_high", "high", "powershell")).orElseThrow().ruleId())
                .isEqualTo("SUSPICIOUS_PROCESS_CHAIN");
        assertThat(eval(List.of(office), shell, procRule("shell_crit", "critical", "powershell")).orElseThrow().ruleId())
                .isEqualTo("SHELL_CRIT");
    }

    @Test
    @DisplayName("R3 와 같은 MEDIUM 이면 코드 룰이 이기고, 더 심각한 데이터 룰은 R3 를 이긴다")
    void r3_vsDataRule() {
        Event script = TestEvents.of(HOST, EventTypes.SCRIPT, 1000, "bash", "zsh", "bash /tmp/run.sh", null, 0, null, null, null, "t");

        assertThat(eval(List.of(), script, procRule("run_med", "medium", "run.sh")).orElseThrow().ruleId())
                .isEqualTo("SCRIPT_FROM_TEMP_PATH");
        assertThat(eval(List.of(), script, procRule("run_high", "high", "run.sh")).orElseThrow().ruleId())
                .isEqualTo("RUN_HIGH");
    }

    @Test
    @DisplayName("같은 심각도 데이터 룰끼리는 목록의 앞선 룰이 이긴다")
    void dataRulesTie_firstWins() {
        Event e = proc("tool.exe", "explorer.exe", "tool.exe dumpit", 1000);

        assertThat(eval(List.of(), e, procRule("first", "medium", "dumpit"), procRule("second", "medium", "dumpit"))
                .orElseThrow().ruleId()).isEqualTo("FIRST");
    }

    @Test
    @DisplayName("데이터 룰에 걸리는 이벤트도 시퀀스 트리거나 상관 버퍼 대상이 되지 않는다")
    void dataRules_doNotChangeBufferBehavior() {
        Event e = proc("tool.exe", "explorer.exe", "tool.exe dumpit", 1000);

        assertThat(Rules.isSequenceTrigger(e)).isFalse();
        assertThat(Rules.isCorrelatable(e)).isFalse();
    }

    @Test
    @DisplayName("판정 중 예외를 던지는 룰이 있어도 나머지 룰과 스트림 스레드는 영향이 없다")
    void throwingRule_isSkipped() {
        SigmaRule broken = new SigmaRule("BROKEN", "T1059", Alert.SEV_HIGH, java.util.Set.of(EventTypes.PROCESS),
                e -> {
                    throw new IllegalStateException("boom");
                });
        Event e = proc("tool.exe", "explorer.exe", "tool.exe dumpit", 1000);

        Alert a = eval(List.of(), e, broken, procRule("dump_tool", "medium", "dumpit")).orElseThrow();

        assertThat(a.ruleId()).isEqualTo("DUMP_TOOL");
    }

    @Test
    @DisplayName("host 가 없으면 데이터 룰도 판정하지 않는다")
    void noHost_noAlert() {
        Event e = TestEvents.of("", EventTypes.PROCESS, 1000, "tool.exe", "x", "tool.exe dumpit", null, 0, null, null, null, "t");

        assertThat(eval(List.of(), e, procRule("dump_tool", "medium", "dumpit"))).isEmpty();
    }

    @Test
    @DisplayName("dns 이벤트의 근거 요약은 dns 형식이다 (process 형식이면 api-service 가 엉뚱한 프로세스를 짚는다)")
    void dnsEvidence_hasDnsSummary() {
        Event dns = TestEvents.of(HOST, EventTypes.DNS, 1000, "tor.exe", null, null, null, 0, "abc.onion", null, null, "t");

        Alert a = eval(List.of(), dns, dataRule("onion", "medium", "dns_query", "QueryName", ".onion")).orElseThrow();

        assertThat(a.matched()).containsExactly("dns abc.onion");
        assertThat(a.domain()).isEqualTo("abc.onion");
    }
}
