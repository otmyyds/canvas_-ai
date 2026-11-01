-- Agent 运行底座：会话、运行轨迹、步骤、消息和每日用量。
-- ai_tasks 仍是任务状态唯一权威；agent_runs/agent_steps 只追加审计，不驱动状态机。

-- 会话：一个用户在一个画布下的持续对话
CREATE TABLE agent_sessions (
    id uuid PRIMARY KEY,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    title varchar(255),
    summary text,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_active_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_sessions_owner_board
    ON agent_sessions(owner_id, board_id, last_active_at DESC);

-- 运行：一次 Agent 执行（Supervisor / Worker / Researcher / Critic 各一条）
CREATE TABLE agent_runs (
    id uuid PRIMARY KEY,
    task_id uuid REFERENCES ai_tasks(id) ON DELETE CASCADE,
    session_id uuid REFERENCES agent_sessions(id) ON DELETE SET NULL,
    parent_run_id uuid REFERENCES agent_runs(id) ON DELETE SET NULL,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    role varchar(32) NOT NULL,
    branch_key varchar(128),
    status varchar(32) NOT NULL,
    model varchar(255),
    model_calls integer NOT NULL DEFAULT 0,
    tool_calls integer NOT NULL DEFAULT 0,
    prompt_tokens integer NOT NULL DEFAULT 0,
    completion_tokens integer NOT NULL DEFAULT 0,
    total_tokens integer NOT NULL DEFAULT 0,
    result_json text,
    error_message varchar(1000),
    started_at timestamptz,
    finished_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_runs_task ON agent_runs(task_id);
CREATE INDEX idx_agent_runs_owner_board ON agent_runs(owner_id, board_id, created_at DESC);
CREATE INDEX idx_agent_runs_parent ON agent_runs(parent_run_id);

-- 步骤：只追加，记录模型调用、工具调用、委派、审查等
CREATE TABLE agent_steps (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    seq integer NOT NULL,
    type varchar(32) NOT NULL,
    tool_name varchar(128),
    input_json jsonb,
    output_json jsonb,
    latency_ms integer,
    error_message varchar(1000),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (run_id, seq)
);
CREATE INDEX idx_agent_steps_run ON agent_steps(run_id, seq);

-- 消息：会话历史
CREATE TABLE agent_messages (
    id uuid PRIMARY KEY,
    session_id uuid NOT NULL REFERENCES agent_sessions(id) ON DELETE CASCADE,
    owner_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    board_id uuid NOT NULL REFERENCES boards(id) ON DELETE CASCADE,
    role varchar(32) NOT NULL,
    content text,
    tool_name varchar(128),
    tool_payload jsonb,
    task_id uuid REFERENCES ai_tasks(id) ON DELETE SET NULL,
    run_id uuid REFERENCES agent_runs(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_agent_messages_session ON agent_messages(session_id, created_at);

-- 每用户每日用量：配额和成本归属
CREATE TABLE user_ai_usage_daily (
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    usage_date date NOT NULL,
    task_count integer NOT NULL DEFAULT 0,
    model_calls integer NOT NULL DEFAULT 0,
    total_tokens bigint NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, usage_date)
);
