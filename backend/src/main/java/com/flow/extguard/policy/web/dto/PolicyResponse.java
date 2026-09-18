package com.flow.extguard.policy.web.dto;

import com.flow.extguard.config.PolicyProperties;
import com.flow.extguard.policy.service.PolicySnapshot;
import java.time.Instant;
import java.util.List;

/**
 * Everything the management screen needs in one response, limits included, so the
 * client never has to hardcode 200 or 20.
 */
public record PolicyResponse(List<FixedExtensionDto> fixed,
                             List<CustomExtensionDto> custom,
                             LimitsDto limits) {

    public record FixedExtensionDto(String extension, boolean blocked) {
    }

    public record CustomExtensionDto(Long id, String extension, Instant createdAt) {
    }

    public record LimitsDto(int maxCustomExtensions,
                            int maxExtensionLength,
                            int customCount) {
    }

    public static PolicyResponse from(PolicySnapshot snapshot, PolicyProperties properties) {
        List<FixedExtensionDto> fixed = snapshot.fixed().stream()
                .map(view -> new FixedExtensionDto(view.extension(), view.blocked()))
                .toList();

        List<CustomExtensionDto> custom = snapshot.custom().stream()
                .map(entity -> new CustomExtensionDto(
                        entity.getId(), entity.getExtension(), entity.getCreatedAt()))
                .toList();

        return new PolicyResponse(fixed, custom, new LimitsDto(
                properties.getMaxCustomExtensions(),
                properties.getMaxExtensionLength(),
                custom.size()));
    }
}
