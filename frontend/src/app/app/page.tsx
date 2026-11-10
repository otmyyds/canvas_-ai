"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { FilePlus2, Loader2, LogOut, Plus, Search, Trash2 } from "lucide-react";
import { api, authToken, clearAuthSession } from "@/lib/api";
import type { Board } from "@/lib/types";
import { Button, GhostButton, Input } from "@/components/ui";
import { ThemePicker } from "@/components/theme-picker";

export default function WorkspacePage() {
  const router = useRouter();
  const [boards, setBoards] = useState<Board[]>([]);
  const [title, setTitle] = useState("我的 AI 文档画布");
  const [loading, setLoading] = useState(true);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string>();

  // 无 token 直接跳登录页（演示模式可手动登录）
  useEffect(() => {
    if (!authToken.get()) {
      router.replace("/login");
      return;
    }
  }, [router]);

  useEffect(() => {
    api
      .listBoards()
      .then(setBoards)
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, []);

  function logout() {
    clearAuthSession();
    router.push("/login");
  }

  async function deleteBoard(boardId: string) {
    if (!window.confirm("确定删除该画布？画布内图形节点和连线将一并删除，不可恢复。")) {
      return;
    }
    try {
      await api.deleteBoard(boardId);
      setBoards((items) => items.filter((item) => item.id !== boardId));
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  async function createBoard() {
    setCreating(true);
    setError(undefined);
    try {
      const board = await api.createBoard(title);
      setBoards((items) => [board, ...items]);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建画布失败");
    } finally {
      setCreating(false);
    }
  }

  return (
    <main className="min-h-screen bg-mist">
      <header className="border-b border-ink/10 bg-white">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6">
          <div>
            <h1 className="text-lg font-semibold text-ink">文档画布</h1>
            <p className="text-xs text-ink/55">单人 AI 文档工作台</p>
          </div>
          <div className="flex items-center gap-2">
            <div className="flex items-center gap-2 rounded-md border border-ink/10 bg-mist px-3 py-2 text-sm text-ink/60">
              <Search className="h-4 w-4" />
              <span>私人工作区</span>
            </div>
            <ThemePicker />
            <GhostButton onClick={logout} title="退出登录">
              <LogOut className="h-4 w-4" />
              退出
            </GhostButton>
          </div>
        </div>
      </header>

      <section className="mx-auto max-w-6xl px-6 py-8">
        <div className="flex flex-col gap-4 border-b border-ink/10 pb-6 md:flex-row md:items-end md:justify-between">
          <div>
            <h2 className="text-2xl font-semibold text-ink">工作台</h2>
            <p className="mt-2 max-w-2xl text-sm leading-6 text-ink/60">
              创建画布、上传资料、让 AI 生成图形结构。当前版本以单人私有内容为边界。
            </p>
          </div>
          <div className="flex w-full gap-2 md:w-[420px]">
            <Input
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder="输入画布标题"
            />
            <Button onClick={createBoard} disabled={creating}>
              {creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
              新建
            </Button>
          </div>
        </div>

        {error ? (
          <div className="mt-6 rounded-md border border-coral/30 bg-coral/10 px-4 py-3 text-sm text-coral">{error}</div>
        ) : null}

        <div className="mt-6 grid gap-3 md:grid-cols-2 lg:grid-cols-3">
          {loading ? (
            <div className="flex items-center gap-2 text-sm text-ink/60">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在加载画布
            </div>
          ) : null}

          {!loading && boards.length === 0 ? (
            <div className="flex min-h-44 flex-col items-start justify-center rounded-md border border-dashed border-ink/20 bg-white px-5">
              <FilePlus2 className="h-7 w-7 text-accent" />
              <h3 className="mt-3 font-medium text-ink">还没有画布</h3>
              <p className="mt-1 text-sm text-ink/55">先创建一个，再进入编辑器发起 AI 任务。</p>
            </div>
          ) : null}

          {boards.map((board) => (
            <div key={board.id} className="group relative rounded-md border border-ink/10 bg-white shadow-sm transition hover:border-accent/40 hover:shadow-soft">
              <Link
                href={`/board/${board.id}`}
                className="block p-5"
              >
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <h3 className="font-semibold text-ink">{board.title}</h3>
                    <p className="mt-2 text-sm text-ink/55">
                      {board.visibility === "PRIVATE" ? "私有画布" : "只读分享"}
                    </p>
                  </div>
                  <span className="rounded-md bg-accent/10 px-2 py-1 text-xs font-medium text-accent">打开</span>
                </div>
                <p className="mt-8 text-xs text-ink/45">更新于 {new Date(board.updatedAt).toLocaleString()}</p>
              </Link>
              <button
                type="button"
                onClick={() => void deleteBoard(board.id)}
                className="absolute right-2 top-2 hidden h-7 w-7 items-center justify-center rounded-md text-ink/40 transition hover:bg-coral/10 hover:text-coral group-hover:flex"
                title="删除画布"
              >
                <Trash2 className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      </section>
    </main>
  );
}
