package com.flow.extguard.upload.repository;

import com.flow.extguard.upload.domain.UploadRecord;
import com.flow.extguard.upload.domain.UploadStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Extends the bare {@code Repository} marker rather than {@code JpaRepository},
 * so the only operations that exist are the ones written here. Notably there is
 * no {@code delete}: upload records are never removed, only marked purged.
 */
public interface UploadRecordRepository extends Repository<UploadRecord, Long> {

    UploadRecord save(UploadRecord uploadRecord);

    List<UploadRecord> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Bytes held by uploads still on disk.
     *
     * <p>Read from the records rather than by walking the filesystem, so the
     * cost is one indexed aggregate instead of a directory traversal per upload.
     * Purged rows drop out, which keeps the summed set inside the retention
     * window however large the table grows.
     */
    @Query("""
            select coalesce(sum(r.sizeBytes), 0) from UploadRecord r
             where r.status = com.flow.extguard.upload.domain.UploadStatus.ACCEPTED
               and r.purgedAt is null
            """)
    long sumLiveBytes();

    /**
     * Accepted uploads old enough to delete and still on disk, taken in
     * {@code (createdAt, id)} order after the given cursor.
     *
     * <p>A cursor rather than an offset, because a candidate whose delete fails
     * stays a candidate. Paging by offset would re-read the same failures at the
     * head of every page and never reach what lies behind them: one stuck
     * directory would stop reclaim for the whole table. The cursor moves past a
     * failure within the same run and comes back to it on the next one.
     *
     * <p>Pass {@link Instant#EPOCH} and {@code 0} to start. No record can precede
     * the epoch -- {@code createdAt} is assigned from {@code Instant.now()} when
     * the row is built -- so that pair is a safe sentinel.
     */
    @Query("""
            select r from UploadRecord r
             where r.status = com.flow.extguard.upload.domain.UploadStatus.ACCEPTED
               and r.purgedAt is null
               and r.createdAt < :cutoff
               and (r.createdAt > :afterCreatedAt
                    or (r.createdAt = :afterCreatedAt and r.id > :afterId))
             order by r.createdAt asc, r.id asc
            """)
    List<UploadRecord> findPurgeCandidatesAfter(@Param("cutoff") Instant cutoff,
                                                @Param("afterCreatedAt") Instant afterCreatedAt,
                                                @Param("afterId") long afterId,
                                                Pageable pageable);

    /**
     * Records the fact that the files are gone.
     *
     * <p>An update query rather than a setter: {@link UploadRecord} is immutable
     * once built, and confining the write to this one column is what lets the
     * production grant stay {@code UPDATE (purged_at)} instead of a table-wide
     * one.
     */
    @Modifying
    @Query("update UploadRecord r set r.purgedAt = :purgedAt where r.id in :ids")
    int markPurged(@Param("ids") Collection<Long> ids, @Param("purgedAt") Instant purgedAt);

    /** Of the given stored names, those a record knows about. */
    @Query("select r.storedName from UploadRecord r where r.storedName in :names")
    List<String> findKnownStoredNames(@Param("names") Collection<String> names);
}
