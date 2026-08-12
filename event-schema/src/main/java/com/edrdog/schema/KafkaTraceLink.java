package com.edrdog.schema;

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
 * <p>벤더 API 를 이 클래스에만 두었다. 관측 백엔드를 옮기면 여기만 걷어내면 된다.
 * 에이전트가 없으면 API 가 알아서 아무 일도 안 하므로 로컬 실행과 테스트에 영향이 없다.
 *
 * <p>events 토픽을 쓰는 쪽이 다 같이 필요해서 event-schema 에 둔다. 헤더 규약을 정의한
 * {@link EventHeaders} 옆자리다.
 */
public final class KafkaTraceLink {

    private KafkaTraceLink() {
    }

    /**
     * 레코드 하나를 별도 트랜잭션으로 열고, 헤더에 실린 발행 측 트레이스에 이어 붙인다.
     * 레코드마다 처리가 일어나는 쪽(detector 의 판정)에 쓴다.
     */
    @Trace(dispatcher = true)
    public static void linked(Iterable<Header> kafkaHeaders, Runnable body) {
        accept(kafkaHeaders);
        body.run();
    }

    /**
     * 이어 붙일 부모 없이 트랜잭션만 연다.
     *
     * <p>레코드가 아니라 벽시계가 부르는 쪽(detector 의 punctuator)에 쓴다. 거기서 발행하는 알림은
     * 트리거가 특정 레코드가 아니라 "grace 가 지났다"라서 이어 붙일 부모가 없다. 그래도 트랜잭션이
     * 있어야 프로듀서 계측이 헤더를 실어 주고, 그래야 alert/responder/archiver 가 detector 에 이어진다.
     *
     * <p>트랜잭션이 없으면 헤더가 통째로 안 실린다. 시퀀스 룰(R1·R2)은 대부분 이 경로로 발행되므로
     * 이게 없으면 이 프로젝트의 핵심 탐지 경로가 트레이스 밖에 남는다.
     *
     * <p>⚠️ 부를 때마다 트랜잭션이 하나 생긴다. punctuate 처럼 자주 도는 자리에서는 실제로 할 일이
     * 있을 때만 불러야 한다. 매 tick 감싸면 초당 두 개씩 빈 트랜잭션이 쌓인다.
     */
    @Trace(dispatcher = true)
    public static void traced(Runnable body) {
        body.run();
    }

    /**
     * 이미 열려 있는 트랜잭션을 발행 측 트레이스에 이어 붙이기만 한다.
     *
     * <p>배치가 작업 단위인 쪽(archiver 의 ClickHouse 적재)에 쓴다. 거기서는 레코드마다 트랜잭션을
     * 열 이유가 없다. 실제 작업이 배치당 한 번이고, 쪼개면 INSERT 를 묶어 파트 수를 줄인 설계가 깨진다.
     *
     * <p>트랜잭션은 부모를 하나만 가질 수 있으므로 배치의 첫 레코드만 이어진다. 서비스 맵은 트레이스가
     * 전부 이어져야 그려지는 게 아니라 일부만 이어져도 되므로 이걸로 충분하다.
     */
    public static void accept(Iterable<Header> kafkaHeaders) {
        NewRelic.getAgent().getTransaction()
                .acceptDistributedTraceHeaders(TransportType.Kafka, new KafkaHeaders(kafkaHeaders));
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
