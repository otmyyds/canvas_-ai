-- 本地轻量版 SQLite 初始表结构。
-- 只包含基础画布、图片资产和用户表；AI/RAG/Agent/分享/反馈表不在轻量版中出现。

create table if not exists users
(
    id            char(36)     primary key,
    email         varchar(255) not null unique,
    display_name  varchar(255) not null,
    password_hash varchar(255),
    created_at    timestamp    not null,
    updated_at    timestamp    not null
);

create table if not exists boards
(
    id                 char(36)     primary key,
    owner_id           char(36)     not null references users (id) on delete cascade,
    title              varchar(255) not null,
    visibility         varchar(32)  not null default 'PRIVATE',
    layout_style       varchar(16)  not null default 'RADIAL',
    radial_layout_json text,
    created_at         timestamp    not null,
    updated_at         timestamp    not null
);

create index if not exists idx_boards_owner on boards (owner_id, updated_at);

create table if not exists shapes
(
    id          char(36)     primary key,
    board_id    char(36)     not null references boards (id) on delete cascade,
    title       varchar(255) not null,
    shape       varchar(32)  not null,
    level       integer      not null default 0,
    x           real         not null,
    y           real         not null,
    width       real         not null,
    height      real         not null,
    visual_json text,
    collapsed   integer      not null default 0,
    created_at  timestamp    not null,
    updated_at  timestamp    not null
);

create index if not exists idx_shapes_board on shapes (board_id, created_at);

create table if not exists board_edges
(
    id            char(36)     primary key,
    board_id      char(36)     not null references boards (id) on delete cascade,
    from_shape_id char(36)     not null,
    to_shape_id   char(36)     not null,
    label         varchar(255),
    style         varchar(16),
    color         varchar(16),
    weight        integer      not null default 2,
    sort_order    integer      not null default 0,
    side          varchar(8),
    created_at    timestamp    not null,
    updated_at    timestamp    not null
);

create index if not exists idx_board_edges_board on board_edges (board_id, created_at);

create table if not exists board_snapshots
(
    id            char(36) primary key,
    board_id      char(36) not null unique references boards (id) on delete cascade,
    snapshot_json text     not null,
    version       integer  not null,
    created_at    timestamp not null,
    updated_at    timestamp not null
);

create table if not exists assets
(
    id              char(36)     primary key,
    owner_id        char(36)     not null references users (id) on delete cascade,
    board_id        char(36)     not null references boards (id) on delete cascade,
    source_task_id  char(36),
    storage_bucket  varchar(255) not null,
    storage_key     varchar(1024) not null,
    mime_type       varchar(255) not null,
    size            integer      not null,
    width           integer,
    height          integer,
    checksum        varchar(255),
    created_at      timestamp    not null,
    updated_at      timestamp    not null
);

create index if not exists idx_assets_board_owner on assets (board_id, owner_id);
