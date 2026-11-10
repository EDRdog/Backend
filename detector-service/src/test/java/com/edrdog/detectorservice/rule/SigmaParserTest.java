package com.edrdog.detectorservice.rule;

import com.edrdog.detectorservice.dto.Alert;
import com.edrdog.detectorservice.support.TestEvents;
import com.edrdog.schema.Event;
import com.edrdog.schema.EventTypes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Sigma 부분집합 파서: 필드 modifier, condition 문법, 지원하지 않는 구문의 거절. */
class SigmaParserTest {

    /** 헤더를 채운 룰 본문. detection 은 들여쓰기 없이 넘기면 여기서 붙인다. */
    private static String rule(String category, String detection) {
        return "name: test_rule\nlevel: medium\ntags:\n  - attack.t1059.001\nlogsource:\n  category: " + category
                + "\ndetection:\n" + detection.indent(2);
    }

    private static SigmaRule process(String detection) {
        return SigmaParser.parse(rule("process_creation", detection));
    }

    private static Event proc(String name, String parent, String cmdline) {
        return TestEvents.of("h", EventTypes.PROCESS, 1, name, parent, cmdline, null, 0, null, null, null, "t");
    }

    private static Event script(String name, String cmdline) {
        return TestEvents.of("h", EventTypes.SCRIPT, 1, name, "explorer.exe", cmdline, null, 0, null, null, null, "t");
    }

    // ---- 값 비교 ----

    @Test
    @DisplayName("값만 적으면 대소문자 무시 완전 일치다")
    void plainEquals_ignoresCase_andIsExact() {
        SigmaRule r = process("""
                selection:
                  CommandLine: 'whoami /all'
                condition: selection
                """);

        assertThat(r.matches(proc("a", "b", "WHOAMI /ALL"))).isTrue();
        assertThat(r.matches(proc("a", "b", "whoami /all now"))).isFalse();
    }

    @Test
    @DisplayName("contains, startswith, endswith")
    void substringModifiers() {
        SigmaRule contains = process("selection:\n  CommandLine|contains: 'Mimi'\ncondition: selection");
        SigmaRule starts = process("selection:\n  CommandLine|startswith: 'cmd /c'\ncondition: selection");
        SigmaRule ends = process("selection:\n  CommandLine|endswith: '.ps1'\ncondition: selection");

        assertThat(contains.matches(proc("a", "b", "x mimikatz y"))).isTrue();
        assertThat(contains.matches(proc("a", "b", "x y"))).isFalse();
        assertThat(starts.matches(proc("a", "b", "CMD /c dir"))).isTrue();
        assertThat(starts.matches(proc("a", "b", "x cmd /c dir"))).isFalse();
        assertThat(ends.matches(proc("a", "b", "run.PS1"))).isTrue();
        assertThat(ends.matches(proc("a", "b", "run.ps1 -x"))).isFalse();
    }

    @Test
    @DisplayName("값 목록은 하나만 맞아도 된다")
    void valueList_isOr() {
        SigmaRule r = process("selection:\n  CommandLine|contains:\n    - 'aaa'\n    - 'bbb'\ncondition: selection");

        assertThat(r.matches(proc("a", "b", "xx bbb"))).isTrue();
        assertThat(r.matches(proc("a", "b", "xx ccc"))).isFalse();
    }

    @Test
    @DisplayName("contains|all 은 값이 전부 있어야 한다")
    void containsAll_requiresEveryValue() {
        SigmaRule r = process("selection:\n  CommandLine|contains|all:\n    - 'aaa'\n    - 'bbb'\ncondition: selection");

        assertThat(r.matches(proc("a", "b", "bbb aaa"))).isTrue();
        assertThat(r.matches(proc("a", "b", "aaa only"))).isFalse();
    }

