package com.edrdog.apiservice.selftest.web;

import com.edrdog.apiservice.selftest.SelfTestItem;
import com.edrdog.apiservice.selftest.SelfTestRun;

import java.time.Instant;
import java.util.List;

/** 자체시험·무결성 검증 한 번의 결과. items 의 detail 에는 비밀값이 없다. */
public record SelfTestRunResponse(Long id, String trigger, String status, Instant startedAt,
                                  String requestedBy, List<SelfTestItem> items) {

    public static SelfTestRunResponse from(SelfTestRun r) {
        return new SelfTestRunResponse(r.getId(), r.getTriggerType().name(), r.getStatus().name(),
                r.getStartedAt(), r.getRequestedBy(), r.getItems());
    }
}
