import type {
  AiTask,
  AssistResult,
  Board,
  BoardAsset,
  BoardEdge,
  BoardLayoutStyle,
  BoardPage,
  BoardShape,
  SharedBoard,
  SourceFile
} from "@/lib/types";

/**
 * 后端 API 基地址（来自 .env 的 NEXT_PUBLIC_API_BASE，默认 localhost:8080）。
 */
export const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

// HTTP 头只能是 ISO-8859-1，中文名用 encodeURIComponent 后再传
export const DEMO_USER_HEADERS = {
  "X-User-Email": "demo@example.com",
  "X-User-Name": encodeURIComponent("演示用户")
};

/**
 * 认证 token 存取（localStorage，key = "dc_token"）。
 * 有 token → 后续请求带 Authorization 头；无 token → 回退演示用户头。
 */
export const authToken = {
  get: () => (typeof window !== "undefined" ? window.localStorage.getItem("dc_token") : null),
  set: (token: string | null) => {
    if (typeof window === "undefined") {
      return;
    }
    if (token) {
      window.localStorage.setItem("dc_token", token);
    } else {
      window.localStorage.removeItem("dc_token");
    }
  }
};

/**
 * 刷新令牌 + 用户 id 存取（localStorage，key = "dc_refresh"）。
 * 用于访问令牌过期时静默续期（POST /api/auth/refresh）。
 */
export const authRefresh = {
  get: (): { refreshToken: string; userId: string } | null => {
    if (typeof window === "undefined") {
      return null;
    }
    const raw = window.localStorage.getItem("dc_refresh");
    if (!raw) {
      return null;
    }
    try {
      return JSON.parse(raw) as { refreshToken: string; userId: string };
    } catch {
      return null;
    }
  },
  set: (session: { refreshToken: string; userId: string } | null) => {
    if (typeof window === "undefined") {
      return;
    }
    if (session) {
      window.localStorage.setItem("dc_refresh", JSON.stringify(session));
    } else {
      window.localStorage.removeItem("dc_refresh");
    }
  }
};

/** 登录/注册/刷新后保存完整会话（访问令牌 + 刷新令牌 + 用户 id） */
export function saveAuthSession(result: AuthResponse) {
  authToken.set(result.token);
  authRefresh.set({ refreshToken: result.refreshToken, userId: result.user.id });
}

/** 清除会话（登出或续期失败） */
export function clearAuthSession() {
  authToken.set(null);
  authRefresh.set(null);
}

/**
 * 构建请求认证头。
 * 已登录 → Bearer <jwt>；未登录 → 演示用户头（X-User-Email，本地兼容）。
 */
function authHeaders(): Record<string, string> {
  const token = authToken.get();
  if (token) {
    return { Authorization: `Bearer ${token}` };
  }
  return DEMO_USER_HEADERS;
}

/**
 * 通用请求封装：
 * - 自动拼 API_BASE 前缀 + 统一认证头
 * - 401 → 用刷新令牌静默续期后重试一次，仍失败才清会话跳登录
 * - 非 2xx → 解析错误体并转中文错误信息
 * - 204 → 返回 undefined
 *
 * @param path 接口路径（以 /api 开头）
 * @param init fetch 配置（method/body/headers）
 * @returns 后端返回的 JSON 数据
 */
async function request<T>(path: string, init?: RequestInit): Promise<T> {
  let response = await doRequest(path, init);

  // 访问令牌过期：尝试用刷新令牌静默续期后重试一次
  if (response.status === 401 && (await refreshAccessSession())) {
    response = await doRequest(path, init);
  }

  if (response.status === 401) {
    clearAuthSession();
    if (typeof window !== "undefined") {
      window.location.href = "/login";
    }
    throw new Error("登录已过期，请重新登录");
  }

  if (!response.ok) {
    const body = await response.json().catch(() => ({ error: response.statusText }));
    throw new Error(toChineseError(body.error ?? `请求失败（${response.status}）`));
  }

  if (response.status === 204) {
    return undefined as T;
  }

  return response.json() as Promise<T>;
}

