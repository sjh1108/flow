package com.flow.extguard.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class PolicyApiIntegrationTest extends IntegrationTestBase {

    private ResultActions addCustom(String extension) throws Exception {
        return mockMvc.perform(post("/api/v1/policy/extensions/custom")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"extension\": \"%s\"}".formatted(extension)));
    }

    // --- requirement A: fixed extensions -------------------------------------

    @Test
    @DisplayName("returns all seven fixed extensions, unchecked by default")
    void returnsFixedExtensionsUnblockedByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/policy/extensions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fixed.length()").value(7))
                .andExpect(jsonPath("$.fixed[*].extension").value(
                        org.hamcrest.Matchers.containsInAnyOrder(
                                "bat", "cmd", "com", "cpl", "exe", "scr", "js")))
                .andExpect(jsonPath("$.fixed[*].blocked").value(
                        org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(jsonPath("$.limits.maxCustomExtensions").value(200))
                .andExpect(jsonPath("$.limits.maxExtensionLength").value(20));
    }

    @Test
    @DisplayName("a checkbox change survives a page reload")
    void fixedToggleIsPersisted() throws Exception {
        mockMvc.perform(patch("/api/v1/policy/extensions/fixed/exe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\": true}"))
                .andExpect(status().isOk());

        // A fresh read, as the page would do after F5.
        mockMvc.perform(get("/api/v1/policy/extensions"))
                .andExpect(jsonPath("$.fixed[?(@.extension == 'exe')].blocked").value(true));
    }

    @Test
    @DisplayName("fixed extensions never appear in the custom list")
    void fixedExtensionsAreNotListedAsCustom() throws Exception {
        addCustom("sh").andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/policy/extensions"))
                .andExpect(jsonPath("$.custom.length()").value(1))
                .andExpect(jsonPath("$.custom[0].extension").value("sh"));
    }

    @Test
    void rejectsTogglingAnExtensionThatIsNotFixed() throws Exception {
        mockMvc.perform(patch("/api/v1/policy/extensions/fixed/sh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\": true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FIXED_EXTENSION_UNKNOWN"));
    }

    // --- requirement A: custom extensions ------------------------------------

    @Test
    void addsAndRemovesCustomExtension() throws Exception {
        addCustom("sh")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.custom[0].extension").value("sh"))
                .andExpect(jsonPath("$.limits.customCount").value(1));

        mockMvc.perform(delete("/api/v1/policy/extensions/custom/sh"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom.length()").value(0));
    }

    @Test
    @DisplayName("rejects a duplicate, including one differing only by case or a dot")
    void rejectsDuplicates() throws Exception {
        addCustom("sh").andExpect(status().isCreated());

        addCustom("sh").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXT_DUPLICATE"));
        addCustom("SH").andExpect(status().isConflict());
        addCustom(".sh").andExpect(status().isConflict());
        addCustom("  sh  ").andExpect(status().isConflict());
    }

    @Test
    @DisplayName("points the user at the checkbox when they type a fixed extension")
    void rejectsFixedExtensionAsCustom() throws Exception {
        addCustom("exe")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EXT_IS_FIXED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("고정 확장자")));
    }

    @Test
    void rejectsOverlongExtension() throws Exception {
        addCustom("a".repeat(21))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXT_TOO_LONG"));
    }

    @Test
    void rejectsMalformedExtensions() throws Exception {
        addCustom("ex e").andExpect(status().isBadRequest());
        addCustom("tar.gz").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EXT_CONTAINS_DOT"));
        addCustom("").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("stops at 200 custom extensions with an actionable message")
    void enforcesTheTwoHundredLimit() throws Exception {
        for (int i = 0; i < 200; i++) {
            jdbc.update("INSERT INTO custom_extension (extension, created_at) VALUES (?, ?)",
                    "ext" + i, Instant.now());
        }

        addCustom("onemore")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CUSTOM_LIMIT_EXCEEDED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("200")));
    }

    @Test
    void returns404WhenDeletingSomethingNotRegistered() throws Exception {
        mockMvc.perform(delete("/api/v1/policy/extensions/custom/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EXT_NOT_FOUND"));
    }

    // --- audit trail ---------------------------------------------------------

    @Test
    @DisplayName("records who changed what, with before and after values")
    void writesAnAuditTrail() throws Exception {
        mockMvc.perform(patch("/api/v1/policy/extensions/fixed/exe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blocked\": true}"));
        addCustom("sh");

        mockMvc.perform(get("/api/v1/policy/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].action").value(
                        org.hamcrest.Matchers.containsInAnyOrder("FIXED_BLOCKED", "CUSTOM_ADDED")));
    }

    @Test
    @DisplayName("does not log a no-op toggle")
    void skipsAuditWhenNothingChanged() throws Exception {
        mockMvc.perform(patch("/api/v1/policy/extensions/fixed/exe")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"blocked\": false}"));

        mockMvc.perform(get("/api/v1/policy/audit"))
                .andExpect(jsonPath("$.length()").value(0));
    }
}
