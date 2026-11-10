package com.edrdog.collectorservice.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;

/**
 * 에이전트 감사기록을 api-service 로 넘기는 호출({@code POST {base}/api/internal/audit}, 헤더 {@code X-Internal-Key}).
 * 몸통은 {@code {"tenantId": 숫자, "host": 문자열, "events": [{ts, type, result, detail}]}} 이다.
 * 못 넘기면 {@link AuditForwardException} 을 올린다. 이벤트와 달리 감사기록은 잃으면 안 되어 로그만 남기고 삼키지 않는다.
 */
@Component
public class AuditClient {

    private final RestClient http;

    public AuditClient(@Value("${edrdog.api.url}") String baseUrl,
                       @Value("${edrdog.internal.key}") String internalKey) {
        this.http = RestClient.builder().baseUrl(baseUrl).defaultHeader("X-Internal-Key", internalKey).build();
    }

    /** 같은 기록을 두 번 받을 수 있다. 응답이 유실되면 에이전트가 같은 배치를 다시 보내기 때문이다. */
    public void forward(long tenantId, String host, List<AuditEvent> events) {
        try {
            http.post()
                    .uri("/api/internal/audit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Body(tenantId, host, events))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            throw new AuditForwardException(e);
        }
    }

    /** api-service 가 받는 본문. */
    record Body(long tenantId, String host, List<AuditEvent> events) {
    }
}