/** 发送一次请求（自动拼统一认证头，重试时会重新读取刷新后的 token） */
function doRequest(path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
      ...(init?.headers ?? {})
    }
  });
}

/** 用刷新令牌换取新访问令牌；成功返回 true，失败（无刷新令牌/接口报错）返回 false */
async function refreshAccessSession(): Promise<boolean> {
  const session = authRefresh.get();
  if (!session) {
    return false;
  }
  try {
    const response = await fetch(`${API_BASE}/api/auth/refresh`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ userId: session.userId, refreshToken: session.refreshToken })
    });
    if (!response.ok) {
      return false;
    }
    const result = (await response.json()) as AuthResponse;
    saveAuthSession(result);
    return true;
  } catch {
    return false;
  }
}

/**
 * 全部后端接口的封装对象。
 * 分组：画布 / 图形节点 / 连线 / AI 任务 / 文件 / 快照 / 聊天 / 认证
 */
export const api = {
  // ===== 画布 =====
  /** 分页列出当前用户的画布，keyword 按标题模糊匹配 */
  listBoards: (params?: { page?: number; size?: number; keyword?: string }) => {
    const search = new URLSearchParams();
    if (params?.page) {
      search.set("page", String(params.page));
    }
    if (params?.size) {
      search.set("size", String(params.size));
    }
    if (params?.keyword) {
      search.set("keyword", params.keyword);
    }
    const suffix = search.toString();
    return request<BoardPage>(`/api/boards${suffix ? `?${suffix}` : ""}`);
  },
  createBoard: (title: string) => request<Board>("/api/boards", {
    method: "POST",
    body: JSON.stringify({ title })
  }),
  getBoard: (boardId: string) => request<Board>(`/api/boards/${boardId}`),
  renameBoard: (boardId: string, title: string) => request<Board>(`/api/boards/${boardId}`, {
    method: "PATCH",
    body: JSON.stringify({ title })
  }),
  /** 切换思维导图排布风格，后端立即重排并返回最新画布 */
  updateBoardLayout: (boardId: string, layoutStyle: BoardLayoutStyle) =>
    request<Board>(`/api/boards/${boardId}/layout`, {
      method: "PATCH",
      body: JSON.stringify({ layoutStyle })
    }),
  /** 整理画布：按连通分量重排整张画布 */
  applyBoardLayout: (boardId: string) =>
    request<void>(`/api/boards/${boardId}/layout/apply`, { method: "POST" }),
  deleteBoard: (boardId: string) => request<void>(`/api/boards/${boardId}`, { method: "DELETE" }),

  // ===== 快照 =====
  getBoardSnapshot: (boardId: string) => request<{ id: string; boardId: string; snapshotJson: string; version: number; createdAt: string } | null>(`/api/boards/${boardId}/snapshot`),

  // ===== 图形节点（思维导图/流程图节点） =====
  listEdges: (boardId: string) => request<BoardEdge[]>(`/api/boards/${boardId}/edges`),
  listShapes: (boardId: string) => request<BoardShape[]>(`/api/boards/${boardId}/shapes`),
  /** 删除图形节点（白板删除元素时同步调用，避免刷新复活）；思维导图会连带删除子树与连线 */
  deleteShape: (shapeId: string) => request<void>(`/api/shapes/${shapeId}`, { method: "DELETE" }),
  /** 新建节点：parentId 为 null 时创建独立根节点，否则在父节点下新建子节点并重排所属分量 */
  createShape: (boardId: string, body: { parentId: string | null; title?: string }) =>
    request<BoardShape>(`/api/boards/${boardId}/shapes`, { method: "POST", body: JSON.stringify(body) }),
  /** 回写用户手动编辑（拖动位置/改标题/改尺寸），统一 tldraw 快照与 shapes 表 */
  updateShape: (
    shapeId: string,
    body: {
      title: string;
      x: number;
      y: number;
      width: number;
      height: number;
      /** 视觉属性；不传表示本次不改 */
      visual?: Record<string, string> | null;
    }
  ) => request<BoardShape>(`/api/shapes/${shapeId}`, { method: "PATCH", body: JSON.stringify(body) }),
  /** 调整某个节点的子节点顺序；逻辑图一级分支同时提交左右侧，后端重排该分量 */
  reorderChildren: (
    boardId: string,
    body: { parentId: string; children: { edgeId: string; side?: "LEFT" | "RIGHT" | null }[] }
  ) => request<BoardEdge[]>(`/api/boards/${boardId}/structure/order`, {
    method: "PATCH",
    body: JSON.stringify(body)
  }),
  /** 折叠/展开节点：折叠后后端重排该分量，前端隐藏其后代 */
  setCollapsed: (boardId: string, shapeId: string, collapsed: boolean) =>
    request<BoardShape>(`/api/boards/${boardId}/structure/collapse`, {
      method: "PATCH",
      body: JSON.stringify({ shapeId, collapsed })
    }),
  /** 纳管画布上手绘的图形与连线（原坐标尺寸落库，不触发布局） */
  adoptStructure: (
    boardId: string,
    body: {
      nodes: {
        localId: string;
        title: string;
        shape?: string;
        level?: number;
        x: number;
        y: number;
        width: number;
        height: number;
        visual?: Record<string, string>;
      }[];
      edges: { fromLocalId: string; toLocalId: string }[];
    }
  ) => request<{ nodes: { localId: string; shape: BoardShape }[]; edges: BoardEdge[] }>(
    `/api/boards/${boardId}/structure/adopt`,
    { method: "POST", body: JSON.stringify(body) }
  ),

  // ===== AI 任务 =====
  listTasks: (boardId: string) => request<AiTask[]>(`/api/boards/${boardId}/ai-tasks`),
  abortTask: (taskId: string) => request<AiTask>(`/api/ai-tasks/${taskId}/abort`, { method: "POST" }),

  // ===== 文件 =====
  listFiles: (boardId: string) => request<SourceFile[]>(`/api/boards/${boardId}/files`),
  deleteFile: (boardId: string, fileId: string) => request<void>(`/api/boards/${boardId}/files/${fileId}`, { method: "DELETE" }),
  /** 上传文件（multipart/form-data，需要带认证头但无 Content-Type，交给浏览器设置 boundary） */
  uploadFile: async (boardId: string, file: File) => {
    const form = new FormData();
    form.append("file", file);
    const response = await fetch(`${API_BASE}/api/boards/${boardId}/files`, {
      method: "POST",
      headers: {
        ...authHeaders()
      },
      body: form
    });
    if (!response.ok) {
      const body = await response.json().catch(() => ({ error: response.statusText }));
      throw new Error(toChineseError(body.error ?? `上传失败（${response.status}）`));
    }
    return response.json() as Promise<SourceFile>;
  },

  // ===== 快照 =====
  saveSnapshot: (boardId: string, snapshotJson: string) =>
    request<{ id: string; boardId: string; snapshotJson: string; version: number; createdAt: string }>(
      `/api/boards/${boardId}/snapshot`,
      {
        method: "POST",
        body: JSON.stringify({ snapshotJson })
      }
    ),
  /** 图片直链：<img src> 无法带 Authorization 头，用 query param 传 token */
  fileContentUrl: (boardId: string, fileId: string) => {
    const token = authToken.get();
    const base = `${API_BASE}/api/boards/${boardId}/files/${fileId}/content`;
    return token ? `${base}?token=${encodeURIComponent(token)}` : base;
  },

  // ===== AI 助手统一入口 =====
  /**
   * 统一提交到后端预检：返回 kind=reply 纯文本 / kind=task 后台任务。
   * selection 携带用户当前选中的图形节点真实 id，用于「编辑选中元素」任务。
   * sessionId 为空时后端新建会话，响应会带回会话 id 供后续多轮复用。
   */
  assist: (boardId: string, prompt: string, selection?: { shapeIds: string[] }, sessionId?: string) =>
    request<AssistResult>(`/api/boards/${boardId}/assist`, {
      method: "POST",
      body: JSON.stringify({
        prompt,
        selectedShapeIds: selection?.shapeIds ?? [],
        sessionId: sessionId ?? null
      })
    }),

  // ===== 分享 =====
  createShareLink: (boardId: string) => request<{ token: string }>(`/api/boards/${boardId}/share`, { method: "POST" }),
  getSharedBoard: (token: string) => request<SharedBoard>(`/api/share/${token}`),

  // ===== 资产（AI 生成图片） =====
  listAssets: (boardId: string) => request<BoardAsset[]>(`/api/boards/${boardId}/assets`),
  /** 删除图片资产（白板删除图片时同步调用，避免刷新复活） */
  deleteAsset: (boardId: string, assetId: string) => request<void>(`/api/boards/${boardId}/assets/${assetId}`, { method: "DELETE" }),
  /** 图片直链：<img src> 无法带 Authorization 头，用 query param 传 token */
  assetContentUrl: (boardId: string, assetId: string) => {
    const token = authToken.get();
    const base = `${API_BASE}/api/boards/${boardId}/assets/${assetId}/content`;
    return token ? `${base}?token=${encodeURIComponent(token)}` : base;
  },

  // ===== 认证 =====
  register: (email: string, displayName: string, password: string) =>
    request<AuthResponse>("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, displayName, password })
    }),
  login: (email: string, password: string) =>
    request<AuthResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    }),

  // ===== 用户 =====
  /** 当前登录用户的资料（含注册时间、最近更新、画布数量） */
  getProfile: () => request<UserProfile>("/api/users/me"),
  /** 修改昵称和邮箱，返回更新后的资料 */
  updateProfile: (body: { displayName: string; email: string }) =>
    request<UserProfile>("/api/users/me", { method: "PATCH", body: JSON.stringify(body) }),
  /** 修改密码：需要校验当前密码 */
  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    request<void>("/api/users/me/password", { method: "PATCH", body: JSON.stringify(body) })
};

