package com.flow.extguard.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One policy change: who, when, what, and the before/after values.
 */
@Entity
@Table(name = "policy_audit_log")
public class PolicyAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", length = 30, nullable = false)
    private AuditAction action;

    @Column(name = "extension", length = 20, nullable = false)
    private String extension;

    @Enumerated(EnumType.STRING)
    @Column(name = "extension_type", length = 10, nullable = false)
    private ExtensionType extensionType;

    @Column(name = "before_value", length = 20)
    private String beforeValue;

    @Column(name = "after_value", length = 20)
    private String afterValue;

    @Column(name = "actor", length = 100, nullable = false)
    private String actor;

    @Column(name = "actor_ip", length = 45)
    private String actorIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PolicyAuditLog() {
    }

    public static PolicyAuditLog record(AuditAction action,
                                        String extension,
                                        ExtensionType extensionType,
                                        String beforeValue,
                                        String afterValue,
                                        String actor,
                                        String actorIp,
                                        String userAgent,
                                        Instant createdAt) {
        PolicyAuditLog log = new PolicyAuditLog();
        log.action = action;
        log.extension = extension;
        log.extensionType = extensionType;
        log.beforeValue = beforeValue;
        log.afterValue = afterValue;
        log.actor = actor;
        log.actorIp = actorIp;
        log.userAgent = truncate(userAgent, 255);
        log.createdAt = createdAt;
        return log;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public AuditAction getAction() {
        return action;
    }

    public String getExtension() {
        return extension;
    }

    public ExtensionType getExtensionType() {
        return extensionType;
    }

    public String getBeforeValue() {
        return beforeValue;
    }

    public String getAfterValue() {
        return afterValue;
    }

    public String getActor() {
        return actor;
    }

    public String getActorIp() {
        return actorIp;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
