-- 树形图排列记忆：切到逻辑图前保存节点坐标尺寸与结构签名；
-- 切回树形图时结构未变则原样还原，变了则按树形图算法重排
ALTER TABLE boards ADD COLUMN radial_layout_json text;
