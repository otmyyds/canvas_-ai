-- 思维导图/流程图图形节点表
CREATE TABLE shapes (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    title varchar(255) NOT NULL,
    shape varchar(32) NOT NULL,
    level integer NOT NULL DEFAULT 0,
    x double precision NOT NULL,
    y double precision NOT NULL,
    width double precision NOT NULL,
    height double precision NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_shapes_board ON shapes(board_id, created_at ASC);
