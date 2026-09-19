package com.flow.extguard.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A user-registered blocked extension.
 *
 * <p>Presence in this table means blocked; there is no toggle. Removing the block
 * means deleting the row, which is what the X button in the UI does.
 */
@Entity
@Table(name = "custom_extension")
public class CustomExtension {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "extension", length = 20, nullable = false, updatable = false)
    private String extension;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected CustomExtension() {
    }

    private CustomExtension(String extension, Instant createdAt) {
        this.extension = extension;
        this.createdAt = createdAt;
    }

    /** @param normalizedExtension a value already through {@code ExtensionNormalizer} */
    public static CustomExtension of(String normalizedExtension, Instant createdAt) {
        return new CustomExtension(normalizedExtension, createdAt);
    }

    public Long getId() {
        return id;
    }

    public String getExtension() {
        return extension;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
