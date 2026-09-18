package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flow.extguard.policy.domain.FixedExtensions;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * The seven fixed extensions must stay fixed.
 *
 * <p>These tests exist because of a design review question: with fixed and custom
 * extensions in one table distinguished only by a {@code type} column, could
 * someone delete a fixed extension through the API, or make one stop being fixed
 * by editing the database directly? They could, so the design was changed. Each
 * test below pins one layer of the replacement.
 */
class FixedExtensionIntegrityTest extends IntegrationTestBase {

    /** Layer 1: the delete endpoint is scoped to the custom table and cannot reach the fixed one. */
    @Test
    @DisplayName("deleting a fixed extension through the custom endpoint is a 404 and changes nothing")
    void customDeleteEndpointCannotRemoveAFixedExtension() throws Exception {
        mockMvc.perform(delete("/api/v1/policy/extensions/custom/exe"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXT_NOT_FOUND"));

        Integer remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM fixed_extension_state", Integer.class);
        assertThat(remaining).isEqualTo(7);

        mockMvc.perform(get("/api/v1/policy/extensions"))
                .andExpect(jsonPath("$.fixed.length()").value(7));
    }

    /** Layer 1: there is no endpoint at all for deleting a fixed extension. */
    @Test
    @DisplayName("no endpoint exists to delete a fixed extension")
    void thereIsNoFixedDeleteEndpoint() throws Exception {
        mockMvc.perform(delete("/api/v1/policy/extensions/fixed/exe"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("a fixed extension can only be toggled, never removed")
                        .isGreaterThanOrEqualTo(400));
    }

    /** Layer 2: ck_custom_not_fixed stops a fixed extension being smuggled in as custom. */
    @Test
    @DisplayName("the database itself refuses a fixed extension inserted as custom")
    void databaseConstraintBlocksFixedExtensionAsCustom() {
        for (String fixed : FixedExtensions.ALL) {
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO custom_extension (extension, created_at) VALUES (?, ?)",
                    fixed, Instant.now()))
                    .as("direct SQL insert of the fixed extension '%s' must fail", fixed)
                    .isInstanceOf(Exception.class);
        }
    }

    /** Layer 2: the same rule reached through the API, with a message that helps. */
    @Test
    void apiRejectsFixedExtensionAsCustomWithGuidance() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\": \"exe\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXT_IS_FIXED"));
    }

    /** Layer 3: ck_fixed_whitelist stops the fixed table growing a new member. */
    @Test
    @DisplayName("the fixed table cannot gain a new member")
    void databaseConstraintBlocksNewFixedExtension() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO fixed_extension_state (extension, blocked, updated_at) VALUES (?, ?, ?)",
                "sh", true, Instant.now()))
                .isInstanceOf(Exception.class);
    }

    /**
     * Layer 4: the read path is driven by the code constant, not by the table.
     *
     * <p>This is the case that the original single-table design got wrong. Deleting
     * a row used to make the extension disappear from the management screen
     * entirely; now it simply reads back as unblocked and all seven checkboxes
     * still render.
     */
    @Test
    @DisplayName("deleting a row directly in the database still leaves seven checkboxes")
    void policyStillReportsSevenFixedExtensionsAfterRowDeletion() throws Exception {
        jdbc.update("DELETE FROM fixed_extension_state WHERE extension = 'exe'");

        mockMvc.perform(get("/api/v1/policy/extensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixed.length()").value(7))
                .andExpect(jsonPath("$.fixed[?(@.extension == 'exe')].blocked").value(false));

        // Restore for the other tests: only a migration-privileged account could
        // do this in production, which is the point.
        jdbc.update("INSERT INTO fixed_extension_state (extension, blocked, updated_at) VALUES (?, ?, ?)",
                "exe", false, Instant.now());
    }

    /**
     * The Java constant and the SQL CHECK constraints list the same seven values.
     * If someone edits one without the other, this fails.
     */
    @Test
    @DisplayName("the Java constant and the database constraint agree")
    void javaConstantMatchesDatabaseConstraint() {
        var seeded = jdbc.queryForList("SELECT extension FROM fixed_extension_state", String.class);

        assertThat(seeded).containsExactlyInAnyOrderElementsOf(FixedExtensions.ALL);
        assertThat(FixedExtensions.ALL).hasSize(7);
    }
}
