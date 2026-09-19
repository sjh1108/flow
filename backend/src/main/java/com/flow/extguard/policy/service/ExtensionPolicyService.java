package com.flow.extguard.policy.service;

import com.flow.extguard.common.ApiErrorCode;
import com.flow.extguard.common.ApiException;
import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.policy.domain.AuditAction;
import com.flow.extguard.policy.domain.CustomExtension;
import com.flow.extguard.policy.domain.ExtensionType;
import com.flow.extguard.policy.domain.FixedExtensionState;
import com.flow.extguard.policy.domain.FixedExtensions;
import com.flow.extguard.policy.domain.PolicyAuditLog;
import com.flow.extguard.policy.repository.CustomExtensionRepository;
import com.flow.extguard.policy.repository.FixedExtensionStateRepository;
import com.flow.extguard.policy.repository.PolicyAuditLogRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and mutates the extension blocking policy.
 */
@Service
public class ExtensionPolicyService {

    private static final Logger log = LoggerFactory.getLogger(ExtensionPolicyService.class);

    private final FixedExtensionStateRepository fixedRepository;
    private final CustomExtensionRepository customRepository;
    private final PolicyAuditLogRepository auditRepository;
    private final ExtensionNormalizer normalizer;
    private final PolicyProperties policyProperties;

    public ExtensionPolicyService(FixedExtensionStateRepository fixedRepository,
                                  CustomExtensionRepository customRepository,
                                  PolicyAuditLogRepository auditRepository,
                                  ExtensionNormalizer normalizer,
                                  PolicyProperties policyProperties) {
        this.fixedRepository = fixedRepository;
        this.customRepository = customRepository;
        this.auditRepository = auditRepository;
        this.normalizer = normalizer;
        this.policyProperties = policyProperties;
    }

    /**
     * The fixed list is built from {@link FixedExtensions#ALL}, with database state
     * joined on. A missing row therefore reads as unblocked instead of vanishing
     * from the response, so the management screen always renders all seven
     * checkboxes even if rows were tampered with directly in the database.
     */
    @Transactional(readOnly = true)
    public PolicySnapshot getPolicy() {
        Map<String, Boolean> stateByExtension = new HashMap<>();
        for (FixedExtensionState state : fixedRepository.findAll()) {
            stateByExtension.put(state.getExtension(), state.isBlocked());
        }

        List<PolicySnapshot.FixedExtensionView> fixed = FixedExtensions.ALL.stream()
                .map(extension -> new PolicySnapshot.FixedExtensionView(
                        extension, stateByExtension.getOrDefault(extension, false)))
                .toList();

        if (stateByExtension.size() != FixedExtensions.ALL.size()) {
            log.warn("fixed_extension_state holds {} rows but {} fixed extensions are defined in code; "
                            + "missing rows are being reported as unblocked. Re-run migrations to restore them.",
                    stateByExtension.size(), FixedExtensions.ALL.size());
        }

        return new PolicySnapshot(fixed, customRepository.findAllByOrderByCreatedAtDesc());
    }

    /** The effective blocklist used by upload validation. */
    @Transactional(readOnly = true)
    public Set<String> blockedExtensions() {
        Set<String> blocked = new HashSet<>(fixedRepository.findBlockedExtensions());
        blocked.addAll(customRepository.findAllExtensions());
        return blocked;
    }

