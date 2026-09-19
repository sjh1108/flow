package com.flow.extguard.upload.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

public interface FileStorage {

    StoredFile store(InputStream content) throws IOException;

    /**
     * Deletes a stored file, reporting whether it is now gone.
     *
     * <p>Returns true when the file was deleted or was already absent, false when
     * it may still be on disk. The result matters: a caller that records the
     * deletion somewhere else -- the retention job marking {@code purged_at} --
     * must not record one that failed, or the record and the disk drift apart
     * with nothing left to reconcile them.
     */
    boolean delete(String storedName);

    /**
     * Stored names last modified before {@code cutoff}, for reconciliation
     * against the records.
     *
     * <p>Only files older than the cutoff are reported, because a file is on
     * disk before its row is committed: a fresh file with no record is an upload
     * in flight, not an orphan.
     */
    List<String> listStoredNamesModifiedBefore(Instant cutoff) throws IOException;

    /**
     * Deletes leftover partial writes older than {@code cutoff}, returning how
     * many went.
     *
     * <p>{@link #store} removes its own partial file when a write fails, so one
     * survives only a kill or a power loss mid-write.
     */
    int deleteStaleTempFiles(Instant cutoff) throws IOException;

    /** Free space on the filesystem holding the storage root, in bytes. */
    long usableSpaceBytes();
}
