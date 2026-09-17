package com.edrdog.responderservice.command;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 에이전트에 내려보낼 명령 한 건의 저장 행.
 *
 * <p>status 는 PENDING(대기) → DELIVERED(하트비트로 가져감) → KILLED | NO_MATCH | FAILED(보고됨) 순서로만 바뀐다.
 * TIMEOUT 은 저장하지 않고 조회 시각으로 판단한다.
 */
@Entity
@Table(name = "agent_command", indexes = @Index(name = "idx_agent_command_host_status", columnList = "host, status"))
public class AgentCommand {

    /** 보고 메시지 컬럼 길이. 에이전트 메시지는 길이 제한이 없어 저장 전에 자른다. */
    public static final int MESSAGE_MAX = 1024;

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false)
    private String host;

    @Column(nullable = false, length = 32)
    private String type;

    @Column(nullable = false, length = 1024)
    private String target;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(length = MESSAGE_MAX)
    private String message;

    @Column(nullable = false)
    private Instant createdAt;

    protected AgentCommand() {
    }

    public AgentCommand(String id, String host, String type, String target, String status, Instant createdAt) {
        this.id = id;
        this.host = host;
        this.type = type;
        this.target = target;
        this.status = status;
        this.createdAt = createdAt;
    }

    /** 에이전트 프로토콜로 내려보낼 모양. */
    public Command toCommand() {
        return new Command(id, host, type, target, createdAt);
    }

    public String getId() {
        return id;
    }

    public String getHost() {
        return host;
    }

    public String getTarget() {
        return target;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
