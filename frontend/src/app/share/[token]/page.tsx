"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Tldraw, createTLStore, defaultBindingUtils, defaultShapeUtils, parseTldrawJsonFile } from "tldraw";
import type { Editor as TldrawEditor } from "@tldraw/editor";
import { ArrowLeft, Loader2, Lock } from "lucide-react";
import { api } from "@/lib/api";
import type { SharedBoard } from "@/lib/types";
import { mergeMissingShapes, syncExistingShapes } from "@/lib/whiteboard/shape-render";
import { GhostButton } from "@/components/ui";

/**
 * 分享画布只读页：凭 /share/{token} 无需登录查看画布快照 + 图形节点。
 * tldraw 以只读模式渲染（隐藏 UI + isReadonly），不可编辑。
 */
export default function SharePage({ params }: { params: Promise<{ token: string }> }) {
  const [token, setToken] = useState<string>();
  const [board, setBoard] = useState<SharedBoard>();
  const [error, setError] = useState<string>();

  useEffect(() => {
    params.then(({ token }) => setToken(token));
  }, [params]);

  useEffect(() => {
    if (!token) {
      return;
    }
    api
      .getSharedBoard(token)
      .then(setBoard)
      .catch((err: Error) => setError(err.message));
  }, [token]);

  return (
    <main className="flex h-screen min-h-0 flex-col bg-mist">
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-ink/10 bg-white px-4">
        <div className="flex items-center gap-3">
          <Link href="/app">
            <GhostButton>
              <ArrowLeft className="h-4 w-4" />
              返回
            </GhostButton>
          </Link>
          <div>
            <h1 className="text-sm font-semibold text-ink">{board?.title ?? "分享画布"}</h1>
            <p className="text-xs text-ink/50">只读分享 · 无法编辑</p>
          </div>
        </div>
        <Lock className="h-4 w-4 text-ink/40" />
      </header>

      {error ? (
        <div className="flex flex-1 items-center justify-center text-sm text-ink/60">{error}</div>
      ) : !board ? (
        <div className="flex flex-1 items-center justify-center text-sm text-ink/60">
          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
          正在加载分享画布
        </div>
      ) : (
        <ReadonlyCanvas board={board} />
      )}
    </main>
  );
}

function ReadonlyCanvas({ board }: { board: SharedBoard }) {
  const store = useMemo(
    () =>
      createTLStore({
        shapeUtils: defaultShapeUtils,
        bindingUtils: defaultBindingUtils,
        defaultName: board.title
      }),
    [board]
  );

  const onMount = useCallback(
    (editor: TldrawEditor) => {
      if (board.snapshotJson) {
        const parsed = parseTldrawJsonFile({ json: board.snapshotJson, schema: editor.store.schema });
        if (parsed.ok) {
          editor.loadSnapshot(parsed.value.getStoreSnapshot());
        }
      }
      mergeMissingShapes(editor, board.shapes, board.edges);
      // 快照里的标题可能落后于后端（AI 改过标题但所有者还没重新保存快照），按后端数据对账
      syncExistingShapes(editor, board.shapes);
      editor.clearHistory();
      editor.updateInstanceState({ isReadonly: true });
      editor.zoomToFit();
    },
    [board]
  );

  return (
    <div className="relative min-h-0 flex-1 overflow-hidden bg-[#eef4f1]">
      <Tldraw store={store} onMount={onMount} hideUi autoFocus={false} className="h-full w-full" />
    </div>
  );
}
