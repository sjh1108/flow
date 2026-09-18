package com.flow.extguard.upload.validation;

/**
 * Everything the validator needs about one submitted file, without holding the
 * file itself in memory.
 *
 * @param filename            as sent by the client, untrusted
 * @param sizeBytes           reported size
 * @param declaredContentType the part's Content-Type, untrusted
 * @param header              first {@link ContentSignatureDetector#HEADER_BYTES} bytes
 */
public record UploadCandidate(String filename,
                              long sizeBytes,
                              String declaredContentType,
                              byte[] header) {
}
