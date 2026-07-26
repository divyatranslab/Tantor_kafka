-- Existing installations may still contain the legacy fixed bootstrap account.
-- Preserve the row for referential compatibility, but make it unusable until an
-- operator explicitly provisions an identity through the supported IdP flow.
UPDATE users
SET is_active = false,
    password_hash = 'OPERATOR_PROVISIONING_REQUIRED'
WHERE id = '00000000-0000-0000-0000-000000000000'
  AND username = 'admin'
  AND auth_source = 'local';
