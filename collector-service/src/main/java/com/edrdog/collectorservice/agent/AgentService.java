package com.edrdog.collectorservice.agent;

import com.edrdog.collectorservice.RawEventMapper;
import com.edrdog.collectorservice.agent.domain.AgentNode;
import com.edrdog.collectorservice.agent.repository.AgentNodeRepository;
import com.edrdog.schema.TraceAttribute;
import com.edrdog.schema.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 수집 API 의 서버 로직. enroll secret/node_key 인증과 tenant 태깅·검증·발행을 담당한다. */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final TenantResolverClient tenants;
    private final AgentNodeRepository nodes;
    private final EventsProducer producer;
    private final Timer uplink;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 에이전트가 보고한 값의 상한. 이걸 넘으면 시계가 어긋났거나 값이 깨진 것이라 지표를 오염시킨다. */
    private static final long UPLINK_MAX_US = TimeUnit.MINUTES.toMicros(1);

    public AgentService(TenantResolverClient tenants, AgentNodeRepository nodes, EventsProducer producer,
                        MeterRegistry metrics) {
        this.tenants = tenants;
        this.nodes = nodes;
        this.producer = producer;
        this.uplink = Timer.builder("agent.uplink.rtt")
                .description("에이전트가 잰 events 전송 왕복 시간 — 고객사 네트워크를 타는 유일한 구간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(metrics);
    }

    /**
     * 에이전트가 자기 시계로 잰 직전 전송 왕복 시간을 집계한다.
     * 두 기기의 시계를 빼지 않아 시계 오차가 섞이지 않는다. 대신 서버 처리 시간이 포함된 값이다.
     */
    public void recordUplink(Long prevSendUs) {
        if (prevSendUs == null || prevSendUs <= 0 || prevSendUs > UPLINK_MAX_US) {
            return;
        }
        uplink.record(prevSendUs, TimeUnit.MICROSECONDS);
        // 운영은 Micrometer 를 안 내보낸다. 이 줄이 없으면 고객사 네트워크 구간이 계속 안 보인다.
        TraceAttribute.number("uplinkRttUs", prevSendUs);
    }

    /**
     * enroll secret 을 검증해 node_key 를 발급한다.
     * 재-enroll 도 토큰을 새로 발급한다. 평문을 저장하지 않아 기존 토큰을 다시 돌려줄 방법이 없다.
     * 시크릿이 비었거나 매칭 tenant 가 없으면 빈 Optional(컨트롤러가 401 로 매핑).
     */
    @Transactional
    public Optional<String> enroll(String enrollSecret, String hostIdentifier, String platform) {
        if (enrollSecret == null || enrollSecret.isBlank()) {
            return Optional.empty();
        }
        Long tenantId = tenants.resolve(enrollSecret).orElse(null);
        if (tenantId == null) {
            return Optional.empty();
        }
        String host = (hostIdentifier == null || hostIdentifier.isBlank()) ? "unknown" : hostIdentifier;
        Instant now = Instant.now();
        String nodeKey = Tokens.newToken();
        List<AgentNode> found = nodes.findAllByTenantIdAndHostIdentifier(tenantId, host);
        if (found.size() > 1) {
            // 유니크 제약이 붙기 전에 생긴 중복. 이 경로가 유일한 자동 청소 기회다.
            log.warn("중복 노드 {}건 정리 tenant={} host={}", found.size(), tenantId, host);
        }
        // 최초 등록 시각은 가장 오래된 행에서 물려받는다
        AgentNode previous = found.stream().min(Comparator.comparing(AgentNode::getEnrolledAt)).orElse(null);
        if (previous != null) {
            nodes.deleteAll(found);   // 해시가 PK 라 토큰을 새로 발급하면 행을 갈아끼워야 한다
            // flush 가 없으면 Hibernate 가 INSERT 를 DELETE 보다 앞세워 유니크 제약에 걸린다
            nodes.flush();
        }
        nodes.save(previous == null
                ? AgentNode.enroll(Tokens.hash(nodeKey), tenantId, host, platform, now)
                : AgentNode.reenroll(Tokens.hash(nodeKey), previous, platform, now));
        return Optional.of(nodeKey);
    }

    /**
     * node_key 로 노드를 찾고 마지막 관측 시각을 갱신한다.
     * 유효하지 않으면 빈 Optional(컨트롤러가 401 로 매핑).
     */
    @Transactional
    public Optional<AgentNode> authenticate(String nodeKey) {
        if (nodeKey == null || nodeKey.isBlank()) {
            return Optional.empty();
        }
        Optional<AgentNode> node = nodes.findById(Tokens.hash(nodeKey));
        node.ifPresent(n -> n.touch(Instant.now()));
        return node;
    }

    /** 이벤트 배열에 서버가 푼 tenantId 를 심고 검증을 통과한 것만 events 로 발행한다. 반환값은 발행 건수다. */
    public int publish(AgentNode node, JsonNode events) {
        String tenantId = String.valueOf(node.getTenantId());
        int accepted = 0;
        int dropped = 0;
        for (String raw : EventTagger.tag(tenantId, events, mapper)) {
            Optional<Event> event = RawEventMapper.map(raw, mapper);
            if (event.isEmpty() || !producer.publish(event.get())) {
                dropped++;
                continue;
            }
            accepted++;
        }
        if (dropped > 0) {
            // 이 로그가 없으면 에이전트가 스키마를 어긋나게 보내기 시작한 것을 알 방법이 없다.
            log.warn("이벤트 {}건 버림 host={} accepted={}", dropped, node.getHostIdentifier(), accepted);
        }
        return accepted;
    }
}
