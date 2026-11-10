"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useState } from "react";
import { clsx } from "clsx";
import {
  ArrowRight,
  Clock,
  FilePlus2,
  Globe,
  Lock,
  LogOut,
  Plus,
  Search,
  Shapes,
  Trash2,
  User
} from "lucide-react";
import { api, authToken, clearAuthSession } from "@/lib/api";
import type { Board, BoardPage } from "@/lib/types";
import { Button, GhostButton, Input } from "@/components/ui";
import { ThemePicker } from "@/components/theme-picker";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { CreateBoardDialog } from "@/components/create-board-dialog";

/** 首页每页画布数量 */
const PAGE_SIZE = 9;

/** 画布卡片的更新时间：一周内显示相对时间，更早显示日期 */
function formatUpdated(value: string) {
  const date = new Date(value);
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000);
  if (minutes < 1) {
    return "刚刚";
  }
  if (minutes < 60) {
    return `${minutes} 分钟前`;
  }
  const hours = Math.floor(minutes / 60);
  if (hours < 24) {
    return `${hours} 小时前`;
  }
  const days = Math.floor(hours / 24);
  if (days < 7) {
    return `${days} 天前`;
  }
  return date.toLocaleDateString("zh-CN");
}

/**
 * 生成分页按钮序列：页数少时全部展示，多时保留首尾并用 null 表示省略号。
 */
function pageNumbers(current: number, total: number): (number | null)[] {
  if (total <= 7) {
    return Array.from({ length: total }, (_, index) => index + 1);
  }
  if (current <= 4) {
    return [1, 2, 3, 4, 5, null, total];
  }
  if (current >= total - 3) {
    return [1, null, total - 4, total - 3, total - 2, total - 1, total];
  }
  return [1, null, current - 1, current, current + 1, null, total];
}

