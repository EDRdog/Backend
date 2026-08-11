package com.edrdog.responderservice;

import com.edrdog.responderservice.dto.Alert;
import com.edrdog.responderservice.response.ResponsePlanner;
import com.edrdog.schema.KafkaTraceLink;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * alerts 토픽을 소비해 권장 조치를 dry-run 으로 표시(로그)만 하는 리스너.
 * 판정/쿨다운은 ResponsePlanner(순수 로직)에 위임하고, 여기서는 소비와 로그 출력만 담당한다.
 */
@Component
public class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final ResponsePlanner planner;

    public AlertListener(@Value("${edrdog.responder.cooldown-ms}") long cooldownMs) {
        this.planner = new ResponsePlanner(cooldownMs);
    }

    // 레코드로 받아야 헤더에서 트레이스를 이어받는다. 값만 받으면 detector 와 끊긴 트레이스가 된다.
    @KafkaListener(topics = "${edrdog.kafka.alerts-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onAlert(ConsumerRecord<String, Alert> record) {
        KafkaTraceLink.accept(record.headers());
        handle(record.value());
    }

    public void handle(Alert alert) {
        planner.plan(alert).ifPresent(log::info);
    }
}
