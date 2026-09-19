package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flow.extguard.config.StorageProperties;
import com.flow.extguard.upload.repository.UploadRecordRepository;
import com.flow.extguard.upload.service.FileStorage;
import com.flow.extguard.upload.service.LocalFileStorage;
import com.flow.extguard.upload.service.StorageMaintenanceService;
import com.flow.extguard.upload.service.StoredFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;

/**
 * What happens when the disk refuses to give the space back.
 *
 * <p>The retention job marks {@code purged_at} to record that a file is gone. If
 * it marked a row whose delete had failed, the row would drop out of the quota
 * sum while the file still occupied the disk: the budget would drift from
 * reality, and nothing would ever retry the deletion. The job must mark only
 * what it actually deleted.
 */
@Import(StorageMaintenanceFailureIntegrationTest.RefusingDeleteConfig.class)
class StorageMaintenanceFailureIntegrationTest extends IntegrationTestBase {

    private static final byte[] CONTENT = "hello, world".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private StorageMaintenanceService maintenance;

    @Autowired
    private UploadRecordRepository recordRepository;

    /**
     * Storage that stores normally but never manages to delete, standing in for
     * a permission problem or an I/O error. Everything else delegates, so the
     * rest of the pipeline behaves exactly as in production.
     */
    @TestConfiguration
    static class RefusingDeleteConfig {

        @Bean
        @Primary
        FileStorage refusingDeleteStorage(StorageProperties properties) {
            LocalFileStorage real = new LocalFileStorage(properties);
            return new FileStorage() {
                @Override
                public StoredFile store(InputStream content) throws IOException {
                    return real.store(content);
                }

                @Override
                public boolean delete(String storedName) {
                    return false;
                }

                @Override
                public List<String> listStoredNamesModifiedBefore(Instant cutoff) throws IOException {
                    return real.listStoredNamesModifiedBefore(cutoff);
                }

                @Override
                public int deleteStaleTempFiles(Instant cutoff) throws IOException {
                    return real.deleteStaleTempFiles(cutoff);
                }

                @Override
                public long usableSpaceBytes() {
                    return real.usableSpaceBytes();
                }
            };
        }
    }

    private String uploadExpiredFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("files", "old.txt", "text/plain", CONTENT)))
                .andExpect(status().isOk());
        String storedName = jdbc.queryForObject(
                "SELECT stored_name FROM upload_record WHERE original_filename = 'old.txt'", String.class);

        Instant longAgo = Instant.now().minus(31, ChronoUnit.DAYS);
        jdbc.update("UPDATE upload_record SET created_at = ? WHERE stored_name = ?",
                Timestamp.from(longAgo), storedName);
        Files.setLastModifiedTime(storageRoot().resolve(storedName), FileTime.from(longAgo));
        return storedName;
    }

    @Test
    @DisplayName("a failed delete leaves purged_at null so the next run retries")
    void failedDeleteIsNotRecordedAsPurged() throws Exception {
        String storedName = uploadExpiredFile();

        int purged = maintenance.purgeExpired();

        assertThat(purged).as("nothing was reclaimed, so nothing is reported").isZero();
        assertThat(storageRoot().resolve(storedName)).exists();

        Timestamp purgedAt = jdbc.queryForObject(
                "SELECT purged_at FROM upload_record WHERE stored_name = ?", Timestamp.class, storedName);
        assertThat(purgedAt)
                .as("marking a file purged while it is still on disk would strand it forever")
                .isNull();
    }

    @Test
    @DisplayName("the bytes keep counting against the quota while the file is still there")
    void unpurgedBytesStillCountAgainstTheQuota() throws Exception {
        uploadExpiredFile();

        maintenance.purgeExpired();

        assertThat(recordRepository.sumLiveBytes())
                .as("the disk did not give the space back, so the budget must not either")
                .isEqualTo(CONTENT.length);
    }

    /**
     * Every delete in the page failing must not spin: the same rows would be
     * fetched again and again with nothing ever marked.
     */
    @Test
    @DisplayName("a page where every delete fails ends the run instead of looping")
    void doesNotLoopWhenEveryDeleteFails() throws Exception {
        uploadExpiredFile();

        assertThat(maintenance.purgeExpired()).isZero();
        assertThat(maintenance.purgeExpired()).as("still a candidate, still retried").isZero();
    }
}
