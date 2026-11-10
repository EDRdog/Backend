package com.edrdog.detectorservice.rule;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** 룰 파일 로더: 외부 디렉터리, 주기 리로드, 깨진 파일 처리. */
class DataRulesTest {

    @TempDir
    Path dir;

    private static String ruleYaml(String name, String needle) {
        return "name: " + name + "\nlevel: medium\ntags: [attack.t1059]\nlogsource:\n  category: process_creation\n"
                + "detection:\n  selection:\n    CommandLine|contains: '" + needle + "'\n  condition: selection\n";
    }

    private void write(String file, String content) throws IOException {
        Files.writeString(dir.resolve(file), content);
    }

    private java.util.List<String> ids(DataRules rules) {
        return rules.current().stream().map(SigmaRule::ruleId).toList();
    }

    @BeforeEach
    void setUp() throws IOException {
        write("a.yml", ruleYaml("rule_a", "aaa"));
        write("b.yml", ruleYaml("rule_b", "bbb"));
    }

    @Test
    @DisplayName("외부 디렉터리의 룰 파일을 파일명 순으로 읽는다")
    void loadsExternalDirectory() {
        DataRules rules = new DataRules(dir.toString());

        assertThat(ids(rules)).containsExactly("RULE_A", "RULE_B");
    }

    @Test
    @DisplayName("ConfigMap 마운트처럼 ..data 를 거치는 심볼릭 링크 파일도 읽고, 링크가 바뀌면 리로드로 반영한다")
    void configMapStyleSymlinks_areRead() throws IOException {
        Path mount = Files.createDirectory(dir.resolve("mount"));
        Path v1 = Files.createDirectory(mount.resolve("..v1"));
        Files.writeString(v1.resolve("a.yml"), ruleYaml("rule_a", "aaa"));
        Files.createSymbolicLink(mount.resolve("..data"), mount.relativize(v1));
        Files.createSymbolicLink(mount.resolve("a.yml"), Path.of("..data/a.yml"));

        DataRules rules = new DataRules(mount.toString());
        assertThat(ids(rules)).containsExactly("RULE_A");

        // kubelet 은 새 버전 디렉터리를 만들고 ..data 링크를 바꿔 끼운다
        Path v2 = Files.createDirectory(mount.resolve("..v2"));
        Files.writeString(v2.resolve("a.yml"), ruleYaml("rule_a2", "aaa"));
        Files.delete(mount.resolve("..data"));
        Files.createSymbolicLink(mount.resolve("..data"), mount.relativize(v2));
        rules.reload();

        assertThat(ids(rules)).containsExactly("RULE_A2");
    }

    @Test
    @DisplayName("지원하지 않는 구문의 룰은 빼고 나머지는 읽는다")
    void unsupportedRule_isSkipped_othersLoaded() throws IOException {
        write("c.yml", ruleYaml("rule_c", "ccc").replace("CommandLine|contains", "CommandLine|re"));

        DataRules rules = new DataRules(dir.toString());

        assertThat(ids(rules)).containsExactly("RULE_A", "RULE_B");
    }

    @Test
    @DisplayName("리로드: 새 파일이 생기고, 고친 파일이 바뀌고, 지운 파일이 빠진다")
    void reload_swapsInChanges() throws IOException {
        DataRules rules = new DataRules(dir.toString());

        write("c.yml", ruleYaml("rule_c", "ccc"));
        write("a.yml", ruleYaml("rule_a2", "aaa"));
        Files.delete(dir.resolve("b.yml"));
        rules.reload();

        assertThat(ids(rules)).containsExactly("RULE_A2", "RULE_C");
    }

    @Test
    @DisplayName("리로드 때 깨진 파일은 그 파일의 이전 룰을 유지한다")
    void reload_brokenFile_keepsPreviousRule() throws IOException {
        DataRules rules = new DataRules(dir.toString());

        write("a.yml", "name: [unclosed");                                  // YAML 이 깨짐
        write("b.yml", ruleYaml("rule_b", "bbb").replace("medium", "urgent")); // 룰이 깨짐
        write("c.yml", ruleYaml("rule_c", "ccc"));                          // 새 정상 파일은 반영
        rules.reload();

        assertThat(ids(rules)).containsExactly("RULE_A", "RULE_B", "RULE_C");
    }

