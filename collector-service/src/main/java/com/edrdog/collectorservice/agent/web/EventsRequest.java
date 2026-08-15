package com.edrdog.collectorservice.agent.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 이벤트 전송 요청. events 는 detector 가 판정 입력으로 쓰는 스키마 그대로의 평평한 객체 배열이다.
 * 여기서 형태를 강제하면(DTO 로 바꾸면) 스키마가 늘 때마다 서버를 다시 배포해야 한다.
 *
 * <p>prevSendUs 는 에이전트가 직전 전송 왕복에 쓴 마이크로초다. 서버는 자기 인바운드 처리 시간만
 * 알아서 업링크에 얼마를 썼는지 모른다. 첫 전송에는 실을 값이 없어 빠진다(null).
 */
public record EventsRequest(
        JsonNode events,
        @JsonProperty("prev_send_us") Long prevSendUs
) {
}
