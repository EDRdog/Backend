package com.edrdog.collectorservice.agent;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 프로듀서 설정이 실제로 Kafka 클라이언트까지 닿는지 본다.
 *
 * <p>yaml 키를 잘못된 자리에 두면 Spring 도 Kafka 도 아무 말 없이 무시하고 기본값으로 돈다.
 * linger.ms 는 producer 바로 아래가 아니라 producer.properties 아래여야 한다. 그걸 놓치면
 * 배치가 안 모이는데 설정 파일만 보고는 켜진 줄 안다.
 *
 * <p>배치가 모여야 압축이 의미가 있다. Kafka 압축은 레코드 배치 단위로 동작하는데,
 * 작은 레코드를 하나씩 압축하면 압축률이 나쁘고 압축 헤더 오버헤드만 붙는다(#183).
 */
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
class EventsProducerConfigTest {

    @Autowired
    private ProducerFactory<String, byte[]> producers;

    @Test
    void 배치가_모이도록_linger_와_batch_size_가_설정된다() {
        Map<String, Object> config = producers.getConfigurationProperties();

        assertEquals("5", String.valueOf(config.get(ProducerConfig.LINGER_MS_CONFIG)));
        assertEquals("131072", String.valueOf(config.get(ProducerConfig.BATCH_SIZE_CONFIG)));
    }
}
