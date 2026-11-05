-- 结构同类顺序与逻辑图一级分支左右侧
-- sort_order：同一父节点下子节点的顺序，布局按它排序；0 表示未手动排序，按创建时间兜底
-- side：仅逻辑图一级分支使用，LEFT/RIGHT；NULL 表示由布局按两侧高度自动分配
ALTER TABLE board_edges ADD COLUMN sort_order integer NOT NULL DEFAULT 0;
ALTER TABLE board_edges ADD COLUMN side varchar(8);

-- 纳管自由图形时保留 tldraw 的视觉属性（geo/color/fill/dash），切回树形图时还原
ALTER TABLE shapes ADD COLUMN visual_json text;
