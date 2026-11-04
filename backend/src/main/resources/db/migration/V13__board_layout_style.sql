-- 思维导图排布风格：RADIAL 树形图（径向）/ LOGIC 逻辑图（左到右），按画布持久化
ALTER TABLE boards ADD COLUMN layout_style varchar(16) NOT NULL DEFAULT 'RADIAL';
ALTER TABLE boards ADD CONSTRAINT chk_boards_layout_style CHECK (layout_style IN ('RADIAL', 'LOGIC'));
