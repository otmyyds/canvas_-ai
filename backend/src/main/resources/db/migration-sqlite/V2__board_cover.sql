-- 画布封面：工作台卡片显示客户端生成的缩略图
alter table boards add column cover_key text;
alter table boards add column cover_mime varchar(64);
alter table boards add column cover_version integer not null default 0;