    @Test
    @DisplayName("깨진 파일을 고치면 다음 리로드에 반영된다")
    void reload_fixedFile_isApplied() throws IOException {
        DataRules rules = new DataRules(dir.toString());
        write("a.yml", "broken: [");
        rules.reload();

        write("a.yml", ruleYaml("rule_a2", "aaa"));
        rules.reload();

        assertThat(ids(rules)).containsExactly("RULE_A2", "RULE_B");
    }

    @Test
    @DisplayName("디렉터리가 사라지거나 비면 이전 룰을 유지한다 (마운트 실패로 룰이 통째로 꺼지지 않는다)")
    void reload_missingOrEmptyDirectory_keepsPrevious() throws IOException {
        DataRules rules = new DataRules(dir.toString());

        Files.delete(dir.resolve("a.yml"));
        Files.delete(dir.resolve("b.yml"));
        rules.reload();

        assertThat(ids(rules)).containsExactly("RULE_A", "RULE_B");
    }

    @Test
    @DisplayName("리로드 때 파일이 전부 깨졌고 이전 룰과 이름이 겹치지 않아도 룰이 비지 않는다")
    void reload_allFilesBroken_keepsPrevious() throws IOException {
        DataRules rules = new DataRules(dir.toString());

        Files.delete(dir.resolve("a.yml"));
        Files.delete(dir.resolve("b.yml"));
        write("x.yml", "name: [unclosed");
        rules.reload();

        assertThat(ids(rules)).containsExactly("RULE_A", "RULE_B");
    }

    @Test
    @DisplayName("시작할 때 외부 파일이 전부 깨졌으면 번들 룰로 시작한다")
    void start_allExternalFilesBroken_usesBundled() throws IOException {
        Files.delete(dir.resolve("a.yml"));
        Files.delete(dir.resolve("b.yml"));
        write("x.yml", "name: [unclosed");

        DataRules rules = new DataRules(dir.toString());

        assertThat(rules.current()).isNotEmpty();
        assertThat(ids(rules)).doesNotContain("RULE_A");
    }

    @Test
    @DisplayName("같은 문제는 상태가 바뀔 때 한 번만 에러로 남긴다 (없는 ConfigMap 이 30초마다 에러를 쌓지 않는다)")
    void sameProblem_isLoggedOncePerStateChange() throws IOException {
        Logger logger = (Logger) LoggerFactory.getLogger(DataRules.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            DataRules rules = new DataRules(dir.toString());
            Files.delete(dir.resolve("a.yml"));
            Files.delete(dir.resolve("b.yml"));

            rules.reload();
            rules.reload();
            rules.reload();
            assertThat(errors(appender)).as("계속 비어 있는 동안").isEqualTo(1);

            write("a.yml", ruleYaml("rule_a", "aaa"));
            rules.reload();
            Files.delete(dir.resolve("a.yml"));
            rules.reload();
            assertThat(errors(appender)).as("복구됐다가 다시 비면 다시 한 번").isEqualTo(2);
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static long errors(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR).count();
    }

    @Test
    @DisplayName("같은 ruleId 가 두 파일에 있으면 뒤 파일을 뺀다")
    void duplicateRuleId_laterFileSkipped() throws IOException {
        write("c.yml", ruleYaml("rule_a", "ccc"));

        DataRules rules = new DataRules(dir.toString());

        assertThat(ids(rules)).containsExactly("RULE_A", "RULE_B");
    }

    @Test
    @DisplayName("외부 디렉터리가 없으면 번들 룰을 쓴다")
    void noExternalDirectory_usesBundled() {
        DataRules rules = new DataRules("");

        assertThat(rules.current()).isNotEmpty();
    }

    @Test
    @DisplayName("외부 디렉터리를 읽지 못하면 번들 룰로 시작한다")
    void unreadableExternalDirectory_startsWithBundled() {
        DataRules rules = new DataRules(dir.resolve("none").toString());

        assertThat(rules.current()).isNotEmpty();
    }
}
