package com.edrdog.collectorservice.agent;

/**
 * 에이전트 감사기록 한 건(KISA BF-13). 에이전트가 보내는 모양과 api-service 로 넘기는 모양이 같다.
 * ts 는 epoch millis, result 는 success 또는 failure 다. tenant 와 host 는 여기 없다. 서버가 node_key 로 풀어 붙인다.
 */
public record AuditEvent(long ts, String type, String result, String detail) {
}
