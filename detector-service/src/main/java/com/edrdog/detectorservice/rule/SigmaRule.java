package com.edrdog.detectorservice.rule;

import com.edrdog.schema.Event;

import java.util.Set;
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

    public boolean matches(Event e) {
        return types.contains(e.getType()) && match.test(e);
    }
}
