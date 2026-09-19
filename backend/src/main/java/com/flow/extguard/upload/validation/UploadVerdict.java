package com.flow.extguard.upload.validation;

import com.flow.extguard.common.ApiErrorCode;

/**
 * The outcome of validating one file.
 *
 * <p>A returned verdict rather than a thrown exception, because a rejection still
 * needs to be recorded with all the context that produced it, and a multi-file
 * request needs a per-file answer rather than one failure for the batch.
 *
 * @param analysis  may be null when the filename itself could not be parsed
 * @param signature may be null when no magic number matched
 */
public record UploadVerdict(boolean accepted,
                            ApiErrorCode code,
                            String message,
                            String detail,
                            FilenameAnalysis analysis,
                            FileSignature signature) {

    public static UploadVerdict accept(FilenameAnalysis analysis, FileSignature signature) {
        return new UploadVerdict(true, null, null, null, analysis, signature);
    }

    public static UploadVerdict reject(ApiErrorCode code,
                                       String message,
                                       String detail,
                                       FilenameAnalysis analysis,
                                       FileSignature signature) {
        return new UploadVerdict(false, code, message, detail, analysis, signature);
    }

    public boolean rejected() {
        return !accepted;
    }
}