    @Test
    @DisplayName("한 selection 안의 필드는 모두 맞아야 한다")
    void fieldsInOneSelection_areAnd() {
        SigmaRule r = process("selection:\n  ParentImage: 'winword.exe'\n  CommandLine|contains: 'x'\ncondition: selection");

        assertThat(r.matches(proc("a", "winword.exe", "x"))).isTrue();
        assertThat(r.matches(proc("a", "winword.exe", "y"))).isFalse();
        assertThat(r.matches(proc("a", "excel.exe", "x"))).isFalse();
    }

    @Test
    @DisplayName("맵 목록 selection 은 하나만 맞아도 된다")
    void listOfMaps_isOr() {
        SigmaRule r = process("selection:\n  - CommandLine|contains: 'aaa'\n  - ParentImage: 'bbb.exe'\ncondition: selection");

        assertThat(r.matches(proc("a", "x.exe", "aaa"))).isTrue();
        assertThat(r.matches(proc("a", "bbb.exe", "zzz"))).isTrue();
        assertThat(r.matches(proc("a", "x.exe", "zzz"))).isFalse();
    }

    // ---- Image: 에이전트는 파일명만 보낸다 ----

    @Test
    @DisplayName("Image|endswith 에 경로 구분자가 있으면 파일명 일치로 본다 (xcmd.exe 는 아니다)")
    void imageEndswithWithSeparator_matchesBasenameExactly() {
        SigmaRule r = process("selection:\n  Image|endswith: '\\cmd.exe'\ncondition: selection");

        assertThat(r.matches(proc("cmd.exe", "x", ""))).isTrue();
        assertThat(r.matches(proc("CMD.EXE", "x", ""))).isTrue();
        assertThat(r.matches(proc("xcmd.exe", "x", ""))).isFalse();
    }

    @Test
    @DisplayName("Image 에 맥 경로 전체가 적혀도 파일명으로 본다")
    void imageFullPath_matchesBasename() {
        SigmaRule r = process("selection:\n  Image: '/usr/bin/security'\ncondition: selection");

        assertThat(r.matches(proc("security", "x", ""))).isTrue();
        assertThat(r.matches(proc("securityd", "x", ""))).isFalse();
    }

