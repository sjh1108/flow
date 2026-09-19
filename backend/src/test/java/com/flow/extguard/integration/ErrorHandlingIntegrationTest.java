package com.flow.extguard.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Client mistakes must be reported as client errors.
 *
 * <p>These exist because the catch-all handler originally swallowed Spring MVC's
 * own exceptions and answered 500 to every wrong method or unknown path. That
 * both misleads the caller and hides real server faults in the logs.
 */
class ErrorHandlingIntegrationTest extends IntegrationTestBase {

    @Test
    @DisplayName("an unsupported method is 405, not 500")
    void unsupportedMethodIsMethodNotAllowed() throws Exception {
        mockMvc.perform(delete("/api/v1/policy/extensions/fixed/exe"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("an unknown path is 404, not 500")
    void unknownPathIsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a missing file part is 400 with an actionable message")
    void missingFilePartIsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/v1/files"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("NO_FILE_SUBMITTED"));
    }

    @Test
    void malformedJsonBodyIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void missingRequiredFieldIsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("every error uses the same body shape")
    void errorBodyShapeIsConsistent() throws Exception {
        mockMvc.perform(delete("/api/v1/policy/extensions/custom/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }
}
