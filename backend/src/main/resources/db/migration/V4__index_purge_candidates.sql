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
-- purged long ago, and the share of those grows as the table ages.
--
-- id is named explicitly, and its position is the point. InnoDB appends the
-- primary key to a secondary index, but it appends it *after* every column the
-- index already declares -- so (status, purged_at, created_at, size_bytes)
-- would really be (status, purged_at, created_at, size_bytes, id), and rows
-- sharing a created_at would be ordered by size_bytes rather than by id. That
-- is not the cursor's `ORDER BY created_at, id`. Naming id in fourth position
-- puts the ordering columns where the cursor needs them.
--
-- size_bytes stays in the index, last, so the quota sum is still answered from
-- the index alone. One index does both jobs and the old one is dropped rather
-- than kept alongside.
--
-- Portable DDL only: these migrations run against MySQL 8.4 in production and
-- H2 in MySQL mode under test.
ALTER TABLE upload_record DROP INDEX idx_upload_record_live;

CREATE INDEX idx_upload_record_live
    ON upload_record (status, purged_at, created_at, id, size_bytes);
