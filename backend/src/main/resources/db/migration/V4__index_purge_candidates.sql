-- Widens the live-usage index so it also serves the retention cursor.
--
-- Two queries read upload_record on a schedule:
--
--   quota sum   status = ACCEPTED AND purged_at IS NULL, summing size_bytes
--   purge scan  the same filter plus created_at < cutoff, ordered by
--               (created_at, id) so a candidate whose delete fails is stepped
--               over rather than re-read forever
--
-- V3's (status, purged_at, size_bytes) covers the first and only half-serves the
-- second: it has no created_at, so the scan reads and discards rows that were
-- purged long ago, and the share of those grows as the table ages. InnoDB
-- appends the primary key, so (status, purged_at, created_at) is really
-- (status, purged_at, created_at, id) -- exactly the cursor's ordering.
--
-- Keeping size_bytes last leaves the quota sum answered from the index alone,
-- so one index does both jobs and the old one is dropped rather than kept
-- alongside.
--
-- Portable DDL only: these migrations run against MySQL 8.4 in production and
-- H2 in MySQL mode under test.
ALTER TABLE upload_record DROP INDEX idx_upload_record_live;

CREATE INDEX idx_upload_record_live
    ON upload_record (status, purged_at, created_at, size_bytes);
