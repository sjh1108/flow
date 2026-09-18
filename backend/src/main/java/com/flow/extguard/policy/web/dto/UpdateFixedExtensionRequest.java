package com.flow.extguard.policy.web.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateFixedExtensionRequest(@NotNull Boolean blocked) {
}
