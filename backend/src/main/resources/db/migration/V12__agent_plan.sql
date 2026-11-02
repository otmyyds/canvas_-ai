-- Agent 计划执行：任务步骤补齐计划信息，新增计划级画布检查点。
-- 只新增列与表，不改动现有数据；步骤顺序由 seq 决定，索引不唯一以兼容历史默认值 0。

ALTER TABLE ai_task_steps ADD COLUMN seq integer NOT NULL DEFAULT 0;
ALTER TABLE ai_task_steps ADD COLUMN step_type varchar(32);
ALTER TABLE ai_task_steps ADD COLUMN description text;
ALTER TABLE ai_task_steps ADD COLUMN brief text;
ALTER TABLE ai_task_steps ADD COLUMN result_message text;
ALTER TABLE ai_task_steps ADD COLUMN error_message varchar(1000);
CREATE INDEX idx_ai_task_steps_task_seq ON ai_task_steps(task_id, seq);

-- 计划开始前的画布检查点：shape/edge/asset 全量行 + tldraw 快照。
-- 每个计划任务一行，计划成功后删除；执行失败或中断时按原始 id 重建画布。
CREATE TABLE board_checkpoints (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    task_id uuid NOT NULL REFERENCES ai_tasks(id) ON DELETE CASCADE,
    state_json text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX idx_board_checkpoints_task ON board_checkpoints(task_id);
