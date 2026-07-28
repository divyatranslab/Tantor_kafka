-- Existing installations may still contain the legacy fixed bootstrap account.
-- V31 renamed users to kf_users, but tolerate installations that have not
-- completed that rename. Preserve the row for referential compatibility while
-- making the fixed account unusable.
DO $$
BEGIN
    IF to_regclass('public.kf_users') IS NOT NULL THEN
        UPDATE kf_users
        SET is_active = false,
            password_hash = 'OPERATOR_PROVISIONING_REQUIRED'
        WHERE id = '00000000-0000-0000-0000-000000000000'
          AND username = 'admin'
          AND auth_source = 'local';
    ELSIF to_regclass('public.users') IS NOT NULL THEN
        UPDATE users
        SET is_active = false,
            password_hash = 'OPERATOR_PROVISIONING_REQUIRED'
        WHERE id = '00000000-0000-0000-0000-000000000000'
          AND username = 'admin'
          AND auth_source = 'local';
    END IF;
END
$$;
