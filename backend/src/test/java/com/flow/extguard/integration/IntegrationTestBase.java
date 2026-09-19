package com.flow.extguard.integration;

import com.flow.extguard.config.StorageProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
// Boot 4 relocated MockMvc test support out of spring-boot-test-autoconfigure
// into the per-technology spring-boot-webmvc-test module.
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Shared setup for the HTTP-level tests.
 *
 * <p>State is reset between tests by truncating rather than by wrapping each test
 * in a rolled-back transaction. That keeps every request in its own transaction,
 * exactly as in production, so constraint violations behave the way they really
 * would instead of poisoning the surrounding test transaction.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
abstract class IntegrationTestBase {

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    protected StorageProperties storageProperties;

    protected JdbcTemplate jdbc;

    protected Path storageRoot() {
        return Path.of(storageProperties.getRoot()).toAbsolutePath().normalize();
    }

    @BeforeEach
    void resetDatabase() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DELETE FROM custom_extension");
        jdbc.execute("DELETE FROM policy_audit_log");
        jdbc.execute("DELETE FROM upload_record");
        // Restore the seeded rows to their default state without deleting them --
        // the application account could not recreate them.
        jdbc.execute("UPDATE fixed_extension_state SET blocked = FALSE");
    }

    /**
     * Empties the storage root as well as the tables.
     *
     * <p>The root is a fixed path under the temp directory shared by every test
     * class, so without this files pile up across runs. That is untidy for most
     * tests and wrong for the orphan sweep, which would otherwise find another
     * test's leftovers and count them as orphans.
     */
    @BeforeEach
    void resetStorage() {
        Path root = storageRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
