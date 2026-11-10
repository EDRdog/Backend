package com.edrdog.apiservice.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * 감사기록 한 행(MySQL). 넣기만 하고 고치거나 지우지 않는다: setter 가 없고, {@code @Immutable} 이라
 * 하이버네이트가 UPDATE 를 만들지 않으며, 저장소에도 수정·삭제 메서드가 없다(AuditRepository).
 */
@Entity
@Immutable
@Table(name = "audit_log", indexes = @Index(name = "idx_audit_tenant_ts", columnList = "tenantId, ts"))
public class AuditRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant ts;

    @Column
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditActor actorType;

    @Column
    private Long actorUserId;

    @Column(length = AuditService.MAX_ACTOR)
    private String actor;

    @Column(nullable = false, length = AuditService.MAX_TYPE)
    private String eventType;

    @Column(length = AuditService.MAX_TARGET)
    private String target;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AuditResult result;

    @Column(length = AuditService.MAX_DETAIL)
    private String detail;

    @Column(length = AuditService.MAX_IP)
    private String ip;

    protected AuditRecord() {
    }

    AuditRecord(Instant ts, AuditEntry e) {
        this.ts = ts;
        this.tenantId = e.tenantId();
        this.actorType = e.actorType();
        this.actorUserId = e.actorUserId();
        this.actor = e.actor();
        this.eventType = e.type();
        this.target = e.target();
        this.result = e.result();
        this.detail = e.detail();
        this.ip = e.ip();
    }

    public Long getId() {
        return id;
    }

    public Instant getTs() {
        return ts;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public AuditActor getActorType() {
        return actorType;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActor() {
        return actor;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTarget() {
        return target;
    }

    public AuditResult getResult() {
        return result;
    }

    public String getDetail() {
        return detail;
    }

    public String getIp() {
        return ip;
    }
}
