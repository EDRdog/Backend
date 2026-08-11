package com.edrdog.archiverservice.alert;

import com.edrdog.archiverservice.alert.dto.Alert;
import com.edrdog.schema.KafkaTraceLink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * alerts 토픽을 archiver-alerts 컨슈머 그룹으로 소비해 ClickHouse 에 적재하는 리스너.
 * 값은 alertConsumerFactory(AlertKafkaConfig)가 문자열로 넘겨주므로 여기서 ObjectMapper 로 직접 파싱한다.
 * tenantId/host/ruleId 없는 alert 는 조용히 버린다.
 */
@Component
public class AlertIngestListener {

    private static final Logger log = LoggerFactory.getLogger(AlertIngestListener.class);

    private final AlertClickHouseWriter writer;
    private final ObjectMapper mapper;

    public AlertIngestListener(AlertClickHouseWriter writer, ObjectMapper mapper) {
        this.writer = writer;
        this.mapper = mapper;
    }

    // 레코드로 받아야 헤더에서 트레이스를 이어받는다. 값만 받으면 detector 와 끊긴 트레이스가 된다.
    @KafkaListener(topics = "${edrdog.kafka.alerts-topic}", containerFactory = "alertListenerContainerFactory")
    public void onAlert(ConsumerRecord<String, String> record) {
        KafkaTraceLink.accept(record.headers());
        handle(record.value());
    }

    public void handle(String message) {
        Alert alert = parse(message);
        if (alert == null || alert.tenantId() == null || alert.tenantId().isBlank()
                || alert.host() == null || alert.ruleId() == null) {
            return;
        }
        String id = AlertId.of(alert.tenantId(), alert.host(), alert.ruleId(), alert.ts());
        writer.insert(id, alert);
    }

    private Alert parse(String message) {
        try {
            return mapper.readValue(message, Alert.class);
        } catch (Exception e) {
            log.warn("alert 파싱 실패, 버립니다: {}", message, e);
            return null;
        }
    }
}
