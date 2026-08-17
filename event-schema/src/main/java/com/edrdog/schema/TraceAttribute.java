package com.edrdog.schema;

import com.newrelic.api.agent.NewRelic;

/**
 * 지금 트랜잭션에 숫자 속성을 하나 심는다. 운영에서 그 값을 조회하려면 이게 필요하다.
 *
 * <p>운영은 앱의 OTLP 전송을 끄고(매니페스트의 {@code OTEL_ENABLED=false}) 관측을 자바 에이전트에
 * 몰아준다. 그래서 Micrometer 미터는 로컬에서만 보이고 뉴렐릭에는 한 건도 안 올라간다.
 * 운영에서 봐야 하는 값은 미터와 별개로 여기를 한 번 더 거쳐야 한다.
 *
 * <p>속성은 이미 열려 있는 트랜잭션에 얹히므로 새 파이프라인도 새 시계열도 만들지 않는다.
 * 대신 트랜잭션 하나에 값 하나다. 배치 안에서 여러 건이면 대표값을 골라 넣어야 한다.
 *
 * <p>벤더 API 를 쓰는 자리는 여기와 {@link KafkaTraceLink} 둘뿐이다. 관측 백엔드를 옮기면
 * 이 둘만 걷어내면 된다. 에이전트가 없으면 API 가 알아서 아무 일도 안 하므로 로컬 실행과
 * 테스트에 영향이 없다.
 */
public final class TraceAttribute {

    private TraceAttribute() {
    }

    /** 트랜잭션이 열려 있지 않으면 아무 일도 일어나지 않는다. */
    public static void number(String key, Number value) {
        NewRelic.addCustomParameter(key, value);
    }
}
