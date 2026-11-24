-- 画布封面：工作台卡片显示客户端生成的缩略图
ALTER TABLE boards ADD COLUMN cover_key text;
ALTER TABLE boards ADD COLUMN cover_mime varchar(64);
ALTER TABLE boards ADD COLUMN cover_version integer NOT NULL DEFAULT 0;