/** 认证用户信息 */
export type AuthUser = {
  id: string;
  email: string;
  displayName: string;
};

/** 用户资料：users 表中可展示的字段 + 画布数量（不暴露内部用户 id） */
export type UserProfile = {
  email: string;
  displayName: string;
  createdAt: string;
  updatedAt: string;
  boardCount: number;
};

/** 认证响应：accessToken + refreshToken + 用户信息 */
export type AuthResponse = {
  token: string;
  refreshToken: string;
  user: AuthUser;
};

/**
 * 把后端返回的错误信息转为中文。
 * 兜底常见错误字符串（网络失败/HTTP 状态/Bean Validation 英文短语）。
 */
function toChineseError(message: string) {
  if (!message) {
    return "请求失败，请稍后重试";
  }
  const known: Record<string, string> = {
    "Failed to fetch": "无法连接后端服务，请确认后端已启动并刷新页面",
    "NetworkError when attempting to fetch resource.": "网络异常，请检查后端服务是否运行",
    "Create board failed": "创建画布失败",
    "Create task failed": "创建任务失败",
    "Upload failed": "上传失败",
    "Not Found": "资源不存在",
    "Internal Server Error": "服务器内部错误",
    "Bad Request": "请求参数有误",
    "Conflict": "当前操作冲突，请稍后重试",
    "must not be blank": "内容不能为空",
    "must not be null": "必填项不能为空",
    "must not be empty": "内容不能为空"
  };
  if (known[message]) {
    return known[message];
  }
  if (/non ISO-8859-1 code point/i.test(message)) {
    return "请求头包含非法字符，请刷新页面后重试";
  }
  if (/must not be blank/i.test(message)) {
    return "内容不能为空";
  }
  if (/must not be null/i.test(message)) {
    return "必填项不能为空";
  }
  if (/Failed to fetch/i.test(message)) {
    return "无法连接后端服务，请确认后端已启动并刷新页面";
  }
  return message;
}
