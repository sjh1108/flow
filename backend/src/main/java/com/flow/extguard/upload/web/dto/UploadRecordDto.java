package com.flow.extguard.upload.web.dto;

import com.flow.extguard.upload.domain.UploadRecord;
import java.time.Instant;

public record UploadRecordDto(Long id,
                              String originalFilename,
                              String extensionChain,
                              long sizeBytes,
                              String status,
                              String rejectionCode,
                              String rejectionDetail,
                              String detectedSignature,
                              String sha256,
                              Instant createdAt) {

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
                entity.getCreatedAt());
    }
}
