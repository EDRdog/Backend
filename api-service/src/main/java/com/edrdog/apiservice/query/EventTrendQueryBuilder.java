package com.edrdog.apiservice.query;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시간 단위 롤업(events_hourly) 추세 SQL 을 만드는 순수 로직.
 *
 * <p>원본 events 는 TTL 7일이라 그 뒤엔 "이 단말이 지난 두 달간 어떤 추세였나" 에 답할 데이터가 없다.
 * 롤업은 INSERT 시점에 MV 가 쌓아 두므로 원본이 지워진 뒤에도 남는다. 조회 비용 감소는 부수 효과다.
 *
 * <p>이 빌더는 롤업만 읽는다. 원본과 나눠 읽지 않으므로 경계에서 이중 계산이 날 자리가 없다.
 */
@Component
public class EventTrendQueryBuilder {

    private final String rollupTable;

    public EventTrendQueryBuilder(@Value("${edrdog.clickhouse.rollup-table}") String rollupTable) {
        this.rollupTable = rollupTable;
    }

    /**
     * tenant 격리 하에 [from,to) 구간의 시간당 이벤트 수를 type 별로 뽑는다(from/to 는 epoch millis).
     *
     * <p>시 단위라 양 끝의 부분 시간은 한 시간 전체로 나온다. from/to 의 분·초는 무시된다고 보면 된다.
     * 마지막 한 시간은 아직 채워지는 중인 값이다.
     */
    public ClickHouseQuery hourlyTrend(String tenantId, String host, String type, long from, long to) {
        return TenantScope.of(tenantId)
                .addIfText("host = {host:String}", "host", host)
                .addIfText("type = {type:String}", "type", type)
                // 롤업의 hour 는 DateTime(초)이고 API 는 밀리초를 받는다. 안 나누면 1970년으로 접힌다.
                // from 을 시로 내리지 않으면 from 이 든 버킷은 시작이 from 보다 앞이라 통째로 빠진다.
                .add("hour >= toStartOfHour(toDateTime({from:UInt64} / 1000))", "from", String.valueOf(from))
                .add("hour < toDateTime({to:UInt64} / 1000)", "to", String.valueOf(to))
                // SummingMergeTree 는 병합 전 행이 남아 있어 sum 없이 읽으면 같은 시간이 여러 줄로 나온다.
                .toQuery("SELECT hour, type, sum(cnt) AS cnt FROM " + rollupTable,
                        " GROUP BY hour, type ORDER BY hour");
    }
}
