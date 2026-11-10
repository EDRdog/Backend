package com.edrdog.detectorservice.rule;

import com.edrdog.schema.Event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * 파일에서 읽은 단일 이벤트(point) 룰 하나.
 *
 * @param ruleId   알림의 ruleId. 룰 파일의 name 을 대문자로 바꾼 값
 * @param mitre    알림의 MITRE 기법. 룰 파일 tags 의 첫 attack.tNNNN 태그
 * @param severity Alert.SEV_* 중 하나
 * @param types    이 룰이 보는 이벤트 type (logsource.category 에서 정해진다)
 * @param match    detection + condition 을 합친 판정
 */
public record SigmaRule(String ruleId, String mitre, String severity, Set<String> types, Predicate<Event> match) {

    private static final Logger log = LoggerFactory.getLogger(SigmaRule.class);

    /** 예외를 던진 룰의 ruleId. 이벤트마다 로그를 쏟지 않으려고 룰당 한 번만 남긴다. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    public boolean matches(Event e) {
        if (!types.contains(e.getType())) {
            return false;
        }
        try {
            return match.test(e);
        } catch (RuntimeException ex) {
            // 룰 하나의 결함이 Streams 스레드를 죽이면 그 파티션의 탐지가 통째로 멈춘다. 그 룰만 안 걸린 것으로 본다.
            if (WARNED.add(ruleId)) {
                log.error("룰 판정 중 예외가 나서 이 룰은 건너뛴다: {}", ruleId, ex);
            }
            return false;
        }
    }
}
