package com.edrdog.apiservice.selftest.web;

import com.edrdog.apiservice.selftest.SelfTestItem;
import com.edrdog.apiservice.selftest.SelfTestRun;

import java.time.Instant;
import java.util.List;

/** 자체시험·무결성 검증 한 번의 결과. items 의 detail 에는 비밀값이 없다. */
public record SelfTestRunResponse(Long id, String trigger, String status, Instant startedAt,
                                  String requestedBy, List<SelfTestItem> items) {

    /** 요청자가 다른 조직(또는 조직을 기록하기 전 행)이면 requestedBy 에 이메일 대신 이 문구가 나간다. */
    static final String OTHER_ORGANIZATION = "다른 조직 관리자";

    /** viewerTenantId 는 조회하는 사람의 조직이다. 같은 조직이 요청한 실행만 이메일이 보인다. */
    public static SelfTestRunResponse from(SelfTestRun r, Long viewerTenantId) {
        String requester = r.getRequestedBy();
        if (requester != null && !viewerTenantId.equals(r.getRequestedByTenantId())) {
            requester = OTHER_ORGANIZATION;
        }
        return new SelfTestRunResponse(r.getId(), r.getTriggerType().name(), r.getStatus().name(),
                r.getStartedAt(), requester, r.getItems());
    }
}
