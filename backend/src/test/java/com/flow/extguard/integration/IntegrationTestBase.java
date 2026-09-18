package com.flow.extguard.integration;

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

    protected JdbcTemplate jdbc;

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
}
