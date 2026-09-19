package com.flow.extguard.upload.service;

import com.flow.extguard.config.StorageProperties;
import com.flow.extguard.upload.domain.UploadRecord;
import com.flow.extguard.upload.domain.UploadStatus;
import com.flow.extguard.upload.repository.UploadRecordRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reclaims disk space, so that the quota is a level the service settles at
 * rather than a wall it eventually hits.
 *
 * <p>Two jobs, both idempotent and both safe to interrupt:
 *
 * <ul>
 *   <li><strong>Retention.</strong> Files past the retention period are deleted;
 *       their records stay and are marked purged. The records are the audit
 *       trail of what was uploaded and refused, and that is worth keeping long
 *       after the bytes are not.
 *   <li><strong>Orphans.</strong> Files with no record at all. They should not
 *       exist -- a failed write cleans up after itself and a failed record write
 *       deletes the file -- but a kill at the wrong moment still leaves one, and
 *       nothing else would ever reclaim it.
 * </ul>
 */
@Service
public class StorageMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceService.class);

    /** Bounds how much is held in memory per round; the loop continues regardless. */
    private static final int BATCH_SIZE = 500;

    private final FileStorage storage;
    private final UploadRecordRepository recordRepository;
    private final StorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;

    public StorageMaintenanceService(FileStorage storage,
                                     UploadRecordRepository recordRepository,
                                     StorageProperties storageProperties,
                                     TransactionTemplate transactionTemplate) {
        this.storage = storage;
        this.recordRepository = recordRepository;
        this.storageProperties = storageProperties;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(cron = "${extguard.storage.cleanup-cron}")
    public void runMaintenance() {
        long startedAt = System.currentTimeMillis();
        int purged = purgeExpired();
        int orphans = sweepOrphans();
        log.info("Storage maintenance finished in {}ms: {} expired file(s) purged, {} orphan(s) removed",
                System.currentTimeMillis() - startedAt, purged, orphans);
    }

    /**
     * Deletes files past the retention period and marks their records.
     *
     * <p>The file goes before the row is marked. Interrupted halfway, the row is
     * still unmarked and the next round retries it; deleting a file that is
     * already gone is a no-op, so the retry costs nothing. The reverse order
     * would lose track of files that were marked but never deleted.
     *
     * <p>The transaction is opened explicitly around the update rather than by
     * annotating this method, for two reasons. {@link #runMaintenance} calls it
     * directly, and a self-invocation never passes through the proxy that
     * {@code @Transactional} relies on -- the annotation would silently do
     * nothing on the one path that actually runs on a schedule. And the file
     * deletions belong outside any transaction: they cannot be rolled back, and
     * holding a database connection across filesystem I/O is how a pool starves.
     */
    public int purgeExpired() {
        Instant cutoff = Instant.now().minus(storageProperties.getRetention());
        int total = 0;

        while (true) {
            List<UploadRecord> expired =
                    recordRepository.findByStatusAndPurgedAtIsNullAndCreatedAtLessThanOrderByCreatedAtAsc(
                            UploadStatus.ACCEPTED, cutoff, PageRequest.of(0, BATCH_SIZE));
            if (expired.isEmpty()) {
                return total;
            }

            List<Long> purgedIds = expired.stream()
                    .peek(record -> storage.delete(record.getStoredName()))
                    .map(UploadRecord::getId)
                    .toList();

            transactionTemplate.executeWithoutResult(
                    ignored -> recordRepository.markPurged(purgedIds, Instant.now()));
            total += purgedIds.size();

            if (expired.size() < BATCH_SIZE) {
                return total;
            }
        }
    }

    /**
     * Deletes stored files that no record accounts for, plus abandoned partial
     * writes.
     *
     * <p>Only files older than the grace period are considered. A file is on
     * disk before its record is committed, so a fresh unaccounted file is an
     * upload in flight -- deleting it would destroy a good upload to reclaim
     * nothing.
     */
    public int sweepOrphans() {
        Instant cutoff = Instant.now().minus(storageProperties.getOrphanGracePeriod());
        int removed = 0;

        try {
            List<String> onDisk = storage.listStoredNamesModifiedBefore(cutoff);
            for (int from = 0; from < onDisk.size(); from += BATCH_SIZE) {
                List<String> chunk = onDisk.subList(from, Math.min(from + BATCH_SIZE, onDisk.size()));
                Set<String> known = new HashSet<>(recordRepository.findKnownStoredNames(chunk));
                for (String candidate : chunk) {
                    if (!known.contains(candidate)) {
                        log.warn("Deleting orphaned file '{}': no upload record refers to it", candidate);
                        storage.delete(candidate);
                        removed++;
                    }
                }
            }
            removed += storage.deleteStaleTempFiles(cutoff);
        } catch (IOException e) {
            // A sweep that cannot read the directory is a problem to look at, not
            // a reason to fail the whole maintenance run.
            log.error("Orphan sweep could not walk the storage root", e);
        }
        return removed;
    }
}
