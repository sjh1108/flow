package com.flow.extguard.policy.service;

import com.flow.extguard.policy.domain.CustomExtension;
import java.util.List;

/**
 * The complete policy as the management screen needs it.
 *
 * @param fixed always the seven fixed extensions, in their canonical order
 */
public record PolicySnapshot(List<FixedExtensionView> fixed, List<CustomExtension> custom) {

    public record FixedExtensionView(String extension, boolean blocked) {
    }
}
