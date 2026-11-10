package com.edrdog.collectorservice.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * events 요청의 events 배열 각 원소 루트에 tenantId 와 host 를 심는 순수 로직.
 * 이벤트에 이미 들어 있어도 신뢰하지 않고 node_key 로 푼 노드의 값으로 덮어쓴다(엔드포인트 위조 방지).
 */
public final class EventTagger {

    private EventTagger() {
    }

    public static List<String> tag(String tenantId, String host, JsonNode events, ObjectMapper mapper) {
        List<String> out = new ArrayList<>();
        if (events == null || !events.isArray()) {
            return out;
        }
        for (JsonNode event : events) {
            if (event == null || !event.isObject()) {
                continue;
            }
            // host 를 믿으면 인증된 노드가 다른 기기 이름으로 이벤트를 넣어 그 기기의 탐지를 흐린다
            ObjectNode tagged = ((ObjectNode) event).put("tenantId", tenantId).put("host", host);
            try {
                out.add(mapper.writeValueAsString(tagged));
            } catch (Exception e) {
                throw new IllegalStateException("이벤트 직렬화 실패", e);
            }
        }
        return out;
    }
}
