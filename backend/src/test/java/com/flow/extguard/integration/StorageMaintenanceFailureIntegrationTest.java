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
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

/**
 * What happens when the disk refuses to give the space back.
 *
 * <p>Two failure properties are pinned here. A delete that fails must not be
 * recorded as a purge, or the budget and the disk drift apart with nothing left
 * to reconcile them. And a delete that keeps failing must not block the
 * candidates behind it, or one stuck file stops reclaim for the whole table.
 *
 * <p>The batch size is lowered to 2 so a "whole batch fails" case fits in a
 * handful of rows; at the production 500 the starvation below could only be
 * reproduced with 500 stuck files.
 */
@Import(StorageMaintenanceFailureIntegrationTest.RefusingDeleteConfig.class)
@TestPropertySource(properties = "extguard.storage.cleanup-batch-size=2")
class StorageMaintenanceFailureIntegrationTest extends IntegrationTestBase {

    private static final byte[] CONTENT = "hello, world".getBytes(StandardCharsets.UTF_8);

    /**
     * Which stored names refuse to be deleted. Every delete fails by default; a
     * test narrows it to leave only some files stuck.
     */
    private static Predicate<String> undeletable = name -> true;

    @Autowired
    private StorageMaintenanceService maintenance;

    @Autowired
    private UploadRecordRepository recordRepository;

    @BeforeEach
    void allDeletesFailByDefault() {
        undeletable = name -> true;
    }

    /**
     * Storage that stores normally but cannot delete, standing in for a
     * permission problem or an I/O error. Everything else delegates, so the rest
     * of the pipeline behaves exactly as in production.
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

                // A stuck name reports failure and leaves the file alone; every
                // other name is deleted for real, so "reported purged" and "gone
                // from disk" stay the same thing in this test.
                @Override
                public boolean delete(String storedName) {
                    return !undeletable.test(storedName) && real.delete(storedName);
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

    /** Uploads a file and backdates it past the retention period. */
    private String uploadExpiredFile(String filename, int daysOld) throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("files", filename, "text/plain", CONTENT)))
                .andExpect(status().isOk());
        String storedName = jdbc.queryForObject(
                "SELECT stored_name FROM upload_record WHERE original_filename = ?",
                String.class, filename);

        Instant longAgo = Instant.now().minus(daysOld, ChronoUnit.DAYS);
        jdbc.update("UPDATE upload_record SET created_at = ? WHERE stored_name = ?",
                Timestamp.from(longAgo), storedName);
        Files.setLastModifiedTime(storageRoot().resolve(storedName), FileTime.from(longAgo));
        return storedName;
    }

    private String uploadExpiredFile() throws Exception {
        return uploadExpiredFile("old.txt", 31);
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

    @Test
    @DisplayName("a run where every delete fails ends instead of looping")
    void doesNotLoopWhenEveryDeleteFails() throws Exception {
        uploadExpiredFile();

        assertThat(maintenance.purgeExpired()).isZero();
        assertThat(maintenance.purgeExpired()).as("still a candidate, still retried").isZero();
    }

    /**
     * The case that offset paging gets wrong. With a batch size of 2 the two
     * oldest records fill the first batch and both fail, so paging from offset 0
     * would hand back the same two forever and the deletable records behind them
     * would never be reached -- reclaim would stop while the quota kept filling.
     * The cursor steps past them within the same run.
     */
    @Test
    @DisplayName("records behind a permanently failing batch are still reclaimed")
    void failingBatchDoesNotStarveTheRecordsBehindIt() throws Exception {
        String stuckA = uploadExpiredFile("stuck-a.txt", 40);
        String stuckB = uploadExpiredFile("stuck-b.txt", 39);
        String freeA = uploadExpiredFile("free-a.txt", 38);
        String freeB = uploadExpiredFile("free-b.txt", 37);
        undeletable = name -> name.equals(stuckA) || name.equals(stuckB);

        int purged = maintenance.purgeExpired();

        assertThat(purged).as("the two behind the stuck batch must still be reclaimed").isEqualTo(2);
        assertThat(storageRoot().resolve(freeA)).doesNotExist();
        assertThat(storageRoot().resolve(freeB)).doesNotExist();
        assertThat(storageRoot().resolve(stuckA)).exists();
        assertThat(storageRoot().resolve(stuckB)).exists();

        Integer unpurged = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_record WHERE purged_at IS NULL AND status = 'ACCEPTED'",
                Integer.class);
        assertThat(unpurged).as("only the stuck pair stays a candidate").isEqualTo(2);
    }
}
