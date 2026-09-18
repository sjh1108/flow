package com.flow.extguard.upload.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One upload attempt. Rejections are recorded too -- a blocked upload is exactly
 * the event worth having in the log.
 */
@Entity
@Table(name = "upload_record")
public class UploadRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** UUID-based name on disk. Null for rejected uploads, which are never written. */
    @Column(name = "stored_name", length = 64)
    private String storedName;

    @Column(name = "original_filename", length = 255, nullable = false)
    private String originalFilename;

    @Column(name = "display_filename", length = 255, nullable = false)
    private String displayFilename;

    /** Comma-joined extension chain, e.g. "pdf,exe". */
    @Column(name = "extension_chain", length = 255)
    private String extensionChain;

    @Column(name = "effective_extension", length = 20)
    private String effectiveExtension;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "sha256", length = 64)
    private String sha256;

    @Column(name = "declared_content_type", length = 120)
    private String declaredContentType;

    @Column(name = "detected_signature", length = 40)
    private String detectedSignature;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 12, nullable = false)
    private UploadStatus status;

    @Column(name = "rejection_code", length = 40)
    private String rejectionCode;

    @Column(name = "rejection_detail", length = 500)
    private String rejectionDetail;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected UploadRecord() {
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public Long getId() {
        return id;
    }

    public String getStoredName() {
        return storedName;
    }

    public String getOriginalFilename() {
        return originalFilename;
    }

    public String getDisplayFilename() {
        return displayFilename;
    }

    public String getExtensionChain() {
        return extensionChain;
    }

    public String getEffectiveExtension() {
        return effectiveExtension;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getDeclaredContentType() {
        return declaredContentType;
    }

    public String getDetectedSignature() {
        return detectedSignature;
    }

    public UploadStatus getStatus() {
        return status;
    }

    public String getRejectionCode() {
        return rejectionCode;
    }

    public String getRejectionDetail() {
        return rejectionDetail;
    }

    public String getClientIp() {
        return clientIp;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public static final class Builder {

        private final UploadRecord record = new UploadRecord();

        public Builder storedName(String storedName) {
            record.storedName = storedName;
            return this;
        }

        public Builder originalFilename(String originalFilename) {
            record.originalFilename = truncate(originalFilename, 255);
            return this;
        }

        public Builder displayFilename(String displayFilename) {
            record.displayFilename = truncate(displayFilename, 255);
            return this;
        }

        public Builder extensionChain(String extensionChain) {
            record.extensionChain = truncate(extensionChain, 255);
            return this;
        }

        public Builder effectiveExtension(String effectiveExtension) {
            record.effectiveExtension = truncate(effectiveExtension, 20);
            return this;
        }

        public Builder sizeBytes(long sizeBytes) {
            record.sizeBytes = sizeBytes;
            return this;
        }

        public Builder sha256(String sha256) {
            record.sha256 = sha256;
            return this;
        }

        public Builder declaredContentType(String declaredContentType) {
            record.declaredContentType = truncate(declaredContentType, 120);
            return this;
        }

        public Builder detectedSignature(String detectedSignature) {
            record.detectedSignature = truncate(detectedSignature, 40);
            return this;
        }

        public Builder status(UploadStatus status) {
            record.status = status;
            return this;
        }

        public Builder rejectionCode(String rejectionCode) {
            record.rejectionCode = truncate(rejectionCode, 40);
            return this;
        }

        public Builder rejectionDetail(String rejectionDetail) {
            record.rejectionDetail = truncate(rejectionDetail, 500);
            return this;
        }

        public Builder clientIp(String clientIp) {
            record.clientIp = truncate(clientIp, 45);
            return this;
        }

        public Builder createdAt(Instant createdAt) {
            record.createdAt = createdAt;
            return this;
        }

        public UploadRecord build() {
            return record;
        }
    }
}
