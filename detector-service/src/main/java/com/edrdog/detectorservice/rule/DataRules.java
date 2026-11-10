package com.edrdog.detectorservice.rule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 룰 파일(Sigma 부분집합)을 읽어 들고 있다가 주기적으로 다시 읽는다.
 *
 * <p>번들 룰(jar 안 {@code rules/*.yml})로 시작하고, {@code edrdog.rules.dir} 이 있으면 그 디렉터리(k8s ConfigMap 마운트)로
 * 바꿔 끼운다. 디렉터리가 없거나 비어 있으면 번들 룰이 남는다. 룰 파일을 바꿔도 재배포가 필요 없다.
 *
 * <p>읽는 쪽(Kafka Streams 스레드)은 {@link #current()} 로 불변 스냅샷을 받을 뿐이고 파일을 건드리지 않는다.
 * 리로드는 별도 스레드에서 새 목록을 다 만든 뒤 참조 하나만 바꾼다.
 *
 * <p>깨진 파일 하나가 룰 전체를 끄지 않게 파일 단위로 처리한다. 읽지 못한 파일은 로그를 남기고 그 파일의 이전 룰을 유지한다.
 * 읽은 결과가 비면(파일이 없거나 전부 깨졌으면) 통째로 이전 룰을 유지한다.
 */
@Component
public class DataRules {

    private static final Logger log = LoggerFactory.getLogger(DataRules.class);

    private static final String BUNDLED = "classpath*:rules/*.yml";

    private final PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /** 외부 룰 위치. 없으면 null (번들 룰만 쓴다). */
    private final String externalPattern;

    /** 파일명 → 그 파일의 룰. 이전 룰 유지에 쓰며 시작과 리로드 스레드만 만진다. */
    private Map<String, SigmaRule> byFile = Map.of();

    /** 지금 에러로 남겨 둔 문제들. 같은 문제를 30초마다 다시 남기지 않으려고 쓴다. 리로드 스레드만 만진다. */
    private Set<String> reported = Set.of();

    private volatile List<SigmaRule> rules = List.of();

    public DataRules(@Value("${edrdog.rules.dir:}") String dir) {
        this.externalPattern = dir.isBlank() ? null : "file:" + dir + "/*.yml";
        scan(BUNDLED);
        if (externalPattern != null) {
            scan(externalPattern);
        }
    }

    /** 지금 적용 중인 룰. 파일 순서이며 바뀌지 않는 목록이다. */
    public List<SigmaRule> current() {
        return rules;
    }

    // jar 안의 번들 룰은 바뀌지 않으니 외부 위치가 있을 때만 다시 읽는다.
    @Scheduled(fixedDelayString = "${edrdog.rules.reload-ms:30000}", initialDelayString = "${edrdog.rules.reload-ms:30000}")
    void reload() {
        if (externalPattern != null) {
            scan(externalPattern);
        }
    }

    private void scan(String pattern) {
        // 이번 스캔에서 난 문제들. 이전 스캔에도 있던 문제는 다시 에러로 남기지 않는다.
        Set<String> problems = new LinkedHashSet<>();
        try {
            apply(pattern, problems);
        } finally {
            problems.stream().filter(p -> !reported.contains(p)).forEach(log::error);
            reported = problems;
        }
    }

    private void apply(String pattern, Set<String> problems) {
        Resource[] files;
        try {
            files = resolver.getResources(pattern);
        } catch (IOException e) {
            problems.add("룰 위치를 읽지 못해 이전 룰을 유지한다: " + pattern + " (" + e.getMessage() + ")");
            return;
        }
        if (files.length == 0) {
            problems.add("룰 파일이 하나도 없어 이전 룰을 유지한다: " + pattern);
            return;
        }
        // 파일명 순으로 정렬한다. 동률일 때 어느 룰이 이길지와 중복 판정이 실행마다 같아야 한다.
        Map<String, SigmaRule> next = new TreeMap<>();
        for (Resource file : files) {
            String name = file.getFilename();
            SigmaRule rule = parse(file, name, problems);
            if (rule == null) {
                rule = byFile.get(name);   // 깨진 파일은 그 파일의 이전 룰이 남는다
            }
            if (rule != null) {
                next.put(name, rule);
            }
        }
        Set<String> seen = new HashSet<>();
        next.entrySet().removeIf(e -> {
            boolean duplicate = !seen.add(e.getValue().ruleId());
            if (duplicate) {
                problems.add("ruleId " + e.getValue().ruleId() + " 가 앞선 파일과 겹쳐 뺀다: " + e.getKey());
            }
            return duplicate;
        });
        if (next.isEmpty()) {
            // 파일은 있는데 하나도 못 읽었다. 이걸 적용하면 탐지 룰이 0개가 된다.
            problems.add("읽을 수 있는 룰이 하나도 없어 이전 룰을 유지한다: " + pattern);
            return;
        }
        boolean changed = !ruleIds(next).equals(ruleIds(byFile));
        byFile = next;
        rules = List.copyOf(next.values());
        if (changed) {
            log.info("탐지 룰 {}개 적용 ({})", rules.size(), pattern);
        }
    }

    private static List<String> ruleIds(Map<String, SigmaRule> byFile) {
        return byFile.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue().ruleId()).toList();
    }

    /** 파일 하나를 룰로 읽는다. 실패하면 문제를 적고 null. */
    private SigmaRule parse(Resource file, String name, Set<String> problems) {
        try (InputStream in = file.getInputStream()) {
            return SigmaParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            problems.add("룰 파일을 읽지 못했다. 이전 룰이 있으면 유지한다: " + name + " (" + e.getMessage() + ")");
            return null;
        }
    }
}
