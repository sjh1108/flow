package com.flow.extguard.common;

/**
 * An expected, reportable failure carrying a user-facing message and the
 * evidence behind it.
 */
public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final String detail;

    public ApiException(ApiErrorCode code, String detail, Object... messageArgs) {
        super(code.message(messageArgs));
        this.code = code;
        this.detail = detail;
    }

    public ApiErrorCode code() {
        return code;
    }

    /** Specific evidence, e.g. which extension segment matched the blocklist. */
    public String detail() {
        return detail;
    }
}
