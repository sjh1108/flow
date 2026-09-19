package com.flow.extguard.upload.service;

import com.flow.extguard.config.StorageProperties;
import com.flow.extguard.upload.domain.UploadRecord;
import com.flow.extguard.upload.domain.UploadStatus;
import com.flow.extguard.upload.repository.UploadRecordRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
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

    /**
     * Chunk size for the orphan sweep's name lookups.
     *
     * <p>Bounds the {@code IN (...)} list handed to the database, nothing else.
     * The listing it chunks is already fully in memory by then.
     */
    private static final int LOOKUP_CHUNK = 500;

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
     * <p>Candidates are walked by a {@code (createdAt, id)} cursor, so a record
     * whose delete fails is stepped over rather than re-read at the head of the
     * next page. Offset paging would starve everything behind a persistent
     * failure -- a single undeletable file would stop reclaim entirely.
     *
     * <p>The file goes before the row is marked, and the row is marked only if
     * the delete reported success. Interrupted halfway -- or refused by the
     * filesystem -- the row stays unmarked and the next round retries it;
     * deleting a file that is already gone counts as success, so the retry costs
     * nothing. The reverse order, or marking regardless of the result, would lose
     * track of files that were recorded as purged but are still on disk.
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
        int batchSize = storageProperties.getCleanupBatchSize();
        int total = 0;

        // The cursor is what keeps a failure from blocking everything behind it.
        // Reading page 0 each round instead would hand back the same unpurged
        // rows forever: one directory the process cannot delete from would stall
        // reclaim for the entire table, and the quota would fill with nothing
        // able to free it.
        Instant afterCreatedAt = Instant.EPOCH;
        long afterId = 0;

        while (true) {
            List<UploadRecord> expired = recordRepository.findPurgeCandidatesAfter(
                    cutoff, afterCreatedAt, afterId, PageRequest.of(0, batchSize));
            if (expired.isEmpty()) {
                return total;
            }

            // Only files that are actually gone get marked. Marking one whose
            // delete failed would drop it out of the quota sum while it still
            // occupies the disk -- the budget and the disk would drift apart with
            // nothing left to reconcile them, and the row would never be retried.
            List<Long> purgedIds = new ArrayList<>(expired.size());
            for (UploadRecord record : expired) {
                if (storage.delete(record.getStoredName())) {
                    purgedIds.add(record.getId());
                } else {
                    log.error("Leaving '{}' unpurged: its file could not be deleted. "
                            + "Moving on; the next run will retry it", record.getStoredName());
                }
            }

            if (!purgedIds.isEmpty()) {
                transactionTemplate.executeWithoutResult(
                        ignored -> recordRepository.markPurged(purgedIds, Instant.now()));
                total += purgedIds.size();
            }

            if (expired.size() < batchSize) {
                return total;
            }

            UploadRecord last = expired.getLast();
            afterCreatedAt = last.getCreatedAt();
            afterId = last.getId();
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
     *
     * <p><strong>Memory.</strong> The listing arrives as a whole list, so this
     * sweep holds one name per file past the grace period -- {@code
     * cleanup-batch-size} does not bound it, and neither does {@link
     * #LOOKUP_CHUNK}, which only splits the lookups made from that list. The
     * quota caps bytes rather than files, so a store full of small files is the
     * case that grows here: 10GB of 1KB files is ten million names. At that
     * scale the listing has to become a stream, which means
     * {@link FileStorage#listStoredNamesModifiedBefore} returning one and the
     * caller closing it; it is not a change to make blind, so it waits for a
     * file count that justifies it.
     */
    public int sweepOrphans() {
        Instant cutoff = Instant.now().minus(storageProperties.getOrphanGracePeriod());
        int removed = 0;

        try {
            List<String> onDisk = storage.listStoredNamesModifiedBefore(cutoff);
            for (int from = 0; from < onDisk.size(); from += LOOKUP_CHUNK) {
                List<String> chunk = onDisk.subList(from, Math.min(from + LOOKUP_CHUNK, onDisk.size()));
                Set<String> known = new HashSet<>(recordRepository.findKnownStoredNames(chunk));
                for (String candidate : chunk) {
                    if (!known.contains(candidate)) {
                        log.warn("Deleting orphaned file '{}': no upload record refers to it", candidate);
                        // Counted only if it is really gone, so the reported figure
                        // is reclaimed space rather than attempts.
                        if (storage.delete(candidate)) {
                            removed++;
                        }
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
