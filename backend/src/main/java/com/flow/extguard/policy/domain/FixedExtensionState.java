package com.flow.extguard.policy.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Blocked/unblocked state of one fixed extension.
 *
 * <p>Rows are created by migration only. The application updates {@code blocked}
 * and nothing else.
 */
@Entity
@Table(name = "fixed_extension_state")
public class FixedExtensionState {

    @Id
    @Column(name = "extension", length = 20, nullable = false, updatable = false)
    private String extension;

    @Column(name = "blocked", nullable = false)
    private boolean blocked;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected FixedExtensionState() {
    }

    public String getExtension() {
        return extension;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
