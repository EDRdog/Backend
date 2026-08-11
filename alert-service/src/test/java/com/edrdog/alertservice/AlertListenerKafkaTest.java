package com.edrdog.alertservice;

import com.edrdog.alertservice.dto.Alert;
import com.edrdog.alertservice.slack.SlackNotifier;
import com.edrdog.alertservice.webhook.AlertRouter;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * alerts 토픽에 실제로 메시지를 흘려 리스너가 발송까지 가는지 본다.
 *
 * <p>리스너가 값(Alert) 대신 ConsumerRecord 를 받도록 바꿨다. 트레이스를 이어받으려면 헤더가 필요한데
 * 값만 받으면 헤더에 손이 닿지 않기 때문이다. 이 변경은 기동에서는 아무 티가 안 나고 레코드가 올 때만
 * 터지는 종류다(#247). 그래서 브로커를 띄우고 실제로 한 건 보낸다.
 */
@SpringBootTest(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.listener.concurrency=1",
        "management.tracing.enabled=false",
})
@EmbeddedKafka(partitions = 1, topics = "alerts")
class AlertListenerKafkaTest {

    private static final String WEBHOOK = "https://hooks/abc";

    @Autowired
    EmbeddedKafkaBroker broker;

    @MockitoBean
    AlertRouter router;

    @MockitoBean
    SlackNotifier slack;

    @Test
    void 발행한_alert_가_리스너를_거쳐_발송으로_넘어간다() {
        when(router.route(any())).thenReturn(Optional.of(new AlertRouter.Route(WEBHOOK, "user:1")));

        Alert alert = new Alert("t1", "host-1", "DOWNLOAD_AND_EXECUTE", "T1105",
                Alert.SEV_CRITICAL, Alert.ACTION_KILL, System.currentTimeMillis(), List.of("evidence"));

        try (var producer = new KafkaProducer<String, Alert>(
                Map.of("bootstrap.servers", broker.getBrokersAsString()),
                new StringSerializer(), new JsonSerializer<Alert>())) {
            producer.send(new ProducerRecord<>("alerts", "host-1", alert));
            producer.flush();
        }

        // 실패하면 리스너가 레코드를 못 받은 것이다. 파라미터 해석 실패가 여기서 잡힌다.
        verify(slack, timeout(15_000))
                .send(argThat(a -> "host-1".equals(a.host())), eq(WEBHOOK));
    }
}
