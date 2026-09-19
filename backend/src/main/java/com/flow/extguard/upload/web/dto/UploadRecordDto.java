package com.flow.extguard.upload.web.dto;

import com.flow.extguard.upload.domain.UploadRecord;
import java.time.Instant;

/**
 * One line of the upload history.
 *
 * <p>{@code purgedAt} is here because without it an ACCEPTED row is ambiguous:
 * the status says the upload succeeded, which stays true forever, while the file
 * itself is deleted once it passes the retention period. A reader seeing only
 * ACCEPTED would reasonably assume the bytes are still there. Null means the file
 * is on disk; a timestamp means the record outlived it.
 */
public record UploadRecordDto(Long id,
                              String originalFilename,
                              String extensionChain,
                              long sizeBytes,
                              String status,
                              String rejectionCode,
                              String rejectionDetail,
                              String detectedSignature,
                              String sha256,
                              Instant createdAt,
                              Instant purgedAt) {

    public static UploadRecordDto from(UploadRecord entity) {
        return new UploadRecordDto(
                entity.getId(),
                entity.getDisplayFilename(),
                entity.getExtensionChain(),
                entity.getSizeBytes(),
                entity.getStatus().name(),
                entity.getRejectionCode(),
                entity.getRejectionDetail(),
                entity.getDetectedSignature(),
                entity.getSha256(),
                entity.getCreatedAt(),
                entity.getPurgedAt());
    }
}
