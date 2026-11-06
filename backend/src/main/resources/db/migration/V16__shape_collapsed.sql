-- 节点折叠标记：折叠后该节点的全部后代不参与布局与画布渲染
ALTER TABLE shapes ADD COLUMN collapsed boolean NOT NULL DEFAULT false;
