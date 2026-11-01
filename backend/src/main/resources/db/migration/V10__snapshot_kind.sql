-- 快照表拆分「当前文档」与「历史版本」两层，替代原先无界 append：
-- CURRENT = 每画布仅 1 行的当前文档（自动保存覆盖写）
-- VERSION = 每画布最多 20 行的历史检查点（按 AI 任务边界/手动存档生成，可回退）

ALTER TABLE board_snapshots ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'VERSION';

-- 每画布最新 1 行转为当前文档（version 归 0），其余保留为版本
WITH ranked AS (
    SELECT id,
           row_number() OVER (PARTITION BY board_id ORDER BY created_at DESC, version DESC) AS rn
    FROM board_snapshots
)
UPDATE board_snapshots b
SET kind = 'CURRENT', version = 0
FROM ranked r
WHERE b.id = r.id AND r.rn = 1;

-- 每画布版本行最多保留 20 条，其余删除（1 CURRENT + 20 VERSION）
DELETE FROM board_snapshots b
USING (
    SELECT id,
           row_number() OVER (PARTITION BY board_id ORDER BY created_at DESC, version DESC) AS rn
    FROM board_snapshots
    WHERE kind = 'VERSION'
) r
WHERE b.id = r.id AND r.rn > 20;

-- 每画布至多一个当前文档（防并发插入重复）
CREATE UNIQUE INDEX uq_board_snapshots_current ON board_snapshots(board_id) WHERE kind = 'CURRENT';
