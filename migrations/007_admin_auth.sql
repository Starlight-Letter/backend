ALTER TABLE users ADD COLUMN totp_secret_ciphertext text;
ALTER TABLE users ADD COLUMN last_totp_step bigint NOT NULL DEFAULT -1;
ALTER TABLE sessions ADD COLUMN admin_verified_at timestamptz;

ALTER TABLE users ADD CONSTRAINT users_admin_totp_secret_check
  CHECK (role <> 'admin' OR totp_secret_ciphertext IS NOT NULL);
