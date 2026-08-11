package com.edrdog.archiverservice;

import com.edrdog.archiverservice.clickhouse.ClickHouseWriter;
import com.edrdog.schema.Event;
import com.edrdog.schema.KafkaTraceLink;
import java.util.List;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * events 토픽을 archiver 컨슈머 그룹으로 소비해 ClickHouse 에 적재하는 리스너.
 * detector 와 별도 그룹이라 같은 이벤트를 독립적으로 모두 받는다. 적재는 ClickHouseWriter 에 위임.
 * poll 로 받은 배치를 통째로 넘긴다.
 */
@Component
public class EventListener {

    private final ClickHouseWriter writer;

    public EventListener(ClickHouseWriter writer) {
        this.writer = writer;
    }

    /**
     * @param headers 발행 측 트레이스를 이어받기 위한 레코드별 원본 헤더. 적재에는 쓰지 않는다.
     *                이게 없으면 Kafka 를 건널 때 트레이스가 끊겨 서비스 맵에 collector 와 안 이어진다(#235).
     */
    @KafkaListener(topics = "${edrdog.kafka.events-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onEvents(List<Event> events,
                         @Header(KafkaHeaders.NATIVE_HEADERS) List<Headers> headers) {
        // 배치가 작업 단위라 레코드마다 트랜잭션을 열지 않는다. 쪼개면 INSERT 를 묶어 파트 수를 줄인
        // 설계가 깨진다. 트랜잭션은 부모를 하나만 가지므로 첫 레코드만 이어지는데, 서비스 맵은
        // 일부만 이어져도 그려지므로 그걸로 충분하다.
        if (!headers.isEmpty()) {
            KafkaTraceLink.accept(headers.get(0));
        }
        writer.insert(events);
    }
}
