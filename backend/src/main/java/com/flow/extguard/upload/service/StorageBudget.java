package com.flow.extguard.upload.service;

import com.flow.extguard.config.StorageProperties;
import java.util.Optional;

/**
 * How much more this request may store, against two independent ceilings.
 *
 * <p>The quota is the application's own budget, measured from the upload
 * records. Free space is the filesystem's, which the quota knows nothing about:
 * something else sharing the disk can fill it while the quota still reports
 * room, and the write would then fail partway. Both are checked before anything
 * is written, so a refusal is a clean rejection rather than a broken write.
 *
 * <p>Created once per request and decremented as files are accepted, so a batch
 * cannot slip past the ceiling by spending the same headroom ten times.
 *
 * <p><strong>Not synchronised, and not meant to be.</strong> Concurrent requests
 * each read usage at their own start, so the ceiling can be overshot by at most
 * (requests in flight x max file size) before the next request sees the new
 * total and refuses. Holding a reservation across the write would mean a lock or
 * a reservation table, which is not worth it for a bound this small.
 */
final class StorageBudget {

    private long quotaRemaining;
    private long freeSpaceRemaining;

    private StorageBudget(long quotaRemaining, long freeSpaceRemaining) {
        this.quotaRemaining = quotaRemaining;
        this.freeSpaceRemaining = freeSpaceRemaining;
    }

    static StorageBudget of(long usedBytes, long usableSpaceBytes, StorageProperties properties) {
        long quota = properties.getQuota().toBytes();
        long minFree = properties.getMinFreeSpace().toBytes();
        return new StorageBudget(
                Math.max(0, quota - usedBytes),
                usableSpaceBytes == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0, usableSpaceBytes - minFree));
    }

    /**
     * The reason {@code sizeBytes} cannot be stored, or empty if it can.
     *
     * <p>The detail names which ceiling was hit, because the two call for
     * different responses: a quota ceiling is a configuration decision, a full
     * disk is an operational incident.
     */
    Optional<String> shortfall(long sizeBytes) {
        if (sizeBytes > quotaRemaining) {
            return Optional.of("저장소 사용량이 한도에 도달했습니다. 남은 용량: %s, 요청 크기: %s"
                    .formatted(humanBytes(quotaRemaining), humanBytes(sizeBytes)));
        }
        if (sizeBytes > freeSpaceRemaining) {
            return Optional.of("디스크 여유 공간이 부족합니다. 사용 가능: %s, 요청 크기: %s"
                    .formatted(humanBytes(freeSpaceRemaining), humanBytes(sizeBytes)));
        }
        return Optional.empty();
    }

    /** Charges an accepted file against both ceilings for the rest of the batch. */
    void charge(long sizeBytes) {
        quotaRemaining = Math.max(0, quotaRemaining - sizeBytes);
        if (freeSpaceRemaining != Long.MAX_VALUE) {
            freeSpaceRemaining = Math.max(0, freeSpaceRemaining - sizeBytes);
        }
    }

    private static String humanBytes(long bytes) {
        if (bytes == Long.MAX_VALUE) {
            return "제한 없음";
        }
        if (bytes < 1024) {
            return bytes + "B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return "%.1f%s".formatted(value, units[unit]);
    }
}
