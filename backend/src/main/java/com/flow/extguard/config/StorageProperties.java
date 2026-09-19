package com.flow.extguard.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Where accepted uploads are written, and the hard limits applied to them.
 *
 * <p>The storage root deliberately lives outside any served directory: this
 * application never hands an uploaded file back out over HTTP.
 *
 * <p>The limits below exist because an upload service with no ceiling fills its
 * disk and then fails at every write. Two of them are ceilings ({@code quota},
 * {@code minFreeSpace}) and two govern the reclaim that keeps usage under those
 * ceilings ({@code retention}, {@code orphanGracePeriod}).
 */
@ConfigurationProperties(prefix = "extguard.storage")
public class StorageProperties {

    /** Filesystem root for accepted uploads. Must not be under a served path. */
    private String root = "/var/lib/extguard/files";

    /** Rejected above this size. Also enforced by the servlet container earlier in the request. */
    private DataSize maxFileSize = DataSize.ofMegabytes(20);

    /** Maximum number of files accepted in a single multipart request. */
    private int maxFilesPerRequest = 10;

    /**
     * Ceiling on the bytes held by uploads that have not been purged yet.
     *
     * <p>Measured from the database rather than by walking the disk, so the cost
     * does not grow with the number of files. See {@code UploadRecordRepository}.
     */
    private DataSize quota = DataSize.ofGigabytes(10);

    /**
     * Headroom to leave on the filesystem regardless of {@link #quota}.
     *
     * <p>The quota is this application's own budget; it says nothing about what
     * else shares the disk. Without this check a full disk still breaks writes
     * while the quota reports room to spare.
     */
    private DataSize minFreeSpace = DataSize.ofGigabytes(1);

    /** Accepted files are deleted once they are older than this. Their records remain. */
    private Duration retention = Duration.ofDays(30);

    /**
     * A file younger than this is never treated as an orphan.
     *
     * <p>A file is written before its row is committed, so a newly stored file
     * legitimately has no record for a moment. The grace period keeps the sweep
     * from deleting a file that is mid-upload.
     */
    private Duration orphanGracePeriod = Duration.ofHours(24);

    /** When the reclaim job runs. Spring cron expression, six fields. */
    private String cleanupCron = "0 30 3 * * *";

    /**
     * How many expired records the retention pass holds in memory at a time.
     *
     * <p>Only bounds memory per round; the pass walks the whole expired set
     * either way, advancing a cursor so that a batch whose deletes fail does not
     * block the ones behind it.
     *
     * <p>It bounds the retention pass only. The orphan sweep reads the whole
     * directory listing into a list before it chunks anything, so its memory
     * tracks the number of files past the grace period, not this value. See
     * {@code StorageMaintenanceService#sweepOrphans}.
     */
    private int cleanupBatchSize = 500;

    public String getRoot() {
        return root;
    }

    public void setRoot(String root) {
        this.root = root;
    }

    public DataSize getMaxFileSize() {
        return maxFileSize;
    }

    public void setMaxFileSize(DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    public int getMaxFilesPerRequest() {
        return maxFilesPerRequest;
    }

    public void setMaxFilesPerRequest(int maxFilesPerRequest) {
        this.maxFilesPerRequest = maxFilesPerRequest;
    }

    public DataSize getQuota() {
        return quota;
    }

    public void setQuota(DataSize quota) {
        this.quota = quota;
    }

    public DataSize getMinFreeSpace() {
        return minFreeSpace;
    }

    public void setMinFreeSpace(DataSize minFreeSpace) {
        this.minFreeSpace = minFreeSpace;
    }

    public Duration getRetention() {
        return retention;
    }

    public void setRetention(Duration retention) {
        this.retention = retention;
    }

    public Duration getOrphanGracePeriod() {
        return orphanGracePeriod;
    }

    public void setOrphanGracePeriod(Duration orphanGracePeriod) {
        this.orphanGracePeriod = orphanGracePeriod;
    }

    public String getCleanupCron() {
        return cleanupCron;
    }

    public void setCleanupCron(String cleanupCron) {
        this.cleanupCron = cleanupCron;
    }

    public int getCleanupBatchSize() {
        return cleanupBatchSize;
    }

    public void setCleanupBatchSize(int cleanupBatchSize) {
        this.cleanupBatchSize = cleanupBatchSize;
    }
}
