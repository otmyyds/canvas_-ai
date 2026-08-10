-- 连线样式：曲线/颜色/粗细
ALTER TABLE board_edges ADD COLUMN style varchar(16);
ALTER TABLE board_edges ADD COLUMN color varchar(16);
ALTER TABLE board_edges ADD COLUMN weight integer NOT NULL DEFAULT 2;
