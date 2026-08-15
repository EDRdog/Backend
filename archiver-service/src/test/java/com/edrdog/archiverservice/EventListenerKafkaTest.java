package com.edrdog.archiverservice;

import com.edrdog.archiverservice.clickhouse.ClickHouseWriter;
import com.edrdog.schema.Event;
import com.edrdog.schema.EventSerializer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * events 토픽에 실제로 메시지를 흘려 리스너가 적재까지 가는지 본다.
 *
 * <p>이 테스트가 없어서 사고가 났다(#247). 트레이스를 이으려고 리스너에 파라미터를 하나 붙였는데
 * 배치 리스너에는 그 헤더가 채워지지 않아 메시지마다 실패했고, ClickHouse 적재가 통째로 멈췄다.
 * 기동만 확인했기 때문에 로컬에서는 멀쩡해 보였다. 리스너 등록은 되고 레코드가 올 때 터지는 종류다.
 *
 * <p>그래서 여기서는 브로커를 띄우고 메시지를 실제로 보낸다. 적재 대상(ClickHouse)은 목으로 둔다.
 * 확인하려는 건 저장 결과가 아니라 "리스너가 레코드를 받아 값을 꺼내 넘기는가"다.
 */
@SpringBootTest(properties = {
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.fetch-min-size=1",   // 한 건만 보내므로 하한을 낮춰야 바로 온다
        "spring.kafka.listener.concurrency=1",
        "management.tracing.enabled=false",
        "management.otlp.metrics.export.enabled=false",
})
@EmbeddedKafka(partitions = 1, topics = "events")
class EventListenerKafkaTest {

    @Autowired
    EmbeddedKafkaBroker broker;

    @Autowired
    MeterRegistry meters;

    @MockitoBean
    ClickHouseWriter writer;

    @Test
    void 발행한_이벤트가_리스너를_거쳐_적재로_넘어간다() {
        Event event = Event.newBuilder()
                .setHost("host-1")
                .setType("process")
                .setTs(1700000000000L)
                .setTenantId("99")
                .build();

        try (var producer = new KafkaProducer<String, Event>(
                Map.of("bootstrap.servers", broker.getBrokersAsString()),
                new StringSerializer(), new EventSerializer())) {
            producer.send(new ProducerRecord<>("events", "host-1", event));
            producer.flush();
        }

        // 실패하면 리스너가 레코드를 못 받은 것이다. 파라미터 해석 실패가 여기서 잡힌다.
        verify(writer, timeout(15_000))
                .insert(argThat((List<Event> batch) ->
                        batch.stream().anyMatch(e -> "host-1".equals(e.getHost()))));

        // 발행에서 소비까지의 대기는 어느 스팬에도 안 잡힌다. 여기서 재지 않으면 집계할 숫자가 없다(#181).
        Timer lag = meters.find("events.kafka.lag").timer();
        org.junit.jupiter.api.Assertions.assertNotNull(lag, "events.kafka.lag 미터가 없다");
        org.junit.jupiter.api.Assertions.assertTrue(lag.count() >= 1, "lag 표본이 없다");
    }
}
