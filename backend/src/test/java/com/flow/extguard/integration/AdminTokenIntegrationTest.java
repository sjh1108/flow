package com.flow.extguard.integration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * With a token configured, policy writes require it while reads and uploads stay
 * open.
 */
@TestPropertySource(properties = "extguard.admin.token=test-secret-token")
class AdminTokenIntegrationTest extends IntegrationTestBase {

    private static final String HEADER = "X-Admin-Token";

    @Test
    void rejectsPolicyWriteWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\": \"sh\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    /**
     * The 401 is written from the filter chain, before the servlet runs. CORS used to
     * be applied inside the servlet, so that response went out with no
     * Access-Control-Allow-Origin: a browser discarded it and the page reported a
     * network failure instead of a permission problem. Every other layer missed this
     * -- MockMvc and curl read the 401 happily because neither enforces CORS -- so the
     * assertion here is the header, not the status.
     */
    @Test
    @DisplayName("the rejection is readable by the browser that asked for it")
    void unauthorizedResponseCarriesCorsHeader() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .header("Origin", "http://localhost:5173")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\": \"sh\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
    }

    @Test
    void rejectsPolicyWriteWithWrongToken() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .header(HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\": \"sh\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsPolicyWriteWithCorrectToken() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .header(HEADER, "test-secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\": \"sh\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/policy/extensions/fixed/exe")
                        .header(HEADER, "test-secret-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\": true}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/policy/extensions/custom/sh")
                        .header(HEADER, "test-secret-token"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("reading the policy stays open so the upload page works for everyone")
    void allowsPolicyReadWithoutToken() throws Exception {
        mockMvc.perform(get("/api/v1/policy/extensions"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the audit trail is not public")
    void requiresTokenForAuditLog() throws Exception {
        mockMvc.perform(get("/api/v1/policy/audit"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/policy/audit").header(HEADER, "test-secret-token"))
                .andExpect(status().isOk());
    }

    @Test
    void allowsUploadWithoutToken() throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("files", "notes.txt", "text/plain",
                                "hi".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("records the authorisation mode in the audit trail")
    void auditRecordsTheActor() throws Exception {
        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                .header(HEADER, "test-secret-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"extension\": \"sh\"}"));

        mockMvc.perform(get("/api/v1/policy/audit").header(HEADER, "test-secret-token"))
                .andExpect(jsonPath("$[0].actor").value("admin-token"));
    }
}
