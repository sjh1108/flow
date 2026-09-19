package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * The quota has to stop uploads before the disk does.
 *
 * <p>The quota is set to 4KB here so it can actually be reached. Using the
 * production 10GB and trusting the arithmetic would leave the enforcement itself
 * unverified -- the same mistake as testing an executable rule with text
 * fixtures.
 */
@TestPropertySource(properties = "extguard.storage.quota=4KB")
class StorageQuotaIntegrationTest extends IntegrationTestBase {

    private static byte[] filler(int size) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) 'a');
        return bytes;
    }

    private MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("files", name, "text/plain", content);
    }

    private List<Path> filesOnDisk() throws IOException {
        Path root = storageRoot();
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    @Test
    @DisplayName("refuses an upload once the quota is used up, and says so with 507")
    void refusesUploadOverQuota() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("first.txt", filler(3000))))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/v1/files").file(file("second.txt", filler(3000))))
                .andExpect(status().isInsufficientStorage())
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.results[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.results[0].code").value("STORAGE_QUOTA_EXCEEDED"))
                .andExpect(jsonPath("$.results[0].detail")
                        .value(org.hamcrest.Matchers.containsString("한도")));
    }

    @Test
    @DisplayName("a refused upload writes nothing to disk")
    void refusedUploadIsNotStored() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("first.txt", filler(3000))))
                .andExpect(status().isOk());
        int afterFirst = filesOnDisk().size();

        mockMvc.perform(multipart("/api/v1/files").file(file("second.txt", filler(3000))))
                .andExpect(status().isInsufficientStorage());

        assertThat(filesOnDisk())
                .as("refusing for capacity must not itself consume capacity")
                .hasSize(afterFirst);
    }

    @Test
    @DisplayName("the refusal is recorded, so the audit trail explains the gap")
    void refusalIsRecorded() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("first.txt", filler(3000))));
        mockMvc.perform(multipart("/api/v1/files").file(file("second.txt", filler(3000))));

        String code = jdbc.queryForObject(
                "SELECT rejection_code FROM upload_record WHERE status = 'REJECTED'", String.class);
        String storedName = jdbc.queryForObject(
                "SELECT stored_name FROM upload_record WHERE status = 'REJECTED'", String.class);

        assertThat(code).isEqualTo("STORAGE_QUOTA_EXCEEDED");
        assertThat(storedName).isNull();
    }

    /**
     * Ten files in one request must not each spend the same remaining headroom.
     * The budget is measured once and charged as the batch proceeds.
     */
    @Test
    @DisplayName("a single batch cannot spend the same headroom twice")
    void batchCannotOverspendTheSameHeadroom() throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                        .file(file("a.txt", filler(3000)))
                        .file(file("b.txt", filler(3000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1))
                .andExpect(jsonPath("$.results[1].code").value("STORAGE_QUOTA_EXCEEDED"));
    }

    @Test
    @DisplayName("a mixed batch still succeeds overall")
    void mixedBatchIsNotAnError() throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                        .file(file("small.txt", "hi".getBytes(StandardCharsets.UTF_8)))
                        .file(file("huge.txt", filler(5000))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptedCount").value(1))
                .andExpect(jsonPath("$.rejectedCount").value(1));
    }

    /**
     * A file blocked by policy is refused for being what it is, not for lack of
     * room, so its status must stay 422 even when the disk is full.
     */
    @Test
    @DisplayName("a policy rejection keeps its own 422 rather than borrowing 507")
    void policyRejectionIsNotReportedAsAStorageProblem() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("first.txt", filler(3000))))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/v1/files").file(file("empty.txt", new byte[0])))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.results[0].code").value("EMPTY_FILE"));
    }

    /**
     * The batch that made the old wording wrong. Everything is refused, but for
     * two different kinds of reason, so neither status is true of every file:
     * 507 would promise a retry that cannot help the empty file, and 422 read as
     * "resending will not help" is false for the one that only lacked room.
     *
     * <p>422 is the right answer because it never promises a retry that cannot
     * succeed. What it must not be read as is "none of these can ever succeed" --
     * hence the assertion that both codes survive in {@code results}, which is
     * where per-file retryability actually lives.
     */
    @Test
    @DisplayName("a batch refused for mixed reasons is 422 and keeps both codes")
    void mixedRejectionReasonsAreReportedAs422() throws Exception {
        mockMvc.perform(multipart("/api/v1/files").file(file("first.txt", filler(3000))))
                .andExpect(status().isOk());

        mockMvc.perform(multipart("/api/v1/files")
                        .file(file("empty.txt", new byte[0]))
                        .file(file("roomless.txt", filler(3000))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.acceptedCount").value(0))
                .andExpect(jsonPath("$.rejectedCount").value(2))
                .andExpect(jsonPath("$.results[0].code").value("EMPTY_FILE"))
                .andExpect(jsonPath("$.results[1].code").value("STORAGE_QUOTA_EXCEEDED"));
    }
}
