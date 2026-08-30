-- 删除卡片（cards）与卡片-资料关联表，画布统一为图形节点（shapes）
-- 连线端点字段去掉 card 命名（board_edges 现只连图形节点）
DROP TABLE card_source_files;
DROP TABLE cards;
ALTER TABLE board_edges RENAME COLUMN from_card_id TO from_shape_id;
ALTER TABLE board_edges RENAME COLUMN to_card_id TO to_shape_id;
