package com.edrdog.archiverservice.alert;

import com.edrdog.archiverservice.clickhouse.ClickHouseWriter;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * alerts 토픽에 실제로 메시지를 흘려 리스너가 적재까지 가는지 본다.
 *
 * <p>리스너가 값(String) 대신 ConsumerRecord 를 받도록 바꿨다. 트레이스를 이어받으려면 헤더가 필요한데
 * 값만 받으면 헤더에 손이 닿지 않기 때문이다. 이 변경은 기동에서는 아무 티가 안 나고 레코드가 올 때만
 * 터지는 종류다(#247). 그래서 브로커를 띄우고 실제로 한 건 보낸다.
 */
@SpringBootTest(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.fetch-min-size=1",
        "spring.kafka.listener.concurrency=1",
        "management.tracing.enabled=false",
        "management.otlp.metrics.export.enabled=false",
})
@EmbeddedKafka(partitions = 1, topics = {"events", "alerts"})
class AlertIngestListenerKafkaTest {

    @Autowired
    EmbeddedKafkaBroker broker;

    @MockitoBean
    AlertClickHouseWriter alertWriter;

    @MockitoBean
    ClickHouseWriter eventWriter;   // events 리스너도 같이 뜨므로 실제 적재를 막는다

    @Test
    void 발행한_alert_가_리스너를_거쳐_적재로_넘어간다() {
        String json = """
                {"tenantId":"1","host":"host-1","ruleId":"DOWNLOAD_AND_EXECUTE","mitre":"T1105",
                 "severity":"CRITICAL","action":"kill","ts":1700000000000,"matched":["evidence"]}
                """;

        try (var producer = new KafkaProducer<String, String>(
                Map.of("bootstrap.servers", broker.getBrokersAsString()),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>("alerts", "host-1", json));
            producer.flush();
        }

        // 실패하면 리스너가 레코드를 못 받은 것이다. 파라미터 해석 실패가 여기서 잡힌다.
        verify(alertWriter, timeout(15_000))
                .insert(any(), argThat(a -> "host-1".equals(a.host())));
    }
}
