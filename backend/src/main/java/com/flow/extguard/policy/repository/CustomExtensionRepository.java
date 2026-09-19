package com.flow.extguard.policy.repository;

import com.flow.extguard.policy.domain.CustomExtension;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Insert, delete and read access to custom extensions.
 *
 * <p>Scoped to {@code custom_extension} only: the delete path exposed to the API
 * physically cannot reach {@code fixed_extension_state}.
 */
public interface CustomExtensionRepository extends Repository<CustomExtension, Long> {

    CustomExtension save(CustomExtension customExtension);

    Optional<CustomExtension> findByExtension(String extension);

    List<CustomExtension> findAllByOrderByCreatedAtDesc();

    long count();

    void delete(CustomExtension customExtension);

    @Query("SELECT c.extension FROM CustomExtension c")
    List<String> findAllExtensions();
}
