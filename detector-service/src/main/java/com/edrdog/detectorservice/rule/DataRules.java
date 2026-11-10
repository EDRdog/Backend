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
        Resource[] files;
        try {
            files = resolver.getResources(pattern);
        } catch (IOException e) {
            log.error("룰 위치를 읽지 못해 이전 룰을 유지한다: {}", pattern, e);
            return;
        }
        if (files.length == 0) {
            log.error("룰 파일이 하나도 없어 이전 룰을 유지한다: {}", pattern);
            return;
        }
        // 파일명 순으로 정렬한다. 동률일 때 어느 룰이 이길지와 중복 판정이 실행마다 같아야 한다.
        Map<String, SigmaRule> next = new TreeMap<>();
        for (Resource file : files) {
            String name = file.getFilename();
            SigmaRule rule = parse(file, name);
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
                log.error("ruleId {} 가 앞선 파일과 겹쳐 뺀다: {}", e.getValue().ruleId(), e.getKey());
            }
            return duplicate;
        });
        byFile = next;
        rules = List.copyOf(next.values());
        log.info("탐지 룰 {}개 적용 ({})", rules.size(), pattern);
    }

    /** 파일 하나를 룰로 읽는다. 실패하면 null. */
    private SigmaRule parse(Resource file, String name) {
        try (InputStream in = file.getInputStream()) {
            return SigmaParser.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            log.error("룰 파일을 읽지 못했다. 이전 룰이 있으면 유지한다: {} ({})", name, e.getMessage());
            return null;
        }
    }
}
