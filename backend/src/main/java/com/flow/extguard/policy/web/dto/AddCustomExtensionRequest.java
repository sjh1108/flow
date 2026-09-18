package com.flow.extguard.policy.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * @param extension raw user input; normalisation and the real validation happen
 *                  in {@code ExtensionNormalizer}. The generous bound here only
 *                  stops absurd payloads before they reach it.
 */
public record AddCustomExtensionRequest(
        @NotNull @Size(max = 100) String extension) {
}
