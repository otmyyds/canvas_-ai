CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id uuid PRIMARY KEY,
    email varchar(255) NOT NULL UNIQUE,
    display_name varchar(255) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE boards (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    title varchar(255) NOT NULL,
    visibility varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_boards_owner ON boards(owner_id, updated_at DESC);

CREATE TABLE board_snapshots (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    snapshot_json text NOT NULL,
    version integer NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX idx_board_snapshots_version ON board_snapshots(board_id, version);

CREATE TABLE cards (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    title varchar(255) NOT NULL,
    content_markdown text NOT NULL,
    card_type varchar(32) NOT NULL,
    x double precision NOT NULL,
    y double precision NOT NULL,
    width double precision NOT NULL,
    height double precision NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_cards_board ON cards(board_id, created_at ASC);

CREATE TABLE card_source_files (
    card_id uuid NOT NULL REFERENCES cards(id) ON DELETE CASCADE,
    source_file_id uuid NOT NULL
);

CREATE TABLE source_files (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES users(id),
    storage_key varchar(1024) NOT NULL,
    filename varchar(255) NOT NULL,
    mime_type varchar(255) NOT NULL,
    size bigint NOT NULL,
    text_excerpt text,
    parse_status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_source_files_board_owner ON source_files(board_id, owner_id, created_at DESC);

CREATE TABLE document_chunks (
    id uuid PRIMARY KEY,
    source_file_id uuid NOT NULL REFERENCES source_files(id) ON DELETE CASCADE,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES users(id),
    chunk_index integer NOT NULL,
    content text NOT NULL,
    embedding vector(1536),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_document_chunks_board_owner ON document_chunks(board_id, owner_id);

CREATE TABLE ai_tasks (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES users(id),
    type varchar(64) NOT NULL,
    prompt text NOT NULL,
    status varchar(32) NOT NULL,
    progress integer NOT NULL,
    current_step varchar(255),
    abort_requested boolean NOT NULL DEFAULT false,
    result_json text,
    error_message text,
    attempt_count integer NOT NULL DEFAULT 0,
    max_attempts integer NOT NULL DEFAULT 3,
    locked_by varchar(255),
    lock_expires_at timestamptz,
    idempotency_key varchar(255),
    last_heartbeat_at timestamptz,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_ai_tasks_board_owner ON ai_tasks(board_id, owner_id, created_at DESC);
CREATE INDEX idx_ai_tasks_status ON ai_tasks(status);

CREATE TABLE ai_task_steps (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES ai_tasks(id) ON DELETE CASCADE,
    title varchar(255) NOT NULL,
    status varchar(32) NOT NULL,
    progress integer NOT NULL,
    message text,
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_ai_task_steps_task ON ai_task_steps(task_id, created_at ASC);

CREATE TABLE ai_tool_calls (
    id uuid PRIMARY KEY,
    task_id uuid NOT NULL REFERENCES ai_tasks(id) ON DELETE CASCADE,
    tool_name varchar(255) NOT NULL,
    input_json text NOT NULL,
    output_json text,
    status varchar(32) NOT NULL,
    error_message text,
    duration_ms bigint NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE assets (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id),
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    source_task_id uuid REFERENCES ai_tasks(id) ON DELETE SET NULL,
    storage_bucket varchar(255) NOT NULL,
    storage_key varchar(1024) NOT NULL,
    mime_type varchar(255) NOT NULL,
    size bigint NOT NULL,
    width integer,
    height integer,
    checksum varchar(255),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX idx_assets_board_owner ON assets(board_id, owner_id);

CREATE TABLE share_links (
    id uuid PRIMARY KEY,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES users(id),
    token varchar(255) NOT NULL UNIQUE,
    permission varchar(32) NOT NULL,
    expires_at timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);
