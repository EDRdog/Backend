package com.edrdog.alertservice;

import com.edrdog.alertservice.dto.Alert;
import com.edrdog.alertservice.slack.SlackNotifier;
import com.edrdog.alertservice.webhook.AlertRouter;
import com.edrdog.schema.KafkaTraceLink;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * alerts 토픽을 소비해 host 소유 유저(없으면 tenant 관리자 채널)의 Slack 으로 알림을 보내는 리스너.
 * 라우팅은 {@link AlertRouter} 가 결정하고, 같은 대상+host+ruleId 는 쿨다운 창 안에서 중복 발송을 억제한다.
 */
@Component
public class AlertListener {

    private static final Logger log = LoggerFactory.getLogger(AlertListener.class);

    private final AlertRouter router;
    private final SlackNotifier slack;
    private final Cooldown cooldown;

    public AlertListener(@Value("${edrdog.alert.cooldown-ms}") long cooldownMs,
                         AlertRouter router, SlackNotifier slack) {
        this.router = router;
        this.slack = slack;
        this.cooldown = new Cooldown(cooldownMs);
    }

    // 레코드로 받아야 헤더에서 트레이스를 이어받는다. 값만 받으면 detector 와 끊긴 트레이스가 된다.
    @KafkaListener(topics = "${edrdog.kafka.alerts-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onAlert(ConsumerRecord<String, Alert> record) {
        KafkaTraceLink.accept(record.headers());
        handle(record.value());
    }

    public void handle(Alert alert) {
        if (alert == null || alert.host() == null || alert.ruleId() == null) {
            return;
        }
        if (alert.tenantId() == null) {
            log.warn("tenantId 없는 alert → 목적지 조회 불가로 skip (host={}, rule={})", alert.host(), alert.ruleId());
            return;
        }
        Optional<AlertRouter.Route> route = router.route(alert);
        if (route.isEmpty()) {
            log.warn("목적지 미등록 → 발송 skip (tenant={}, host={}, rule={})",
                    alert.tenantId(), alert.host(), alert.ruleId());
            return;
        }
        String key = route.get().cooldownId() + "|" + alert.host() + "|" + alert.ruleId();
        if (cooldown.allow(key, alert.ts())) {
            // 되돌리지 않으면 실패한 발송이 쿨다운 창을 태워 재발생분까지 막힌다.
            if (!slack.send(alert, route.get().webhookUrl())) {
                cooldown.forget(key);
            }
        }
    }
}
