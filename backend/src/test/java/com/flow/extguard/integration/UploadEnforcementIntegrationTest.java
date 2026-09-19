package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flow.extguard.config.StorageProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The point of the whole project: policy configured on the management screen is
 * enforced on real uploads.
 */
class UploadEnforcementIntegrationTest extends IntegrationTestBase {

    @Autowired
    private StorageProperties storageProperties;

    private static final byte[] TEXT = "hello, world".getBytes(StandardCharsets.UTF_8);
    private static final byte[] PE_BYTES = new byte[]{0x4D, 0x5A, (byte) 0x90, 0x00, 0x03};

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "application/octet-stream", content);
    }

    private void setFixedBlocked(String extension, boolean blocked) throws Exception {
        mockMvc.perform(patch("/api/v1/policy/extensions/fixed/{ext}", extension)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocked\": %s}".formatted(blocked)))
                .andExpect(status().isOk());
    }

    /**
     * The requirement in one test: an .exe uploads fine while the box is unchecked,
     * and the identical upload is refused once it is checked.
     */
    @Test
    @DisplayName("checking a fixed extension immediately blocks the same upload")
    void policyChangeTakesEffectOnTheNextUpload() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("hello.exe", PE_BYTES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"));

        setFixedBlocked("exe", true);

        mockMvc.perform(multipart("/api/v1/files").file(file("hello.exe", PE_BYTES)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.results[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[0].code").value("EXTENSION_BLOCKED"))
                .andExpect(jsonPath("$.results[0].message").value(
                        org.hamcrest.Matchers.containsString("exe")));
    }

    @Test
    @DisplayName("unchecking it again allows the upload once more")
    void unblockingRestoresUpload() throws Exception {
        setFixedBlocked("exe", true);
        mockMvc.perform(multipart("/api/v1/files").file(file("hello.exe", PE_BYTES)))
                .andExpect(status().isUnprocessableEntity());

        setFixedBlocked("exe", false);
        mockMvc.perform(multipart("/api/v1/files").file(file("hello.exe", PE_BYTES)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a custom extension is enforced the same way as a fixed one")
    void customExtensionIsEnforced() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("deploy.sh", TEXT)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\": \"sh\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(multipart("/api/v1/files").file(file("deploy.sh", TEXT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.results[0].code").value("EXTENSION_BLOCKED"))
                .andExpect(jsonPath("$.results[0].detail").value(
                        org.hamcrest.Matchers.containsString("커스텀")));
    }

    @Test
    @DisplayName("blocking is case-insensitive")
    void blocksUppercaseExtension() throws Exception {
        setFixedBlocked("exe", true);

        mockMvc.perform(multipart("/api/v1/files").file(file("SETUP.EXE", PE_BYTES)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.results[0].code").value("EXTENSION_BLOCKED"));
    }

    @Test
    @DisplayName("a double extension cannot smuggle a blocked type past the check")
    void blocksDoubleExtension() throws Exception {
        setFixedBlocked("exe", true);

        mockMvc.perform(multipart("/api/v1/files").file(file("invoice.pdf.exe", TEXT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.results[0].code").value("EXTENSION_BLOCKED"))
                .andExpect(jsonPath("$.results[0].detail").value(
                        org.hamcrest.Matchers.containsString("pdf,exe")));
    }

    /**
     * The checkbox has to govern genuine executables, not just files that happen
     * to be named .exe. Every exe fixture here carries the PE magic number for
     * exactly that reason -- an earlier version used text content and so passed
     * while the checkbox did nothing. (These are magic-number prefixes, not valid
     * executables; the detector inspects leading bytes only.)
     */
    @Test
    @DisplayName("a real executable with no extension is refused whatever the policy says")
    void blocksExecutableWithoutExtension() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("payload", PE_BYTES)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.results[0].code").value("EXECUTABLE_CONTENT"))
                .andExpect(jsonPath("$.results[0].detail").value(
                        org.hamcrest.Matchers.containsString("확장자가 없")));
    }

    @Test
    @DisplayName("a real shell script is governed by the custom extension policy")
    void scriptIsGovernedByPolicy() throws Exception {
        MockMultipartFile script = new MockMultipartFile("files", "deploy.sh",
                "application/octet-stream", "#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/api/v1/files").file(script))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/policy/extensions/custom")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"extension\": \"sh\"}"))
                .andExpect(status().isCreated());

        MockMultipartFile again = new MockMultipartFile("files", "deploy.sh",
                "application/octet-stream", "#!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/api/v1/files").file(again))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.results[0].code").value("EXTENSION_BLOCKED"));
    }

    @Test
    @DisplayName("an executable renamed to .jpg is caught by its content")
    void blocksExecutableDisguisedAsImage() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("report.jpg", PE_BYTES)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.results[0].code").value("EXECUTABLE_CONTENT"))
                .andExpect(jsonPath("$.results[0].detail").value(
                        org.hamcrest.Matchers.containsString("PE_EXE")));
    }

    @Test
    void rejectsEmptyFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("empty.txt", new byte[0])))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.results[0].code").value("EMPTY_FILE"));
    }

    @Test
    @DisplayName("stores an accepted file under a generated name, never the original")
    void storesAcceptedFileUnderGeneratedName() throws Exception {
        String body = mockMvc.perform(multipart("/api/v1/files").file(file("notes.txt", TEXT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("ACCEPTED"))
                .andExpect(jsonPath("$.results[0].sha256").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("\"storedName\"");

        String storedName = jdbc.queryForObject(
                "SELECT stored_name FROM upload_record WHERE status = 'ACCEPTED'", String.class);
        assertThat(storedName).isNotNull().endsWith(".bin").doesNotContain("notes");

        Path onDisk = Path.of(storageProperties.getRoot()).resolve(storedName);
        assertThat(Files.exists(onDisk)).as("file is on disk at %s", onDisk).isTrue();
        assertThat(Files.readAllBytes(onDisk)).isEqualTo(TEXT);
    }

    @Test
    @DisplayName("records rejections without writing anything to disk")
    void rejectedUploadIsLoggedButNotStored() throws Exception {
        setFixedBlocked("exe", true);

        mockMvc.perform(multipart("/api/v1/files").file(file("virus.exe", PE_BYTES)))
                .andExpect(status().isUnprocessableEntity());

        Integer rejected = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_record WHERE status = 'REJECTED'", Integer.class);
        String storedName = jdbc.queryForObject(
                "SELECT stored_name FROM upload_record WHERE status = 'REJECTED'", String.class);

        assertThat(rejected).isEqualTo(1);
        assertThat(storedName).as("nothing is written for a rejected upload").isNull();
    }

    @Test
    @DisplayName("judges each file in a batch separately")
    void mixedBatchReportsPerFileVerdicts() throws Exception {
        setFixedBlocked("bat", true);

        mockMvc.perform(multipart("/api/v1/files")
                        .file(file("notes.txt", TEXT))
                        .file(file("setup.bat", TEXT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1));
    }

    @Test
    void listsRecentUploadsIncludingRejections() throws Exception {
        setFixedBlocked("exe", true);
        mockMvc.perform(multipart("/api/v1/files").file(file("a.exe", TEXT)));
        mockMvc.perform(multipart("/api/v1/files").file(file("b.txt", TEXT)));

        mockMvc.perform(get("/api/v1/files"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void rejectsRequestWithNoFilePart() throws Exception {
        mockMvc.perform(multipart("/api/v1/files"))
                .andExpect(status().isBadRequest());
    }
}
