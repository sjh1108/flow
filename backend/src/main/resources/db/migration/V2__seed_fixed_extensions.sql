-- The seven fixed extensions, seeded unblocked as the requirement specifies
-- ("default는 unCheck 상태").
--
-- Seeding lives in the migration rather than in application startup code on
-- purpose: the runtime database account has no INSERT privilege on this table,
-- so these rows can only ever be created by a migration.
INSERT INTO fixed_extension_state (extension, blocked, updated_at) VALUES
    ('bat', FALSE, CURRENT_TIMESTAMP(6)),
    ('cmd', FALSE, CURRENT_TIMESTAMP(6)),
    ('com', FALSE, CURRENT_TIMESTAMP(6)),
    ('cpl', FALSE, CURRENT_TIMESTAMP(6)),
    ('exe', FALSE, CURRENT_TIMESTAMP(6)),
    ('scr', FALSE, CURRENT_TIMESTAMP(6)),
    ('js',  FALSE, CURRENT_TIMESTAMP(6));
