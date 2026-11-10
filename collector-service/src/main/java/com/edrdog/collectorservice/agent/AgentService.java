package com.edrdog.collectorservice.agent;

import com.edrdog.collectorservice.RawEventMapper;
import com.edrdog.collectorservice.agent.domain.AgentNode;
import com.edrdog.collectorservice.agent.repository.AgentNodeRepository;
import com.edrdog.schema.TraceAttribute;
import com.edrdog.schema.Event;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** 수집 API 의 서버 로직. enroll secret/node_key 인증과 tenant 태깅·검증·발행을 담당한다. */
@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final TenantResolverClient tenants;
    private final AgentNodeRepository nodes;
    private final EventsProducer producer;
    private final AuditClient audit;
    private final Timer uplink;
    private final DistributionSummary wireBytes;
    private final DistributionSummary rawBytes;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 에이전트가 보고한 값의 상한. 이걸 넘으면 시계가 어긋났거나 값이 깨진 것이라 지표를 오염시킨다. */
    private static final long UPLINK_MAX_US = TimeUnit.MINUTES.toMicros(1);

    /** 에이전트의 HTTP 타임아웃(5초)이다. 이보다 오래 기다려도 에이전트는 이미 실패로 보고 다시 보낸다. */
    private static final long ACK_TIMEOUT_SECONDS = 5;

    public AgentService(TenantResolverClient tenants, AgentNodeRepository nodes, EventsProducer producer,
                        AuditClient audit, MeterRegistry metrics) {
        this.tenants = tenants;
        this.nodes = nodes;
        this.producer = producer;
        this.audit = audit;
        this.uplink = Timer.builder("agent.uplink.rtt")
                .description("에이전트가 잰 events 전송 왕복 시간 — 고객사 네트워크를 타는 유일한 구간")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(metrics);
        this.wireBytes = DistributionSummary.builder("agent.uplink.wire.bytes")
                .description("events 요청이 실제로 네트워크를 탄 바이트 — 압축 후")
                .baseUnit("bytes")
                .register(metrics);
        this.rawBytes = DistributionSummary.builder("agent.uplink.raw.bytes")
                .description("events 요청 본문을 푼 바이트 — 압축 전")
                .baseUnit("bytes")
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
     * events 요청의 압축 전/후 바이트를 집계한다. 같은 요청에서 둘을 재야 압축률을 나눌 수 있다.
     * 압축하지 않은 요청은 두 값이 같아, 그대로 압축 전 기준선이 된다.
     */
    public void recordUplinkBytes(long wire, long raw) {
        wireBytes.record(wire);
        rawBytes.record(raw);
        // 운영은 Micrometer 를 안 내보낸다. 이 두 줄이 없으면 압축 효과를 운영에서 못 잰다.
        TraceAttribute.number("uplinkWireBytes", wire);
        TraceAttribute.number("uplinkRawBytes", raw);
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

    /**
     * 이벤트 배열에 서버가 푼 tenantId·host 를 심고 검증을 통과한 것만 events 로 발행한다. 반환값은 발행 건수다.
     * 브로커 ack 를 전부 받아야 돌아온다. 하나라도 못 받으면 {@link PublishException}(503)이라 에이전트가 배치째 다시 보낸다.
     */
    public int publish(AgentNode node, JsonNode events) {
        String tenantId = String.valueOf(node.getTenantId());
        List<CompletableFuture<?>> sent = new ArrayList<>();
        int dropped = 0;
        for (String raw : EventTagger.tag(tenantId, node.getHostIdentifier(), events, mapper)) {
            Optional<Event> event = RawEventMapper.map(raw, mapper);
            if (event.isEmpty()) {
                dropped++;
                continue;
            }
            sent.add(producer.publish(event.get()));
        }
        if (dropped > 0) {
            // 이 로그가 없으면 에이전트가 스키마를 어긋나게 보내기 시작한 것을 알 방법이 없다.
            log.warn("이벤트 {}건 버림 host={} accepted={}", dropped, node.getHostIdentifier(), sent.size());
        }
        // 전부 보낸 뒤 한 번에 기다린다. 앞서 성공한 건도 재전송되지만 중복 알림은 쿨다운이 억제한다(ADR 003).
        try {
            CompletableFuture.allOf(sent.toArray(CompletableFuture[]::new)).get(ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("events 발행 실패 host={} 건수={}", node.getHostIdentifier(), sent.size(), e);
            throw new PublishException(e);
        }
        return sent.size();
    }

    /** 감사기록 한 요청의 건수 상한. 에이전트가 이 크기로 나눠 보낸다(agent/internal/audit 의 MaxBatch). */
    static final int AUDIT_MAX_EVENTS = 100;

    /** detail 길이 상한. 넘치면 잘라서 받는다. 버리면 실패 사유가 통째로 사라진다. */
    static final int AUDIT_MAX_DETAIL = 1000;

    private static final Pattern AUDIT_TYPE = Pattern.compile("[a-z_]{1,64}");

    /**
     * 에이전트 감사기록을 검증해 api-service 로 넘기고 넘긴 건수를 돌려준다.
     * 모양이 틀린 건(type 모양, result 값)은 그 건만 버린다. 한 건이 배치를 막으면 정상 기록까지 영영 못 간다.
     * 건수 초과·누락은 {@link ResponseStatusException}(400), api-service 전달 실패는 {@link AuditForwardException}(503)이다.
     */
    public int audit(AgentNode node, List<AuditEvent> raw) {
        if (raw == null || raw.size() > AUDIT_MAX_EVENTS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audit events 는 1~" + AUDIT_MAX_EVENTS + "건이어야 한다");
        }
        long now = System.currentTimeMillis();
        List<AuditEvent> valid = new ArrayList<>();
        for (AuditEvent e : raw) {
            if (e == null || e.type() == null || !AUDIT_TYPE.matcher(e.type()).matches()
                    || !("success".equals(e.result()) || "failure".equals(e.result()))) {
                continue;
            }
            String detail = e.detail() == null ? "" : e.detail();
            if (detail.length() > AUDIT_MAX_DETAIL) {
                detail = detail.substring(0, AUDIT_MAX_DETAIL);
            }
            // 시계가 틀어진 단말이 0 이나 음수를 보내면 서버 시각으로 대신한다.
            valid.add(new AuditEvent(e.ts() > 0 ? e.ts() : now, e.type(), e.result(), detail));
        }
        if (valid.size() < raw.size()) {
            log.warn("감사기록 {}건 버림 host={}", raw.size() - valid.size(), node.getHostIdentifier());
        }
        if (!valid.isEmpty()) {
            audit.forward(node.getTenantId(), node.getHostIdentifier(), valid);
        }
        return valid.size();
    }
}
