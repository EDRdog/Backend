package com.edrdog.detectorservice.tracing;

import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import com.newrelic.api.agent.TransportType;
import org.apache.kafka.common.header.Header;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.stream.StreamSupport;

/**
 * Kafka 레코드 하나를 발행 측 트레이스에 이어 붙인다.
 *
 * <p>여기가 없으면 Kafka 를 건널 때 트레이스가 끊긴다. 프로듀서는 헤더에 컨텍스트를 싣지만
 * (실측 확인), Kafka Streams 는 폴 단위로 트랜잭션을 열어 배치에 부모를 하나만 달 수 있다.
 * 배치가 500건이면 499건이 출처를 잃는다. 그래서 레코드마다 트랜잭션을 직접 연다(#235).
 *
 * <p><b>전제</b>: {@code newrelic.yml} 에서 {@code kafka-streams-spans} 가 꺼져 있어야 한다.
 * 켜져 있으면 폴 단위 트랜잭션이 이미 열려 있어서, 아래 {@code @Trace(dispatcher = true)} 가
 * 새 트랜잭션이 아니라 기존 트랜잭션의 구간이 되어 버린다.
 *
 * <p>판정 로직에서 이 클래스만 부르게 격리해 두었다. 관측 백엔드를 옮기면 여기만 걷어내면 된다.
 * 에이전트가 없으면 API 가 알아서 아무 일도 안 하므로 로컬 실행에도 영향이 없다.
 */
public final class KafkaTraceLink {

    private KafkaTraceLink() {
    }

    /** 레코드 하나를 별도 트랜잭션으로 열고, 헤더에 실린 발행 측 트레이스에 이어 붙인다. */
    @Trace(dispatcher = true)
    public static void linked(Iterable<Header> kafkaHeaders, Runnable body) {
        NewRelic.getAgent().getTransaction()
                .acceptDistributedTraceHeaders(TransportType.Kafka, new KafkaHeaders(kafkaHeaders));
        body.run();
    }

    /** 뉴렐릭이 읽을 수 있게 Kafka 헤더를 감싼다. 읽기 전용이라 쓰기 메서드는 비워 둔다. */
    private record KafkaHeaders(Iterable<Header> delegate) implements com.newrelic.api.agent.Headers {

        @Override
        public HeaderType getHeaderType() {
            return HeaderType.MESSAGE;
        }

        @Override
        public String getHeader(String name) {
            for (Header h : delegate) {
                if (h.key().equals(name) && h.value() != null) {
                    return new String(h.value(), StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        @Override
        public Collection<String> getHeaders(String name) {
            String v = getHeader(name);
            return v == null ? List.of() : List.of(v);
        }

        @Override
        public Collection<String> getHeaderNames() {
            return StreamSupport.stream(delegate.spliterator(), false).map(Header::key).toList();
        }

        @Override
        public void setHeader(String name, String value) {
        }

        @Override
        public void addHeader(String name, String value) {
        }

        @Override
        public boolean containsHeader(String name) {
            return getHeader(name) != null;
        }
    }
}
