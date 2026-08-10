CREATE TABLE board_edges (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    from_card_id uuid NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    to_card_id uuid NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    label varchar(255),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_board_edges_board ON board_edges(board_id, created_at ASC);
