package com.flow.extguard.upload.web.dto;

/**
 * The verdict for one submitted file.
 *
 * @param status  {@code ACCEPTED} or {@code REJECTED}
 * @param code    the rejection code; null when accepted
 * @param message Korean text to show the user; null when accepted
 * @param detail  the evidence behind a rejection, e.g. which chain segment matched
 */
public record UploadResultDto(String filename,
                              String status,
                              String code,
                              String message,
                              String detail,
                              Long recordId,
                              long sizeBytes,
                              String sha256,
                              String detectedSignature) {
}
