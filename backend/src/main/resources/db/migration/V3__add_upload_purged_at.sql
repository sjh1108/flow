-- Retention marker for the storage reclaim job.
--
-- Accepted uploads are deleted from disk once they pass the retention period,
-- but their upload_record rows stay: the table is the audit trail of what was
-- uploaded and what was refused, and losing that to a disk-space problem would
-- be the wrong trade. purged_at records that the file is gone while the row
-- remains.
--
-- It also keeps the quota query small. Live usage is the sum of size_bytes over
-- accepted, unpurged rows, so a marked row drops out: rejected uploads and
-- already-purged ones stop counting, and the sum does not grow with the audit
-- trail however long it gets.
--
-- That is not the same as bounding the set by the retention window. A row whose
-- file cannot be deleted keeps purged_at NULL and keeps counting, which is
-- deliberate -- the bytes really are still on the disk.
--
-- Portable DDL only: these migrations run against MySQL 8.4 in production and
-- H2 in MySQL mode under test.
ALTER TABLE upload_record ADD COLUMN purged_at DATETIME(6) NULL;

-- Covers the quota sum end to end, so it is answered from the index alone.
CREATE INDEX idx_upload_record_live ON upload_record (status, purged_at, size_bytes);
