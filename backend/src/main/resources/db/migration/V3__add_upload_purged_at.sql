-- Retention marker for the storage reclaim job.
--
-- Accepted uploads are deleted from disk once they pass the retention period,
-- but their upload_record rows stay: the table is the audit trail of what was
-- uploaded and what was refused, and losing that to a disk-space problem would
-- be the wrong trade. purged_at records that the file is gone while the row
-- remains.
--
-- It also bounds the quota query. Live usage is the sum of size_bytes over
-- accepted, unpurged rows, so once a row is marked it drops out of the sum --
-- the set being summed stays within the retention window no matter how long the
-- audit trail grows.
--
-- Portable DDL only: these migrations run against MySQL 8.4 in production and
-- H2 in MySQL mode under test.
ALTER TABLE upload_record ADD COLUMN purged_at DATETIME(6) NULL;

-- Covers the quota sum end to end, so it is answered from the index alone.
CREATE INDEX idx_upload_record_live ON upload_record (status, purged_at, size_bytes);
