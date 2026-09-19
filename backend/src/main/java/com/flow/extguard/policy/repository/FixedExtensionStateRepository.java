package com.flow.extguard.policy.repository;

import com.flow.extguard.policy.domain.FixedExtensionState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read and toggle access to the fixed extensions -- nothing else.
 *
 * <p>This extends the bare {@link Repository} marker rather than
 * {@code JpaRepository} on purpose. There is no inherited {@code delete},
 * {@code deleteById} or {@code save} here, so no caller anywhere in the
 * application has a code path that can remove a fixed extension or insert a new
 * one. That guarantee is structural rather than a runtime check that a later
 * refactor could drop.
 */
public interface FixedExtensionStateRepository extends Repository<FixedExtensionState, String> {

    List<FixedExtensionState> findAll();

    Optional<FixedExtensionState> findById(String extension);

    /**
     * @return rows affected; 0 means the row is missing, which the read path
     *         tolerates by falling back to "unblocked".
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE FixedExtensionState f
               SET f.blocked = :blocked, f.updatedAt = :updatedAt
             WHERE f.extension = :extension
            """)
    int updateBlocked(@Param("extension") String extension,
                      @Param("blocked") boolean blocked,
                      @Param("updatedAt") Instant updatedAt);

    @Query("SELECT f.extension FROM FixedExtensionState f WHERE f.blocked = true")
    List<String> findBlockedExtensions();
}
