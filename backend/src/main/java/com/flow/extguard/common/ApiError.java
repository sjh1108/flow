package com.flow.extguard.common;

import java.time.Instant;

/**
 * The single error body shape used by every endpoint.
 *
 * @param code    stable machine-readable identifier
 * @param message Korean text safe to render directly in the UI
 * @param detail  the evidence behind the decision; may be null
 */
public record ApiError(String code, String message, String detail, Instant timestamp) {

    public static ApiError of(ApiErrorCode code, String message, String detail) {
        return new ApiError(code.name(), message, detail, Instant.now());
    }

    public static ApiError of(ApiException exception) {
        return new ApiError(exception.code().name(), exception.getMessage(), exception.detail(), Instant.now());
    }
}