export default function WorkspacePage() {
  const router = useRouter();
  const [pageData, setPageData] = useState<BoardPage>({
    items: [],
    page: 1,
    size: PAGE_SIZE,
    total: 0,
    totalPages: 0
  });
  const [page, setPage] = useState(1);
  const [query, setQuery] = useState("");
  const [keyword, setKeyword] = useState("");
  const [loading, setLoading] = useState(true);
  const [createOpen, setCreateOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const [pendingDelete, setPendingDelete] = useState<Board>();
  const [error, setError] = useState<string>();

  // 无 token 直接跳登录页（演示模式可手动登录）
  useEffect(() => {
    if (!authToken.get()) {
      router.replace("/login");
    }
  }, [router]);

  const loadBoards = useCallback(async (targetPage: number, search: string) => {
    setLoading(true);
    try {
      const data = await api.listBoards({
        page: targetPage,
        size: PAGE_SIZE,
        keyword: search || undefined
      });
      // 删除最后一页的最后一项后，回退到最后一个有内容的页
      if (data.items.length === 0 && data.total > 0 && targetPage > 1) {
        setPage(Math.max(1, data.totalPages));
        return;
      }
      setPageData(data);
      setError(undefined);
    } catch (err) {
      setError(err instanceof Error ? err.message : "加载画布失败");
    } finally {
      setLoading(false);
    }
  }, []);

  // 输入停顿 300ms 后再请求，避免每敲一个字都查一次
  useEffect(() => {
    const timer = setTimeout(() => {
      setKeyword(query.trim());
      setPage(1);
    }, 300);
    return () => clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    void loadBoards(page, keyword);
  }, [keyword, loadBoards, page]);

  function logout() {
    clearAuthSession();
    router.push("/login");
  }

  async function createBoard(title: string) {
    setCreating(true);
    setError(undefined);
    try {
      await api.createBoard(title);
      setCreateOpen(false);
      // 清空搜索并回到第一页，保证新画布可见
      setQuery("");
      setKeyword("");
      if (page === 1) {
        await loadBoards(1, "");
      } else {
        setPage(1);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建画布失败");
    } finally {
      setCreating(false);
    }
  }

  async function confirmDelete() {
    if (!pendingDelete) {
      return;
    }
    setDeleting(true);
    try {
      await api.deleteBoard(pendingDelete.id);
      setPendingDelete(undefined);
      await loadBoards(page, keyword);
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    } finally {
      setDeleting(false);
    }
  }

  return (
    <main className="min-h-screen bg-mist">
      <header className="sticky top-0 z-30 border-b border-ink/10 bg-white/85 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-6xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent text-white shadow-sm shadow-accent/30">
              <Shapes className="h-5 w-5" />
            </div>
            <div>
              <p className="text-base font-semibold text-ink">文档画布</p>
              <p className="text-xs text-ink/50">单人 AI 文档工作台</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <Link
              href="/app/profile"
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-ink/10 bg-white px-3 text-sm font-medium text-ink/65 shadow-sm transition hover:border-accent/40 hover:bg-accent/5 hover:text-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/25"
            >
              <User className="h-4 w-4" />
              个人中心
            </Link>
            <ThemePicker />
            <GhostButton onClick={logout} title="退出登录">
              <LogOut className="h-4 w-4" />
              退出
            </GhostButton>
          </div>
        </div>
      </header>

      <section className="mx-auto max-w-6xl px-6 py-8">
        <div className="rounded-2xl border border-ink/5 bg-white p-6 shadow-sm">
          <div className="flex flex-col gap-5 md:flex-row md:items-center md:justify-between">
            <div>
              <h1 className="text-xl font-semibold text-ink md:text-2xl">把想法画成结构</h1>
              <p className="mt-2 max-w-xl text-sm leading-6 text-ink/60">
                创建画布、上传资料，让 AI 生成思维导图与流程图。内容保存在你的私人工作区。
              </p>
            </div>
            <Button onClick={() => setCreateOpen(true)} className="w-full shrink-0 md:w-auto">
              <Plus className="h-4 w-4" />
              新建画布
            </Button>
          </div>
        </div>

        {error ? (
          <div className="mt-6 rounded-lg border border-coral/30 bg-coral/10 px-4 py-3 text-sm text-coral">
            {error}
          </div>
        ) : null}

        <div className="mt-8 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex items-center gap-2">
            <h2 className="text-lg font-semibold text-ink">我的画布</h2>
            <span className="rounded-full bg-ink/5 px-2 py-0.5 text-xs font-medium text-ink/50">
              {pageData.total}
            </span>
          </div>
          <div className="relative w-full sm:w-64">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink/35" />
            <Input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索画布标题"
              className="pl-9"
            />
          </div>
        </div>

        {loading ? (
          <div className="mt-5 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
            {Array.from({ length: 6 }, (_, index) => (
              <div key={index} className="h-48 animate-pulse rounded-xl border border-ink/10 bg-white" />
            ))}
          </div>
        ) : (
          <>
            <div className="mt-5 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
              {pageData.items.length === 0 ? (
                <div className="flex min-h-48 flex-col items-start justify-center rounded-xl border border-dashed border-ink/20 bg-white px-6 md:col-span-2 lg:col-span-3">
                  <FilePlus2 className="h-8 w-8 text-accent" />
                  <h3 className="mt-3 font-medium text-ink">
                    {keyword ? `没有匹配「${keyword}」的画布` : "还没有画布"}
                  </h3>
                  {keyword ? (
                    <p className="mt-1 text-sm text-ink/55">换个关键词试试，或清空搜索查看全部画布。</p>
                  ) : (
                    <p className="mt-1 text-sm text-ink/55">创建你的第一张画布，开始整理资料和思路。</p>
                  )}
                  {!keyword ? (
                    <Button onClick={() => setCreateOpen(true)} className="mt-4">
                      <Plus className="h-4 w-4" />
                      新建画布
                    </Button>
                  ) : null}
                </div>
              ) : null}

              {pageData.items.map((board) => (
                <article
                  key={board.id}
                  className="group flex flex-col rounded-xl border border-ink/10 bg-white p-5 shadow-sm transition hover:-translate-y-0.5 hover:border-accent/30 hover:shadow-soft"
                >
                  <Link
                    href={`/board/${board.id}`}
                    className="line-clamp-2 text-base font-semibold leading-6 text-ink transition hover:text-accent"
                  >
                    {board.title}
                  </Link>

                  <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink/45">
                    <span className="inline-flex items-center gap-1">
                      {board.visibility === "PRIVATE" ? (
                        <Lock className="h-3.5 w-3.5" />
                      ) : (
                        <Globe className="h-3.5 w-3.5" />
                      )}
                      {board.visibility === "PRIVATE" ? "私有画布" : "只读分享"}
                    </span>
                    <span className="inline-flex items-center gap-1">
                      <Clock className="h-3.5 w-3.5" />
                      更新于 {formatUpdated(board.updatedAt)}
                    </span>
                  </div>

                  <div className="mt-auto flex items-center justify-between gap-2 border-t border-ink/5 pt-4">
                    <button
                      type="button"
                      onClick={() => setPendingDelete(board)}
                      className="inline-flex h-8 items-center gap-1.5 rounded-lg px-2 text-xs text-ink/40 transition hover:bg-coral/10 hover:text-coral focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-coral/25"
                      title="删除画布"
                    >
                      <Trash2 className="h-4 w-4" />
                      删除
                    </button>
                    <Link
                      href={`/board/${board.id}`}
                      className="inline-flex h-9 items-center gap-1.5 rounded-lg bg-accent px-4 text-sm font-medium text-white shadow-sm transition hover:bg-accent/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/35 active:scale-[0.97]"
                    >
                      打开
                      <ArrowRight className="h-4 w-4" />
                    </Link>
                  </div>
                </article>
              ))}
            </div>

            {pageData.total > 0 && pageData.totalPages > 1 ? (
              <div className="mt-6 flex flex-col gap-3 border-t border-ink/10 pt-4 sm:flex-row sm:items-center sm:justify-between">
                <p className="text-xs text-ink/45">
                  第 {page} 页，共 {pageData.totalPages} 页 · {pageData.total} 张画布
                </p>
                <div className="flex items-center gap-1">
                  <GhostButton
                    className="h-8 px-2.5 text-xs"
                    disabled={loading || page <= 1}
                    onClick={() => setPage((current) => Math.max(1, current - 1))}
                  >
                    上一页
                  </GhostButton>
                  {pageNumbers(page, pageData.totalPages).map((item, index) =>
                    item === null ? (
                      <span key={`ellipsis-${index}`} className="px-1 text-xs text-ink/35">
                        ...
                      </span>
                    ) : (
                      <button
                        key={item}
                        type="button"
                        disabled={loading}
                        onClick={() => setPage(item)}
                        className={clsx(
                          "inline-flex h-8 min-w-8 items-center justify-center rounded-lg px-2 text-xs font-medium transition disabled:cursor-not-allowed",
                          item === page
                            ? "bg-accent text-white shadow-sm"
                            : "text-ink/55 hover:bg-ink/5 hover:text-ink"
                        )}
                      >
                        {item}
                      </button>
                    )
                  )}
                  <GhostButton
                    className="h-8 px-2.5 text-xs"
                    disabled={loading || page >= pageData.totalPages}
                    onClick={() => setPage((current) => current + 1)}
                  >
                    下一页
                  </GhostButton>
                </div>
              </div>
            ) : null}
          </>
        )}
      </section>

      <CreateBoardDialog
        open={createOpen}
        creating={creating}
        onClose={() => setCreateOpen(false)}
        onCreate={(title) => void createBoard(title)}
      />

      <ConfirmDialog
        open={Boolean(pendingDelete)}
        title="删除画布"
        description={`确定删除「${pendingDelete?.title ?? ""}」？画布内图形节点和连线将一并删除，不可恢复。`}
        confirmText="删除"
        danger
        loading={deleting}
        onConfirm={() => void confirmDelete()}
        onCancel={() => setPendingDelete(undefined)}
      />
    </main>
  );
}
