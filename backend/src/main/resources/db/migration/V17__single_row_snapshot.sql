-- 快照改为每画布单行：保存时原地覆盖，不再追加历史版本。
-- 先保留每张画布最新的一行（created_at 相同用 id 决胜），再建立唯一索引。
DELETE FROM board_snapshots b
USING board_snapshots keep
WHERE b.board_id = keep.board_id
  AND (b.created_at, b.id) < (keep.created_at, keep.id);

CREATE UNIQUE INDEX uq_board_snapshots_board ON board_snapshots(board_id);
