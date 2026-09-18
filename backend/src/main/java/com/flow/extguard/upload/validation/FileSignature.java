package com.flow.extguard.upload.validation;

/**
 * A magic-number match.
 *
 * @param id     short identifier recorded in the upload log, e.g. {@code PE_EXE}
 * @param family how the validator should treat it
 */
public record FileSignature(String id, SignatureFamily family) {

    public boolean isExecutableOrScript() {
        return family == SignatureFamily.EXECUTABLE || family == SignatureFamily.SCRIPT;
    }
}
