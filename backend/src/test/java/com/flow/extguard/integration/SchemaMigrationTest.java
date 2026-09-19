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

    /**
     * V3 adds the retention marker. It has to be nullable: every row starts life
     * with its file on disk, and a NOT NULL column would need a sentinel value
     * standing in for "not purged".
     */
    @Test
    void uploadRecordCarriesANullableRetentionMarker() {
        jdbc().update("INSERT INTO upload_record "
                + "(original_filename, display_filename, size_bytes, status, created_at) "
                + "VALUES ('a.txt', 'a.txt', 1, 'ACCEPTED', CURRENT_TIMESTAMP(6))");

        Integer unpurged = jdbc().queryForObject(
                "SELECT COUNT(*) FROM upload_record WHERE purged_at IS NULL", Integer.class);

        assertThat(unpurged).isEqualTo(1);
        jdbc().update("DELETE FROM upload_record");
    }

    /**
     * V4 replaces V3's index so one index serves both scheduled reads: the quota
     * sum (answered from the index alone) and the retention cursor (which needs
     * created_at to order by, and would otherwise read purged rows only to
     * discard them).
     *
     * <p>Column order is the whole point, so the order is what is asserted --
     * that the index merely exists would pass with the columns any way round.
     *
     * <p>This runs against H2. It proves the migration produces the index it
     * claims to; whether MySQL's optimiser then picks it is a question for
     * {@code EXPLAIN} on the real database, not for this test.
     */
    @Test
    void liveUsageIndexAlsoOrdersThePurgeCursor() {
        var columns = jdbc().queryForList(
                "SELECT column_name FROM information_schema.index_columns "
                        + "WHERE index_name = 'IDX_UPLOAD_RECORD_LIVE' "
                        + "ORDER BY ordinal_position", String.class);

        assertThat(columns)
                .as("id must sit right after created_at: InnoDB appends the primary key "
                        + "after every declared column, so leaving it implicit would order "
                        + "ties by size_bytes instead of by id")
                .containsExactly("STATUS", "PURGED_AT", "CREATED_AT", "ID", "SIZE_BYTES");
    }
}
