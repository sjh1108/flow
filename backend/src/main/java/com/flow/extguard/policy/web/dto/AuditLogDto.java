package com.flow.extguard.policy.web.dto;

import com.flow.extguard.policy.domain.PolicyAuditLog;
import java.time.Instant;

public record AuditLogDto(Long id,
                          String action,
                          String extension,
                          String extensionType,
                          String beforeValue,
                          String afterValue,
                          String actor,
                          String actorIp,
                          Instant createdAt) {

    public static AuditLogDto from(PolicyAuditLog entity) {
        return new AuditLogDto(
                entity.getId(),
                entity.getAction().name(),
                entity.getExtension(),
                entity.getExtensionType().name(),
                entity.getBeforeValue(),
                entity.getAfterValue(),
                entity.getActor(),
                entity.getActorIp(),
                entity.getCreatedAt());
    }
}
