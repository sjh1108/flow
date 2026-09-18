package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flow.extguard.policy.domain.FixedExtensions;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Proves the production Flyway migrations apply cleanly and that the CHECK
 * constraints guarding the fixed extensions are actually enforced by the database
 * rather than only by application code.
 */
@SpringBootTest
@ActiveProfiles("test")
class SchemaMigrationTest {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    @Test
    void seedsExactlySevenFixedExtensionsAllUnblocked() {
        Integer total = jdbc().queryForObject(
                "SELECT COUNT(*) FROM fixed_extension_state", Integer.class);
        Integer blocked = jdbc().queryForObject(
                "SELECT COUNT(*) FROM fixed_extension_state WHERE blocked = TRUE", Integer.class);

        assertThat(total).isEqualTo(7);
        assertThat(blocked).as("requirement: fixed extensions default to unchecked").isZero();
    }

    @Test
    void seededRowsMatchTheJavaConstant() {
        var fromDb = jdbc().queryForList(
                "SELECT extension FROM fixed_extension_state", String.class);

        assertThat(fromDb).containsExactlyInAnyOrderElementsOf(FixedExtensions.ALL);
    }

    /**
     * The point of ck_custom_not_fixed: someone with direct database access still
     * cannot register a fixed extension as a custom one.
     */
    @Test
    void databaseRejectsAFixedExtensionInsertedDirectlyAsCustom() {
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO custom_extension (extension, created_at) VALUES ('exe', CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(Exception.class);
    }

    /**
     * The point of ck_fixed_whitelist: the fixed table cannot grow a new member.
     */
    @Test
    void databaseRejectsAnUnknownExtensionInsertedIntoTheFixedTable() {
        assertThatThrownBy(() -> jdbc().update(
                "INSERT INTO fixed_extension_state (extension, blocked, updated_at) "
                        + "VALUES ('sh', FALSE, CURRENT_TIMESTAMP(6))"))
                .isInstanceOf(Exception.class);
    }
}