    @Transactional
    public void setFixedBlocked(String rawExtension, boolean blocked, ActorInfo actor) {
        String extension = normalizer.normalize(rawExtension);

        if (!FixedExtensions.contains(extension)) {
            throw new ApiException(ApiErrorCode.FIXED_EXTENSION_UNKNOWN,
                    "고정 확장자 목록: " + FixedExtensions.ALL, extension);
        }

        boolean previous = fixedRepository.findById(extension)
                .map(FixedExtensionState::isBlocked)
                .orElse(false);

        int updated = fixedRepository.updateBlocked(extension, blocked, Instant.now());
        if (updated == 0) {
            // Only reachable if the seeded row was deleted outside the application.
            // The runtime account cannot recreate it by design, so this is surfaced
            // loudly rather than silently accepted.
            log.error("fixed_extension_state row for '{}' is missing; toggle had no effect", extension);
            throw new ApiException(ApiErrorCode.INTERNAL_ERROR,
                    "'" + extension + "' 고정 확장자 행이 존재하지 않습니다. 마이그레이션을 다시 적용해야 합니다.");
        }

        if (previous != blocked) {
            audit(blocked ? AuditAction.FIXED_BLOCKED : AuditAction.FIXED_UNBLOCKED,
                    extension, ExtensionType.FIXED,
                    String.valueOf(previous), String.valueOf(blocked), actor);
        }
    }

    @Transactional
    public CustomExtension addCustom(String rawExtension, ActorInfo actor) {
        String extension = normalizer.normalize(rawExtension);

        // A fixed extension is managed by its checkbox, never as a custom entry.
        // The ck_custom_not_fixed constraint enforces the same rule at the database
        // level; this check exists to produce a message that tells the user where
        // to go instead.
        if (FixedExtensions.contains(extension)) {
            throw new ApiException(ApiErrorCode.EXT_IS_FIXED,
                    "'" + extension + "'는 고정 확장자 목록에 포함되어 있습니다.", extension);
        }

        if (customRepository.count() >= policyProperties.getMaxCustomExtensions()) {
            throw new ApiException(ApiErrorCode.CUSTOM_LIMIT_EXCEEDED,
                    "현재 등록 수: " + customRepository.count(),
                    policyProperties.getMaxCustomExtensions());
        }

        // Pre-check for the friendly message; the UNIQUE index below is what
        // actually guarantees uniqueness under concurrent requests.
        if (customRepository.findByExtension(extension).isPresent()) {
            throw new ApiException(ApiErrorCode.EXT_DUPLICATE, "이미 등록된 확장자입니다.", extension);
        }

        CustomExtension saved;
        try {
            saved = customRepository.save(CustomExtension.of(extension, Instant.now()));
        } catch (DataIntegrityViolationException e) {
            // Lost the race against a concurrent add, or hit ck_custom_not_fixed.
            throw new ApiException(ApiErrorCode.EXT_DUPLICATE,
                    "동시 요청으로 이미 등록되었습니다.", extension);
        }

        audit(AuditAction.CUSTOM_ADDED, extension, ExtensionType.CUSTOM, null, extension, actor);
        return saved;
    }

    /**
     * Deletes a custom extension.
     *
     * <p>Scoped to {@code custom_extension} only. Passing a fixed extension such as
     * {@code exe} finds no row and yields 404 -- there is no code path here that
     * could reach the fixed table.
     */
    @Transactional
    public void removeCustom(String rawExtension, ActorInfo actor) {
        String extension = normalizer.normalize(rawExtension);

        CustomExtension existing = customRepository.findByExtension(extension)
                .orElseThrow(() -> new ApiException(ApiErrorCode.EXT_NOT_FOUND,
                        FixedExtensions.contains(extension)
                                ? "'" + extension + "'는 고정 확장자이므로 삭제할 수 없습니다. 체크 해제로 차단을 해제하세요."
                                : "등록되어 있지 않습니다.",
                        extension));

        customRepository.delete(existing);
        audit(AuditAction.CUSTOM_REMOVED, extension, ExtensionType.CUSTOM, extension, null, actor);
    }

    @Transactional(readOnly = true)
    public List<PolicyAuditLog> recentAuditLog(int limit) {
        return auditRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, Math.clamp(limit, 1, 500)));
    }

    private void audit(AuditAction action,
                       String extension,
                       ExtensionType type,
                       String before,
                       String after,
                       ActorInfo actor) {
        auditRepository.save(PolicyAuditLog.record(action, extension, type, before, after,
                actor.name(), actor.ip(), actor.userAgent(), Instant.now()));
        log.info("policy change: {} {} by {} from {}", action, extension, actor.name(), actor.ip());
    }
}
