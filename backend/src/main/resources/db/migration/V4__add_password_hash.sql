-- 认证：users 表增加密码哈希列
ALTER TABLE users ADD COLUMN password_hash varchar(255);
