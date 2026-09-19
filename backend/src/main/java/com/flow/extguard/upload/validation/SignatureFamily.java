package com.flow.extguard.upload.validation;

public enum SignatureFamily {
    /** Native executables and bytecode. Never accepted, whatever the extension says. */
    EXECUTABLE,
    /** Interpreted scripts identified by content. Never accepted. */
    SCRIPT,
    ARCHIVE,
    DOCUMENT,
    IMAGE
}
