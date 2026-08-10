import type { AiTask, AiTaskType, Board, BoardCard, BoardEdge, SourceFile } from "@/lib/types";

export const API_BASE = process.env.NEXT_PUBLIC_API_BASE ?? "http://localhost:8080";

// HTTP 头只能是 ISO-8859-1，中文名用 encodeURIComponent 后再传
export const DEMO_USER_HEADERS = {
  "X-User-Email": "demo@example.com",
  "X-User-Name": encodeURIComponent("演示用户")
};

/** 认证 token 存取（localStorage） */
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

function authHeaders(): Record<string, string> {
  const token = authToken.get();
  if (token) {
    return { Authorization: `Bearer ${token}` };
  }
  return DEMO_USER_HEADERS;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(),
      ...(init?.headers ?? {})
    }
  });

  if (response.status === 401) {
    authToken.set(null);
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

export const api = {
  listBoards: () => request<Board[]>("/api/boards"),
  createBoard: (title: string) => request<Board>("/api/boards", {
    method: "POST",
    body: JSON.stringify({ title })
  }),
  getBoard: (boardId: string) => request<Board>(`/api/boards/${boardId}`),
  renameBoard: (boardId: string, title: string) => request<Board>(`/api/boards/${boardId}`, {
    method: "PATCH",
    body: JSON.stringify({ title })
  }),
  deleteBoard: (boardId: string) => request<void>(`/api/boards/${boardId}`, { method: "DELETE" }),
  deleteCard: (cardId: string) => request<void>(`/api/cards/${cardId}`, { method: "DELETE" }),
  getBoardSnapshot: (boardId: string) => request<{ id: string; boardId: string; snapshotJson: string; version: number; createdAt: string } | null>(`/api/boards/${boardId}/snapshot`),
  listCards: (boardId: string) => request<BoardCard[]>(`/api/boards/${boardId}/cards`),
  updateCard: (cardId: string, body: Partial<Pick<BoardCard, "title" | "contentMarkdown" | "cardType">>) =>
    request<BoardCard>(`/api/cards/${cardId}`, {
      method: "PATCH",
      body: JSON.stringify({
        title: body.title,
        contentMarkdown: body.contentMarkdown,
        cardType: body.cardType
      })
    }),
  createCard: (boardId: string, body: Partial<BoardCard>) => request<BoardCard>(`/api/boards/${boardId}/cards`, {
    method: "POST",
    body: JSON.stringify({
      title: body.title ?? "未命名卡片",
      contentMarkdown: body.contentMarkdown ?? "",
      cardType: body.cardType ?? "NOTE",
      x: body.x ?? 120,
      y: body.y ?? 120,
      width: body.width ?? 320,
      height: body.height ?? 180
    })
  }),
  listEdges: (boardId: string) => request<BoardEdge[]>(`/api/boards/${boardId}/edges`),
  createEdge: (boardId: string, fromCardId: string, toCardId: string, label?: string) =>
    request<BoardEdge>(`/api/boards/${boardId}/edges`, {
      method: "POST",
      body: JSON.stringify({ fromCardId, toCardId, label })
    }),
  listTasks: (boardId: string) => request<AiTask[]>(`/api/boards/${boardId}/ai-tasks`),
  createTask: (boardId: string, type: AiTaskType, prompt: string) => request<AiTask>(`/api/boards/${boardId}/ai-tasks`, {
    method: "POST",
    body: JSON.stringify({ type, prompt })
  }),
  abortTask: (taskId: string) => request<AiTask>(`/api/ai-tasks/${taskId}/abort`, { method: "POST" }),
  listFiles: (boardId: string) => request<SourceFile[]>(`/api/boards/${boardId}/files`),
  deleteFile: (boardId: string, fileId: string) => request<void>(`/api/boards/${boardId}/files/${fileId}`, { method: "DELETE" }),
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
  saveSnapshot: (boardId: string, snapshotJson: string) =>
    request<{ id: string; boardId: string; snapshotJson: string; version: number; createdAt: string }>(
      `/api/boards/${boardId}/snapshot`,
      {
        method: "POST",
        body: JSON.stringify({ snapshotJson })
      }
    ),
  fileContentUrl: (boardId: string, fileId: string) => `${API_BASE}/api/boards/${boardId}/files/${fileId}/content`,
  register: (email: string, displayName: string, password: string) =>
    request<AuthResponse>("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ email, displayName, password })
    }),
  login: (email: string, password: string) =>
    request<AuthResponse>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ email, password })
    })
};

export type AuthUser = {
  id: string;
  email: string;
  displayName: string;
};

export type AuthResponse = {
  token: string;
  refreshToken: string;
  user: AuthUser;
};

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
