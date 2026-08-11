package com.edrdog.archiverservice;

import com.edrdog.archiverservice.clickhouse.ClickHouseWriter;
import com.edrdog.schema.Event;
import com.edrdog.schema.KafkaTraceLink;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * events 토픽을 archiver 컨슈머 그룹으로 소비해 ClickHouse 에 적재하는 리스너.
 * detector 와 별도 그룹이라 같은 이벤트를 독립적으로 모두 받는다. 적재는 ClickHouseWriter 에 위임.
 * poll 로 받은 배치를 통째로 넘긴다.
 *
 * <p>값이 아니라 레코드로 받는 이유는 헤더에 실린 발행 측 트레이스를 이어받기 위해서다. 이게 없으면
 * Kafka 를 건널 때 트레이스가 끊겨, 서비스 맵에서 archiver 가 events 를 직접 받는다는 사실이 안 보인다.
 *
 * <p>{@code @Header(KafkaHeaders.NATIVE_HEADERS)} 로 헤더만 따로 받으려다 적재가 통째로 멈춘 적이
 * 있다(#247). 배치 리스너에는 그 헤더가 채워지지 않는다. 레코드로 받아야 한다.
 */
@Component
public class EventListener {

    private final ClickHouseWriter writer;

    public EventListener(ClickHouseWriter writer) {
        this.writer = writer;
    }

    @KafkaListener(topics = "${edrdog.kafka.events-topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onEvents(List<ConsumerRecord<String, Event>> records) {
        // 배치가 작업 단위라 레코드마다 트랜잭션을 열지 않는다. 쪼개면 INSERT 를 한 번으로 묶어
        // 파트 수를 줄인 설계가 깨진다. 트랜잭션은 부모를 하나만 가지므로 첫 레코드만 이어지는데,
        // 서비스 맵은 일부만 이어져도 그려지므로 그걸로 충분하다.
        if (!records.isEmpty()) {
            KafkaTraceLink.accept(records.get(0).headers());
        }
        writer.insert(records.stream().map(ConsumerRecord::value).toList());
    }
}
