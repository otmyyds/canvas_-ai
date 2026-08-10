-- 允许连线连接到图形节点（shapes 表）而非仅卡片
-- 删除 from_card_id / to_card_id 对 cards 的外键，节点归属由应用层校验
ALTER TABLE board_edges DROP CONSTRAINT board_edges_from_card_id_fkey;
ALTER TABLE board_edges DROP CONSTRAINT board_edges_to_card_id_fkey;
