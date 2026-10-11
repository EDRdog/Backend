package com.edrdog.collectorservice.agent;

import com.edrdog.schema.Event;
import com.edrdog.schema.EventHeaders;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * 검증을 통과한 이벤트를 events 로 발행한다(detector/archiver 입력).
 *
 * <p>여기가 직렬화 지점이다. 수집 API 가 받는 원본은 JSON 이지만 검증과 정규화를 지난 뒤부터는
 * Protobuf 바이트로 나간다. 매 건 반복되던 필드명이 필드번호 태그로 바뀌어 전송량이 준다.
 *
 * <p>host 를 파티션 키로 보내 detector 상관분석의 host 별 순서를 보존한다.
 */
@Component
public class EventsProducer {

    private final KafkaTemplate<String, byte[]> template;
    private final String eventsTopic;

    public EventsProducer(KafkaTemplate<String, byte[]> template,
                          @Value("${edrdog.kafka.events-topic}") String eventsTopic) {
        this.template = template;
        this.eventsTopic = eventsTopic;
    }

    /**
     * 1건 발행. ack 는 여기서 기다리지 않는다. 건마다 기다리면 linger 배치가 안 모여 압축도 깨진다.
     * 요청 단위로 한 번에 기다리는 건 {@link AgentService#publish} 다. 동기 실패도 future 로 돌려 한 곳에서 처리한다.
     */
    public CompletableFuture<?> publish(Event event) {
        try {
            // 본문이 바이너리라 kafbat UI 로 못 읽는다. 헤더가 그 자리를 메우므로 ProducerRecord 로 직접 만든다.
            ProducerRecord<String, byte[]> record =
                    new ProducerRecord<>(eventsTopic, event.getHost(), event.toByteArray());
            EventHeaders.stamp(record.headers(), event);
            return template.send(record);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
    }
}
