package com.flow.extguard.upload.web.dto;

import com.flow.extguard.upload.domain.UploadRecord;
import java.time.Instant;

/**
 * One line of the upload history.
 *
 * <p>{@code purgedAt} is here because without it an ACCEPTED row is ambiguous:
 * the status says the upload succeeded, which stays true forever, while the file
 * itself is deleted once it passes the retention period. A reader seeing only
 * ACCEPTED would reasonably assume the bytes are still there.
 *
 * <p>It reports what was recorded, not what is on disk: a timestamp means
 * maintenance deleted the file and wrote that down, and null means no purge has
 * been recorded yet. The two are not quite complements. Maintenance deletes the
 * file before marking the row, in a separate transaction, so a failure in
 * between leaves {@code purgedAt} null with the file already gone until the next
 * round marks it. Null therefore bounds nothing about the disk; it says the
 * record has not been told the file is gone.
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
