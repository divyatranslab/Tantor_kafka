-- V42: Rename reserved keyword column "user" to "user_name" in kf_hosts

ALTER TABLE kf_hosts RENAME COLUMN "user" TO user_name;