    @Test
    @DisplayName("Image 를 경로 조각으로 찾는 룰은 거절한다 (경로를 안 보낸다)")
    void imageContainsPath_isRejected() {
        assertThatThrownBy(() -> process("selection:\n  Image|contains: '\\temp\\'\ncondition: selection"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- 이벤트 type 과 필드 ----

    @Test
    @DisplayName("process_creation 은 process 와 script 이벤트에 걸리고 file 에는 안 걸린다")
    void processCreation_coversProcessAndScript() {
        SigmaRule r = process("selection:\n  CommandLine|contains: 'x'\ncondition: selection");
        Event file = TestEvents.of("h", EventTypes.FILE, 1, "f", null, "x", null, 0, null, null, null, "t");

        assertThat(r.matches(proc("a", "b", "x"))).isTrue();
        assertThat(r.matches(script("powershell.exe", "x"))).isTrue();
        assertThat(r.matches(file)).isFalse();
    }

    @Test
    @DisplayName("file_event: TargetFilename 은 전체 경로(cmdline)를 본다")
    void fileEvent_targetFilename() {
        SigmaRule r = SigmaParser.parse(rule("file_event", "selection:\n  TargetFilename|endswith: '\\lsass.dmp'\ncondition: selection"));
        Event hit = TestEvents.of("h", EventTypes.FILE, 1, "lsass.dmp", null, "C:\\Temp\\lsass.dmp", null, 0, null, null, null, "t");

        assertThat(r.matches(hit)).isTrue();
        assertThat(r.matches(proc("a", "b", "C:\\Temp\\lsass.dmp"))).isFalse();
    }

    @Test
    @DisplayName("dns_query: QueryName 은 domain 을 본다")
    void dnsQuery_queryName() {
        SigmaRule r = SigmaParser.parse(rule("dns_query", "selection:\n  QueryName|endswith: '.onion'\ncondition: selection"));
        Event hit = TestEvents.of("h", EventTypes.DNS, 1, "tor.exe", null, null, null, 0, "abc.onion", null, null, "t");

        assertThat(r.matches(hit)).isTrue();
    }

    @Test
    @DisplayName("network_connection: 포트는 숫자로 적는다")
    void networkConnection_numericPort() {
        SigmaRule r = SigmaParser.parse(rule("network_connection",
                "selection:\n  DestinationPort:\n    - 4444\n    - 5555\ncondition: selection"));
        Event hit = TestEvents.of("h", EventTypes.NETWORK, 1, "a.exe", null, null, "203.0.113.9", 4444, null, null, null, "t");
        Event miss = TestEvents.of("h", EventTypes.NETWORK, 1, "a.exe", null, null, "203.0.113.9", 443, null, null, null, "t");

        assertThat(r.matches(hit)).isTrue();
        assertThat(r.matches(miss)).isFalse();
    }

    // ---- condition ----

    private static final String THREE = """
            sel_a:
              CommandLine|contains: 'aaa'
            sel_b:
              CommandLine|contains: 'bbb'
            filter_c:
              CommandLine|contains: 'ccc'
            """;

    private static SigmaRule withCondition(String condition) {
        return process(THREE + "condition: " + condition);
    }

    @Test
    @DisplayName("condition: and, or, not")
    void condition_andOrNot() {
        assertThat(withCondition("sel_a and sel_b").matches(proc("a", "b", "aaa bbb"))).isTrue();
        assertThat(withCondition("sel_a and sel_b").matches(proc("a", "b", "aaa"))).isFalse();
        assertThat(withCondition("sel_a or sel_b").matches(proc("a", "b", "bbb"))).isTrue();
        assertThat(withCondition("sel_a and not filter_c").matches(proc("a", "b", "aaa"))).isTrue();
        assertThat(withCondition("sel_a and not filter_c").matches(proc("a", "b", "aaa ccc"))).isFalse();
    }

    @Test
    @DisplayName("condition: 괄호가 우선하고, and 가 or 보다 먼저 묶인다")
    void condition_parenthesesAndPrecedence() {
        // and 가 먼저면 sel_a or (sel_b and filter_c)
        assertThat(withCondition("sel_a or sel_b and filter_c").matches(proc("a", "b", "aaa"))).isTrue();
        assertThat(withCondition("sel_a or sel_b and filter_c").matches(proc("a", "b", "bbb"))).isFalse();
        assertThat(withCondition("(sel_a or sel_b) and filter_c").matches(proc("a", "b", "aaa"))).isFalse();
        assertThat(withCondition("(sel_a or sel_b) and filter_c").matches(proc("a", "b", "aaa ccc"))).isTrue();
    }

    @Test
    @DisplayName("condition: 1 of sel_*, all of sel_*, 1 of them")
    void condition_quantifiers() {
        assertThat(withCondition("1 of sel_*").matches(proc("a", "b", "bbb"))).isTrue();
        assertThat(withCondition("1 of sel_*").matches(proc("a", "b", "ccc"))).isFalse();
        assertThat(withCondition("all of sel_*").matches(proc("a", "b", "aaa bbb"))).isTrue();
        assertThat(withCondition("all of sel_*").matches(proc("a", "b", "aaa"))).isFalse();
        assertThat(withCondition("1 of them").matches(proc("a", "b", "ccc"))).isTrue();
        assertThat(withCondition("all of them").matches(proc("a", "b", "aaa bbb"))).isFalse();
        assertThat(withCondition("1 of sel_* and not 1 of filter_*").matches(proc("a", "b", "aaa ccc"))).isFalse();
    }

    // ---- 메타 ----

    @Test
    @DisplayName("ruleId 는 name 을 대문자로, mitre 는 첫 attack.tNNNN 태그, level 은 심각도로")
    void meta_isMapped() {
        SigmaRule r = SigmaParser.parse("""
                name: mimikatz_command_line
                level: high
                tags:
                  - attack.credential-access
                  - attack.t1003.001
                  - attack.t1003.002
                logsource:
                  category: process_creation
                detection:
                  selection:
                    CommandLine|contains: 'sekurlsa::'
                  condition: selection
                """);

        assertThat(r.ruleId()).isEqualTo("MIMIKATZ_COMMAND_LINE");
        assertThat(r.mitre()).isEqualTo("T1003.001");
        assertThat(r.severity()).isEqualTo(Alert.SEV_HIGH);
    }

    @Test
    @DisplayName("informational, low, medium 은 MEDIUM, critical 은 CRITICAL")
    void level_mapping() {
        assertThat(levelOf("process_creation", "informational")).isEqualTo(Alert.SEV_MEDIUM);
        assertThat(levelOf("process_creation", "low")).isEqualTo(Alert.SEV_MEDIUM);
        assertThat(levelOf("process_creation", "medium")).isEqualTo(Alert.SEV_MEDIUM);
        assertThat(levelOf("process_creation", "critical")).isEqualTo(Alert.SEV_CRITICAL);
    }

    @Test
    @DisplayName("process 가 아닌 이벤트 룰은 high 라도 MEDIUM 으로 깎는다 (kill 대상이 프로세스가 아니다)")
    void nonProcessRules_areCappedAtMedium() {
        assertThat(levelOf("process_creation", "high")).isEqualTo(Alert.SEV_HIGH);
        assertThat(levelOf("file_event", "high")).isEqualTo(Alert.SEV_MEDIUM);
        assertThat(levelOf("dns_query", "critical")).isEqualTo(Alert.SEV_MEDIUM);
    }

    private static String levelOf(String category, String level) {
        String field = switch (category) {
            case "file_event" -> "TargetFilename";
            case "dns_query" -> "QueryName";
            default -> "CommandLine";
        };
        return SigmaParser.parse("name: t\nlevel: " + level + "\ntags: [attack.t1059]\nlogsource:\n  category: " + category
                + "\ndetection:\n  selection:\n    " + field + "|contains: 'x'\n  condition: selection\n").severity();
    }

    // ---- 지원하지 않는 구문은 거절한다 ----

    private static void assertRejected(String yaml, String messagePart) {
        assertThatThrownBy(() -> SigmaParser.parse(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(messagePart);
    }

    private static String withDetection(String detection) {
        return rule("process_creation", detection);
    }

    @Test
    @DisplayName("지원하지 않는 modifier 는 거절한다 (re, windash, base64, cidr)")
    void unsupportedModifier_isRejected() {
        assertRejected(withDetection("selection:\n  CommandLine|re: 'a.*b'\ncondition: selection"), "re");
        assertRejected(withDetection("selection:\n  CommandLine|contains|windash: ' -x'\ncondition: selection"), "windash");
        assertRejected(withDetection("selection:\n  CommandLine|base64offset|contains: 'x'\ncondition: selection"), "base64offset");
    }

    @Test
    @DisplayName("값의 와일드카드는 거절한다")
    void wildcardValue_isRejected() {
        assertRejected(withDetection("selection:\n  CommandLine: 'a*b'\ncondition: selection"), "와일드카드");
    }

    @Test
    @DisplayName("에이전트가 보내지 않는 필드는 거절한다")
    void unknownField_isRejected() {
        assertRejected(withDetection("selection:\n  OriginalFileName: 'x.exe'\ncondition: selection"), "OriginalFileName");
    }

    @Test
    @DisplayName("null 값, 키워드 목록은 거절한다")
    void nullValueAndKeywords_areRejected() {
        assertRejected(withDetection("selection:\n  CommandLine: null\ncondition: selection"), "문자열이나 숫자");
        assertRejected(withDetection("selection:\n  - 'aaa'\n  - 'bbb'\ncondition: selection"), "키워드");
    }

    @Test
    @DisplayName("집계 condition 과 모르는 selection 이름은 거절한다")
    void unsupportedCondition_isRejected() {
        assertRejected(withDetection("selection:\n  CommandLine: 'x'\ncondition: selection | count() > 3"), "|");
        assertRejected(withDetection("selection:\n  CommandLine: 'x'\ncondition: selction"), "selction");
        assertRejected(withDetection("selection:\n  CommandLine: 'x'\ncondition: 1 of nothing_*"), "nothing_*");
    }

    @Test
    @DisplayName("지원하지 않는 logsource.category 는 거절한다")
    void unsupportedCategory_isRejected() {
        assertRejected(rule("registry_set", "selection:\n  CommandLine: 'x'\ncondition: selection"), "registry_set");
    }

    @Test
    @DisplayName("name, level, MITRE 태그가 없거나 형식이 틀리면 거절한다")
    void missingMeta_isRejected() {
        String body = "logsource:\n  category: process_creation\ndetection:\n  selection:\n    CommandLine: 'x'\n  condition: selection\n";
        assertRejected("level: medium\ntags: [attack.t1059]\n" + body, "name");
        assertRejected("name: Bad-Name\nlevel: medium\ntags: [attack.t1059]\n" + body, "name");
        assertRejected("name: t\ntags: [attack.t1059]\n" + body, "level");
        assertRejected("name: t\nlevel: urgent\ntags: [attack.t1059]\n" + body, "level");
        assertRejected("name: t\nlevel: medium\ntags: [attack.execution]\n" + body, "MITRE");
    }

    @Test
    @DisplayName("contains, startswith, endswith 에 빈 값을 주면 거절한다 (전부 맞아 버린다)")
    void emptyValueForSubstringModifier_isRejected() {
        assertRejected(withDetection("selection:\n  CommandLine|contains: ''\ncondition: selection"), "빈 값");
        assertRejected(withDetection("selection:\n  CommandLine|startswith: ''\ncondition: selection"), "빈 값");
        assertRejected(withDetection("selection:\n  CommandLine|endswith:\n    - 'abc'\n    - ''\ncondition: selection"), "빈 값");
        assertRejected(withDetection("selection:\n  Image|endswith: '\\'\ncondition: selection"), "빈 값");
    }

    @Test
    @DisplayName("같은 키가 두 번 나오면 거절한다 (뒤의 것이 앞의 것을 조용히 덮는다)")
    void duplicateKeys_areRejected() {
        assertThatThrownBy(() -> process("selection:\n  CommandLine|contains: 'aaa'\n  CommandLine|contains: 'bbb'\ncondition: selection"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("일치 modifier 를 둘 이상 쓰면 거절한다")
    void multipleMatchModifiers_areRejected() {
        assertRejected(withDetection("selection:\n  CommandLine|contains|startswith: 'x'\ncondition: selection"), "하나만");
    }

    @Test
    @DisplayName("기본 로케일이 터키어여도 대소문자 비교와 ruleId 가 같다")
    void caseHandling_ignoresDefaultLocale() {
        Locale saved = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            SigmaRule r = process("selection:\n  CommandLine|contains: 'ID'\ncondition: selection");

            assertThat(r.matches(proc("a", "b", "x id y"))).isTrue();
            assertThat(r.matches(proc("a", "b", "X ID Y"))).isTrue();
            assertThat(r.ruleId()).isEqualTo("TEST_RULE");
        } finally {
            Locale.setDefault(saved);
        }
    }

    @Test
    @DisplayName("YAML 문법이 깨지면 거절한다")
    void brokenYaml_isRejected() {
        assertThatThrownBy(() -> SigmaParser.parse("name: [unclosed\n  - x"))
                .isInstanceOf(RuntimeException.class);
    }
}
