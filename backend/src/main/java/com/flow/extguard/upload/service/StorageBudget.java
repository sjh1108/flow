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
 * <p><strong>Not synchronised, and not meant to be.</strong> Usage is read once
 * per request, so requests that overlap all see the same total and each may
 * spend the same headroom. "Overlap" is specific: a request counts if it reads
 * the total before the others commit their rows, so the window is read-to-commit
 * -- roughly the time it takes to write the files -- not wall-clock concurrency.
 *
 * <p>Write R for the headroom {@code Q - U} the overlapping requests all read
 * (Q the quota, U the committed usage), K for {@code maxFilesPerRequest x
 * maxFileSize} -- the most one request can carry, 200MB at the defaults -- and N
 * for the number that overlap. Each request writes at most {@code min(R, K)}, so
 * the total lands at {@code U + N x min(R, K)} and the amount past the quota is
 *
 * <pre>
 *   overshoot = max(0, N x min(R, K) - R)
 * </pre>
 *
 * <p>That is not monotonic in R, which is the part worth stating explicitly. It
 * <strong>peaks at exactly {@code R = K}</strong>, where it equals
 * {@code (N - 1) x K} -- 1.8GB for ten overlapping full batches. On either side
 * it falls away: below K every request is capped by the headroom rather than by
 * its own size, giving {@code (N - 1) x R}; above K it decays as {@code N x K - R}
 * and reaches zero at {@code R = N x K} (2GB), where there is simply room for
 * everyone. At {@code R = 0} it is zero too, since every request then sees no
 * headroom and refuses.
 *
 * <p>So the worst case is not a nearly-full store, as one might assume, but one
 * sitting exactly one request's worth below the line. {@code (N - 1) x min(R, K)}
 * is a valid ceiling on all of this and is what a quick estimate should use; it
 * is tight for {@code R <= K} and loose above.
 *
 * <p>Lowering {@code max-files-per-request} or {@code max-file-size} shrinks K
 * and the peak with it. Re-reading the total per file rather than per request
 * would replace K with one file size, at the cost of a query per file. Only a
 * reservation held across the write makes it exact, and that serialises uploads.
 *
 * <p>The looseness is accepted because an overshoot costs budget accuracy rather
 * than correctness. What it does not buy is immunity from a full disk:
 * {@code minFreeSpace} is read once per request from a figure other processes are
 * already changing, so it lowers the odds of ENOSPC without ruling it out, and a
 * write that hits it still has to fail well. See {@link LocalFileStorage} for how
 * far that goes -- and where it stops.
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
