import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { api, authRefresh, authToken, clearAuthSession } from "@/lib/api";

/** 构造一个只包含 request() 用到的字段的响应对象 */
function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: async () => body
  } as Response;
}

describe("api request 统一解包", () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    clearAuthSession();
  });

  it("成功响应返回 Result.data", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, { code: 200, message: "success", data: { id: "board-1", title: "画布" } })
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(api.getBoard("board-1")).resolves.toEqual({ id: "board-1", title: "画布" });
  });

  it("列表响应返回 Result.data 数组", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      jsonResponse(200, { code: 200, message: "success", data: [{ id: "s1" }] })
    ));

    await expect(api.listShapes("board-1")).resolves.toEqual([{ id: "s1" }]);
  });

  it("204 响应返回 undefined，不尝试解析空 body", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(204, null)));

    await expect(api.deleteBoard("board-1")).resolves.toBeUndefined();
  });

  it("失败响应优先读取 message，而不是旧字段 error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      jsonResponse(404, { code: 404, message: "画布不存在", data: null })
    ));

    await expect(api.getBoard("missing")).rejects.toThrow("画布不存在");
  });

  it("401 时用刷新令牌续期并重试一次，刷新响应同样解包 data", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(jsonResponse(401, { code: 401, message: "未登录", data: null }))
      .mockResolvedValueOnce(jsonResponse(200, {
        code: 200,
        message: "success",
        data: {
          token: "new-access",
          refreshToken: "new-refresh",
          user: { email: "alice@test.com", displayName: "Alice" }
        }
      }))
      .mockResolvedValueOnce(jsonResponse(200, {
        code: 200,
        message: "success",
        data: { id: "board-1", title: "画布" }
      }));
    vi.stubGlobal("fetch", fetchMock);
    authRefresh.set("old-refresh");

    await expect(api.getBoard("board-1")).resolves.toEqual({ id: "board-1", title: "画布" });

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(authToken.get()).toBe("new-access");
    expect(authRefresh.get()).toBe("new-refresh");
  });

  it("上传文件成功时返回 Result.data", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      jsonResponse(200, { code: 200, message: "success", data: { id: "file-1", filename: "a.txt" } })
    ));

    const file = new File(["abc"], "a.txt", { type: "text/plain" });
    await expect(api.uploadFile("board-1", file)).resolves.toEqual({ id: "file-1", filename: "a.txt" });
  });

  it("上传失败时读取 message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      jsonResponse(413, { code: 413, message: "上传文件过大", data: null })
    ));

    const file = new File(["abc"], "a.txt", { type: "text/plain" });
    await expect(api.uploadFile("board-1", file)).rejects.toThrow("上传文件过大");
  });
});
