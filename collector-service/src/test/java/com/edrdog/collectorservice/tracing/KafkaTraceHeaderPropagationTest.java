package com.edrdog.collectorservice.tracing;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KafkaTemplate 이 발행할 때 트레이스 컨텍스트를 메시지 헤더에 싣는지 본다.
 *
 * <p>서비스 사이가 HTTP 호출이 아니라 발행-구독이라, 헤더에 실리지 않으면 컨슈머는 부모가 없다고 보고
 * 새 traceId 를 발급한다. 그래서 Kafka 를 건널 때마다 트레이스가 끊기고 서비스 맵에 노드가 하나만 뜬다.
 *
 * <p><b>지금은 실패한다.</b> 아래 전제가 전부 갖춰졌는데도 헤더가 비어 있는 것을 확인했다(#235).
 * 운영에서 kafka-console-consumer 로 본 것과 같은 현상을 여기서 몇 초 만에 재현한다.
 *
 * <pre>
 *   Tracer               OtelTracer 존재
 *   Propagator           OtelPropagator 존재
 *   ObservationRegistry  활성 (noop 아님)
 *   발행 시점 활성 스팬    있음
 *   observation-enabled  true
 *   → traceparent 헤더    없음
 * </pre>
 *
 * <p>다음에 이어갈 때는 배포하지 말고 여기서 시작한다. 설정만 보고 되겠거니 하다가 배포를 두 번
 * 헛돌렸다(#229, #232). Spring Kafka 가 observation 을 KafkaTemplate 에 어떻게 적용하는지부터 본다.
 */
@Disabled("미해결: 전제가 다 갖춰져도 traceparent 가 안 실린다. 조사 재개용 재현 하네스다 (#235)")
@SpringBootTest(properties = {
        "management.tracing.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "spring.kafka.template.observation-enabled=true",
        "management.otlp.metrics.export.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:trace;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
})
@EmbeddedKafka(partitions = 1, topics = KafkaTraceHeaderPropagationTest.TOPIC)
class KafkaTraceHeaderPropagationTest {

    static final String TOPIC = "trace-propagation-probe";

    @Autowired
    KafkaTemplate<String, byte[]> template;

    @Autowired
    EmbeddedKafkaBroker broker;

    @Autowired
    io.micrometer.tracing.Tracer tracer;

    @Test
    void 발행한_메시지_헤더에_traceparent_가_실린다() {
        // 운영에서는 HTTP 요청 스팬 안에서 발행이 일어난다. 조건을 같게 맞춘다.
        var scoped = tracer.startScopedSpan("probe");
        try {
            template.send(TOPIC, "host-1", new byte[]{1, 2, 3});
        } finally {
            scoped.end();
        }

        var consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, byte[]>(Map.of(
                "bootstrap.servers", broker.getBrokersAsString(),
                "group.id", "probe",
                "auto.offset.reset", "earliest",
                "key.deserializer", org.apache.kafka.common.serialization.StringDeserializer.class,
                "value.deserializer", org.apache.kafka.common.serialization.ByteArrayDeserializer.class));
        consumer.subscribe(List.of(TOPIC));

        ConsumerRecord<String, byte[]> record = KafkaTestUtils.getSingleRecord(consumer, TOPIC);
        consumer.close();

        var keys = Arrays.stream(record.headers().toArray()).map(Header::key).toList();
        assertThat(keys)
                .as("헤더 목록: %s — traceparent 가 없으면 Kafka 를 건널 때 트레이스가 끊긴다", keys)
                .contains("traceparent");
    }
}
