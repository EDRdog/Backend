package com.edrdog.apiservice.selftest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

/**
 * 자체시험·무결성 검증 한 번의 결과. 항목들은 JSON 컬럼 하나에 담는다(항목만 따로 조회할 일이 없다).
 * status 는 FAIL 항목이 하나라도 있으면 FAIL, 아니면 PASS(SKIP 은 실패로 세지 않는다).
 */
@Entity
@Table(name = "selftest_runs")
public class SelfTestRun {

    public enum Trigger { STARTUP, SCHEDULED, MANUAL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Trigger triggerType;   // "trigger" 는 MySQL 예약어라 컬럼명이 될 수 없다

    @Column(nullable = false)
    private Instant startedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SelfTestItem.Status status;

    @Column
    private String requestedBy;   // MANUAL 일 때 요청한 관리자 이메일

    @Column
    private Long requestedByTenantId;   // 요청자의 조직. 다른 조직에는 이메일을 가린다. 이 칸이 생기기 전 행은 null

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private List<SelfTestItem> items;

    protected SelfTestRun() {
    }

    public static SelfTestRun of(Trigger trigger, Instant startedAt, String requestedBy, Long requestedByTenantId,
                                 List<SelfTestItem> items) {
        SelfTestRun run = new SelfTestRun();
        run.triggerType = trigger;
        run.startedAt = startedAt;
        run.requestedBy = requestedBy;
        run.requestedByTenantId = requestedByTenantId;
        run.items = items;
        run.status = failedNames(items).isEmpty() ? SelfTestItem.Status.PASS : SelfTestItem.Status.FAIL;
        return run;
    }

    /** 실패한 항목 이름. 직전 실행과 비교해 새로 실패한 것만 통보할 때도 쓴다. */
    public static List<String> failedNames(List<SelfTestItem> items) {
        return items.stream().filter(i -> i.status() == SelfTestItem.Status.FAIL).map(SelfTestItem::name).toList();
    }

    public Long getId() {
        return id;
    }

    public Trigger getTriggerType() {
        return triggerType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public SelfTestItem.Status getStatus() {
        return status;
    }

    public String getRequestedBy() {
        return requestedBy;
    }

    public Long getRequestedByTenantId() {
        return requestedByTenantId;
    }

    public List<SelfTestItem> getItems() {
        return items;
    }
}
