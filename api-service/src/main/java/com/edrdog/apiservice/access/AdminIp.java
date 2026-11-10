package com.edrdog.apiservice.access;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * 조직(tenant)이 관리 접속을 허용하는 단말 IP 하나. 조직에 한 건도 없으면 제한이 없다(ADR 033).
 * IP 는 {@link AdminIpAddress#normalize} 로 맞춘 문자열이라 문자열 비교가 곧 주소 비교다.
 */
@Entity
@Table(name = "admin_ips", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "ip"}))
public class AdminIp {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false, length = 45)
    private String ip;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;   // 등록한 admin 의 이메일

    protected AdminIp() {
    }

    public AdminIp(Long tenantId, String ip, Instant createdAt, String createdBy) {
        this.tenantId = tenantId;
        this.ip = ip;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getTenantId() {
        return tenantId;
    }

    public String getIp() {
        return ip;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
