package com.flow.extguard.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.flow.extguard.upload.repository.UploadRecordRepository;
import com.flow.extguard.upload.service.StorageMaintenanceService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

/**
 * The reclaim half of the storage defence: without it the quota is a wall the
 * service eventually hits and never recovers from.
 */
class StorageMaintenanceIntegrationTest extends IntegrationTestBase {

    @Autowired
    private StorageMaintenanceService maintenance;

    @Autowired
    private UploadRecordRepository recordRepository;

    private static final byte[] CONTENT = "hello, world".getBytes(StandardCharsets.UTF_8);

    /** Uploads a file through the real path and returns the name it was stored under. */
    private String uploadAndGetStoredName(String filename) throws Exception {
        mockMvc.perform(multipart("/api/v1/files")
                        .file(new MockMultipartFile("files", filename, "text/plain", CONTENT)))
                .andExpect(status().isOk());
        return jdbc.queryForObject(
                "SELECT stored_name FROM upload_record WHERE original_filename = ?",
                String.class, filename);
    }

    /** Backdates both the record and the file, the way a month of running would. */
    private void backdate(String storedName, Instant to) throws IOException {
        jdbc.update("UPDATE upload_record SET created_at = ? WHERE stored_name = ?",
                Timestamp.from(to), storedName);
        Files.setLastModifiedTime(storageRoot().resolve(storedName), FileTime.from(to));
    }

    @Test
    @DisplayName("deletes files past the retention period but keeps their records")
    void purgesExpiredFilesAndKeepsTheRecord() throws Exception {
        String storedName = uploadAndGetStoredName("old.txt");
        backdate(storedName, Instant.now().minus(31, ChronoUnit.DAYS));

        int purged = maintenance.purgeExpired();

        assertThat(purged).isEqualTo(1);
        assertThat(storageRoot().resolve(storedName)).doesNotExist();

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM upload_record WHERE stored_name = ?", Integer.class, storedName);
        Timestamp purgedAt = jdbc.queryForObject(
                "SELECT purged_at FROM upload_record WHERE stored_name = ?", Timestamp.class, storedName);

        assertThat(rows).as("the audit trail outlives the bytes").isEqualTo(1);
        assertThat(purgedAt).as("the record says the file is gone").isNotNull();
    }

    /**
     * ACCEPTED stays true forever, but the file behind it does not. Without
     * {@code purgedAt} on the wire, a reader of the history cannot tell a record
     * whose bytes are still on disk from one that outlived them.
     */
    @Test
    @DisplayName("the upload history reports that a purged file is gone")
    void historyExposesThePurgedState() throws Exception {
        String storedName = uploadAndGetStoredName("old.txt");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/files"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].purgedAt").doesNotExist());

        backdate(storedName, Instant.now().minus(31, ChronoUnit.DAYS));
        maintenance.purgeExpired();

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/files"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].status").value("ACCEPTED"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$[0].purgedAt").exists());
    }

    @Test
    @DisplayName("leaves files inside the retention period alone")
    void keepsFilesWithinRetention() throws Exception {
        String storedName = uploadAndGetStoredName("recent.txt");
        backdate(storedName, Instant.now().minus(3, ChronoUnit.DAYS));

        assertThat(maintenance.purgeExpired()).isZero();
        assertThat(storageRoot().resolve(storedName)).exists();
    }

    @Test
    @DisplayName("a purged upload stops counting against the quota")
    void purgedBytesAreReleasedFromTheQuota() throws Exception {
        String storedName = uploadAndGetStoredName("old.txt");
        long before = recordRepository.sumLiveBytes();
        backdate(storedName, Instant.now().minus(31, ChronoUnit.DAYS));

        maintenance.purgeExpired();

        assertThat(before).isEqualTo(CONTENT.length);
        assertThat(recordRepository.sumLiveBytes())
                .as("reclaiming the disk has to reclaim the budget too")
                .isZero();
    }

    @Test
    @DisplayName("deletes a stored file that no record accounts for")
    void sweepsOrphanedFile() throws Exception {
        Path directory = Files.createDirectories(storageRoot().resolve("2026/01/01"));
        Path orphan = Files.write(directory.resolve("11111111-2222-3333-4444-555555555555.bin"), CONTENT);
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        int removed = maintenance.sweepOrphans();

        assertThat(removed).isEqualTo(1);
        assertThat(orphan).doesNotExist();
    }

    /**
     * A file is written before its record is committed, so an unaccounted file
     * that is only seconds old is an upload in flight. Deleting it would destroy
     * a good upload to reclaim nothing.
     */
    @Test
    @DisplayName("spares an unaccounted file that is younger than the grace period")
    void doesNotSweepAnUploadInFlight() throws Exception {
        Path directory = Files.createDirectories(storageRoot().resolve("2026/01/01"));
        Path inFlight = Files.write(directory.resolve("99999999-8888-7777-6666-555555555555.bin"), CONTENT);

        assertThat(maintenance.sweepOrphans()).isZero();
        assertThat(inFlight).exists();
    }

    @Test
    @DisplayName("never sweeps a file a record still points at")
    void doesNotSweepAKnownFile() throws Exception {
        String storedName = uploadAndGetStoredName("kept.txt");
        Files.setLastModifiedTime(storageRoot().resolve(storedName),
                FileTime.from(Instant.now().minus(5, ChronoUnit.DAYS)));

        assertThat(maintenance.sweepOrphans()).isZero();
        assertThat(storageRoot().resolve(storedName)).exists();
    }

    @Test
    @DisplayName("removes an abandoned partial write")
    void sweepsAbandonedPartialWrite() throws Exception {
        Path directory = Files.createDirectories(storageRoot().resolve("2026/01/01"));
        Path partial = Files.writeString(directory.resolve("abandoned.bin.part"), "half");
        Files.setLastModifiedTime(partial, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        assertThat(maintenance.sweepOrphans()).isEqualTo(1);
        assertThat(partial).doesNotExist();
    }

    @Test
    @DisplayName("the scheduled entry point runs both halves without failing")
    void scheduledEntryPointRunsBothHalves() throws Exception {
        String expired = uploadAndGetStoredName("old.txt");
        backdate(expired, Instant.now().minus(31, ChronoUnit.DAYS));

        Path directory = Files.createDirectories(storageRoot().resolve("2026/01/01"));
        Path orphan = Files.write(directory.resolve("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee.bin"), CONTENT);
        Files.setLastModifiedTime(orphan, FileTime.from(Instant.now().minus(2, ChronoUnit.DAYS)));

        maintenance.runMaintenance();

        assertThat(storageRoot().resolve(expired)).doesNotExist();
        assertThat(orphan).doesNotExist();
    }

    /**
     * Running twice must be safe: the job is interrupted by any restart and the
     * next round picks up whatever was left.
     */
    @Test
    @DisplayName("a second run over the same state changes nothing")
    void isIdempotent() throws Exception {
        String storedName = uploadAndGetStoredName("old.txt");
        backdate(storedName, Instant.now().minus(31, ChronoUnit.DAYS));

        maintenance.runMaintenance();

        assertThat(maintenance.purgeExpired()).isZero();
        assertThat(maintenance.sweepOrphans()).isZero();
    }
}
