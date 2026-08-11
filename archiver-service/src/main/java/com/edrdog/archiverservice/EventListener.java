package com.edrdog.archiverservice;

import com.edrdog.archiverservice.clickhouse.ClickHouseWriter;
import com.edrdog.schema.Event;
import java.util.List;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * events 토픽을 archiver 컨슈머 그룹으로 소비해 ClickHouse 에 적재하는 리스너.
 * detector 와 별도 그룹이라 같은 이벤트를 독립적으로 모두 받는다. 적재는 ClickHouseWriter 에 위임.
 * poll 로 받은 배치를 통째로 넘긴다.
 *
 * <p>트레이스를 이으려고 @Header(KafkaHeaders.NATIVE_HEADERS) List&lt;Headers&gt; 파라미터를 붙였다가
 * 적재가 통째로 멈췄다(#247). 배치 리스너에는 그 헤더가 채워지지 않아 메시지마다
 * "Missing header 'kafka_nativeHeaders'" 로 실패한다. 원본 헤더가 필요하면 파라미터를
 * List&lt;ConsumerRecord&lt;K,V&gt;&gt; 로 받아야 한다. 관측 때문에 적재를 멈출 수는 없어 되돌린다.
 */
@Component
public class EventListener {

    private final ClickHouseWriter writer;

    public EventListener(ClickHouseWriter writer) {
        this.writer = writer;
    }

    @KafkaListener(topics = "${edrdog.kafka.events-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onEvents(List<Event> events) {
        writer.insert(events);
    }
}
