package com.edrdog.apiservice.responder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

/**
 * responder-service 내부 API 호출 래퍼(kill 위임).
 * responder 에는 앱 레벨 인증이 없어, 접근 통제는 이 프록시의 Bearer 세션 인증 + tenant 소유 검증(AlertController)이 전부다.
 */
@Component
public class ResponderClient {

    private static final Logger log = LoggerFactory.getLogger(ResponderClient.class);

    private final RestClient http;

    public ResponderClient(@Value("${edrdog.responder.url}") String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * host 의 target 프로세스 kill 을 responder 에 요청한다. 명령이 나가면 PENDING 과 명령 id 가 온다.
     * 연결 실패·4xx/5xx·빈 응답을 FAILED 로 매핑한다. 그냥 던지면 불투명한 500 이 나가고,
     * 반대로 KILLED 로 두면 종료되지 않은 프로세스를 처리 완료로 넘긴다(fail-closed).
     */
    public KillResult kill(String host, String target) {
        try {
            KillResult result = http.post()
                    .uri("/api/responder/kill")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new KillCommand(host, target))
                    .retrieve()
                    .body(KillResult.class);
            return result != null ? result : new KillResult(host, target, "FAILED", null);
        } catch (RestClientException e) {
            log.error("responder kill 위임 실패 host={} target={} err={}", host, target, e.toString());
            return new KillResult(host, target, "FAILED", null);
        }
    }

    /**
     * 명령 결과 조회. responder 가 모르는 id 면 빈 값.
     * 다른 실패는 502 로 올린다. FAILED 로 삼키면 대시보드가 조회를 멈추고, 종료된 프로세스를 실패로 보여준다.
     */
    public Optional<KillResult> killResult(String executionId) {
        try {
            return Optional.ofNullable(http.get()
                    .uri("/api/responder/kill/{id}", executionId)
                    .retrieve()
                    .body(KillResult.class));
        } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
        } catch (RestClientException e) {
            log.error("responder 결과 조회 실패 executionId={} err={}", executionId, e.toString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "responder 결과 조회 실패");
        }
    }

    /** responder kill 요청 본문(responder KillController.KillRequest 와 동일 필드). */
    private record KillCommand(String host, String target) {
    }
}
