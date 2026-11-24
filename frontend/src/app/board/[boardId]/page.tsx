"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";
import { ArrowLeft, Loader2, Share2 } from "lucide-react";
import { api, authUser } from "@/lib/api";
import type { BoardLayoutStyle, MetaFeatures, TaskEvent } from "@/lib/types";
import { useBoardStore } from "@/lib/use-board-store";
import { useTaskStream } from "@/lib/use-task-stream";
import { WhiteboardEditor } from "@/components/whiteboard/whiteboard-editor";
import { ThemePicker } from "@/components/theme-picker";
import { GhostButton } from "@/components/ui";

type BoardPageProps = {
  params: Promise<{ boardId: string }>;
};

/** 能力信息加载失败时按最保守的本地画布处理，避免误调已关闭的 AI/上传接口 */
const DEFAULT_FEATURES: MetaFeatures = {
  version: "1.0.0",
  aiEnabled: false,
  sourceUploadEnabled: false,
  imageUploadEnabled: true,
  shareEnabled: false,
  registrationEnabled: false,
  setupEnabled: false
};

/**
 * 每个用户在每个画布下保存一个持续会话 id，刷新后继续同一段对话。
 * key 带登录邮箱，避免同一浏览器切换账号后复用上一个用户的会话 id。
 */
const AGENT_SESSION_PREFIX = "dc_agent_session_";

function agentSessionKey(boardId: string): string {
  const account = authUser.get()?.email ?? "anonymous";
  return `${AGENT_SESSION_PREFIX}${account}_${boardId}`;
}

function loadAgentSessionId(boardId: string): string | undefined {
  if (typeof window === "undefined") {
    return undefined;
  }
  return window.localStorage.getItem(agentSessionKey(boardId)) ?? undefined;
}

function saveAgentSessionId(boardId: string, sessionId: string) {
  if (typeof window !== "undefined") {
    window.localStorage.setItem(agentSessionKey(boardId), sessionId);
  }
}

export default function BoardPage({ params }: BoardPageProps) {
  const [boardId, setBoardId] = useState<string>();

  useEffect(() => {
    params.then(({ boardId }) => setBoardId(boardId));
  }, [params]);

  if (!boardId) {
    return (
      <main className="flex min-h-screen items-center justify-center bg-mist text-ink/60">
        <Loader2 className="mr-2 h-4 w-4 animate-spin" />
        正在加载画布
      </main>
    );
  }

  return <BoardEditor boardId={boardId} />;
}

function BoardEditor({ boardId }: { boardId: string }) {
  const router = useRouter();
  const { board, edges, shapes, tasks, files, assets, setBoard, setEdges, setShapes, setTasks, setFiles, setAssets, upsertTask } = useBoardStore();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [features, setFeatures] = useState<MetaFeatures>(DEFAULT_FEATURES);
  const [leaving, setLeaving] = useState(false);
  const flushBeforeLeaveRef = useRef<(() => Promise<void>) | null>(null);
  const handleFlushReady = useCallback((flush: (() => Promise<void>) | null) => {
    flushBeforeLeaveRef.current = flush;
  }, []);
  const [canvasRevision, setCanvasRevision] = useState(0);
  // 清空画布信号：CLEAR_CANVAS 步骤完成时递增，驱动白板清空 tldraw 内容
  const [canvasClear, setCanvasClear] = useState(0);
  // 画布重载信号：任务失败/中断时后端已回退，递增后白板重新载入恢复出的快照
  const [canvasReload, setCanvasReload] = useState(0);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");
  // 当前选中的图形节点真实 id（用于「编辑选中元素」任务）
  const [selection, setSelection] = useState<{ shapeIds: string[] }>({ shapeIds: [] });
  // 当前画布的后端会话 id，用于多轮对话；首次请求由后端创建
  const sessionIdRef = useRef<string | undefined>(loadAgentSessionId(boardId));

  useEffect(() => {
    sessionIdRef.current = loadAgentSessionId(boardId);
  }, [boardId]);
  // 分享链接复制状态
  const [shareCopied, setShareCopied] = useState(false);

  async function share() {
    if (!features.shareEnabled) {
      return;
    }
    try {
      const { token } = await api.createShareLink(boardId);
      const url = `${window.location.origin}/share/${token}`;
      await navigator.clipboard.writeText(url);
      setShareCopied(true);
      setTimeout(() => setShareCopied(false), 2000);
    } catch (err) {
      setError(err instanceof Error ? err.message : "生成分享链接失败");
    }
  }

  /** 返回工作台前先保存快照并刷新封面，避免卡片封面滞后 */
  async function leaveBoard() {
    if (leaving) {
      return;
    }
    setLeaving(true);
    try {
      await flushBeforeLeaveRef.current?.();
    } finally {
      router.push("/app");
    }
  }

  async function renameBoard(title: string) {
    const safe = title.trim();
    if (!safe || safe === board?.title) {
      return;
    }
    const updated = await api.renameBoard(boardId, safe);
    setBoard(updated);
  }

  const loadAll = useCallback(async () => {
    setError(undefined);
    // 先拿运行时能力：lite 下不请求已关闭的任务/资料接口
    const meta = await api.getMeta().catch(() => DEFAULT_FEATURES);
    setFeatures(meta);
    const [boardResult, edgeResult, shapeResult, taskResult, fileResult, assetResult] = await Promise.all([
      api.getBoard(boardId),
      api.listEdges(boardId),
      api.listShapes(boardId),
      meta.aiEnabled ? api.listTasks(boardId) : Promise.resolve([]),
      meta.sourceUploadEnabled ? api.listFiles(boardId) : Promise.resolve([]),
      meta.imageUploadEnabled ? api.listAssets(boardId) : Promise.resolve([])
    ]);
    setBoard(boardResult);
    setEdges(edgeResult);
    setShapes(shapeResult);
    setTasks(taskResult);
    setFiles(fileResult);
    setAssets(assetResult);
  }, [boardId, setBoard, setEdges, setShapes, setFiles, setTasks, setAssets]);

  useEffect(() => {
    setLoading(true);
    loadAll()
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, [loadAll]);

  /**
   * 手动刷新：重新拉取后端数据后递增 canvasRevision。
   * 删除节点（后端会连带删子树并重排）与手动新建节点都靠这次完整同步把坐标/元素写回画布。
   */
  const refreshBoard = useCallback(async () => {
    await loadAll();
    setCanvasRevision((value) => value + 1);
  }, [loadAll]);

  const onTaskEvent = useCallback(
    async (event: TaskEvent) => {
      if (event.boardId !== boardId) {
        return;
      }
      setTasks(await api.listTasks(boardId));
      // 拉取画布三张表的最新数据，供步骤完成/任务收尾时同步到白板
      const refreshCanvas = async () => {
        const [edgeResult, shapeResult, assetResult] = await Promise.all([
          api.listEdges(boardId),
          api.listShapes(boardId),
          api.listAssets(boardId)
        ]);
        setEdges(edgeResult);
        setShapes(shapeResult);
        setAssets(assetResult);
      };
      if (event.type === "task.step") {
        // 每一步结束时立即刷新画布，让用户看到该步的真实效果
        if (event.status === "DONE" || event.status === "FAILED") {
          await refreshCanvas();
          setCanvasRevision((value) => value + 1);
          // 清空步骤完成：驱动白板清空 tldraw 中残留的元素
          if (event.status === "DONE" && event.stepType === "CLEAR_CANVAS") {
            setCanvasClear((value) => value + 1);
          }
        }
        return;
      }
      if (event.type === "task.actions" || event.type === "task.done") {
        await refreshCanvas();
        setCanvasRevision((value) => value + 1);
        return;
      }
      if (event.type === "task.failed" || event.type === "task.aborted") {
        // 失败/中断时后端已回退到计划开始前，拉取恢复后的数据并强制白板重载
        await refreshCanvas();
        setCanvasReload((value) => value + 1);
      }
    },
    [boardId, setEdges, setTasks, setAssets]
  );

  const connection = useTaskStream(boardId, onTaskEvent, features.aiEnabled);

  async function assist(prompt: string) {
    const result = await api.assist(boardId, prompt, selection, sessionIdRef.current);
    // 后端每次都会返回会话 id；首次请求后保存，后续多轮复用同一段记忆
    if (result.sessionId) {
      sessionIdRef.current = result.sessionId;
      saveAgentSessionId(boardId, result.sessionId);
    }
    if (result.kind === "task") {
      // 立即把新计划插入任务列表，任务卡不用等下一次事件刷新才出现
      upsertTask(result.task);
    }
    return result;
  }

  /**
   * 切换思维导图排布：后端立即重排，重新拉取节点后由 canvasRevision 同步回 tldraw。
   * 先取数据再一次性更新状态：提前 setBoard 会让 layoutStyle 先变、shapes/edges 还是旧的，
   * 白板会拿旧坐标按新类型同步一轮，表现为节点错位。
   */
  async function changeLayoutStyle(style: BoardLayoutStyle) {
    const updated = await api.updateBoardLayout(boardId, style);
    const [edgeResult, shapeResult] = await Promise.all([api.listEdges(boardId), api.listShapes(boardId)]);
    setEdges(edgeResult);
    setShapes(shapeResult);
    setBoard(updated);
    setCanvasRevision((value) => value + 1);
  }

  async function abortTask(taskId: string) {
    await api.abortTask(taskId);
    setTasks(await api.listTasks(boardId));
  }

  async function uploadFile(file: File) {
    await api.uploadFile(boardId, file);
    setFiles(await api.listFiles(boardId));
  }

  async function deleteFile(fileId: string) {
    await api.deleteFile(boardId, fileId);
    setFiles(await api.listFiles(boardId));
  }

  return (
    <main className="flex h-screen min-h-0 flex-col bg-mist">
      <header className="flex h-14 shrink-0 items-center justify-between border-b border-ink/10 bg-white px-4">
        <div className="flex items-center gap-3">
          <GhostButton onClick={() => void leaveBoard()} disabled={leaving}>
            <ArrowLeft className="h-4 w-4" />
            {leaving ? "保存中" : "返回"}
          </GhostButton>
          <div>
            {editingTitle ? (
              <input
                autoFocus
                value={titleDraft}
                onChange={(event) => setTitleDraft(event.target.value)}
                onBlur={() => {
                  setEditingTitle(false);
                  void renameBoard(titleDraft);
                }}
                onKeyDown={(event) => {
                  if (event.key === "Enter") {
                    (event.target as HTMLInputElement).blur();
                  }
                  if (event.key === "Escape") {
                    setEditingTitle(false);
                  }
                }}
                className="w-64 rounded border border-accent/40 px-1 py-0.5 text-sm font-semibold text-ink outline-none"
              />
            ) : (
              <h1
                className="cursor-pointer text-sm font-semibold text-ink hover:text-accent"
                title="点击重命名"
                onClick={() => {
                  setTitleDraft(board?.title ?? "");
                  setEditingTitle(true);
                }}
              >
                {board?.title ?? "文档画布"}
              </h1>
            )}
            <p className="text-xs text-ink/50">单人私有画布 · AI 任务实时进度</p>
          </div>
        </div>
        <div className="flex items-center gap-3">
          <ThemePicker />
          {features.shareEnabled ? (
            <GhostButton onClick={() => void share()} title="生成只读分享链接">
              <Share2 className="h-4 w-4" />
              {shareCopied ? "已复制" : "分享"}
            </GhostButton>
          ) : null}
          <div className="text-xs text-ink/50">{shapes.length} 个节点 · {edges.length} 条连线</div>
        </div>
      </header>

      {error ? (
        <div className="border-b border-coral/20 bg-coral/10 px-4 py-2 text-sm text-coral">{error}</div>
      ) : null}

      {loading ? (
        <div className="flex flex-1 items-center justify-center text-sm text-ink/55">
          <Loader2 className="mr-2 h-4 w-4 animate-spin" />
          正在加载工作区
        </div>
      ) : (
        <WhiteboardEditor
          boardId={boardId}
          boardTitle={board?.title}
          edges={edges}
          shapes={shapes}
          assets={assets}
          files={files}
          tasks={tasks}
          connection={connection}
          canvasRevision={canvasRevision}
          clearSignal={canvasClear}
          reloadSignal={canvasReload}
          onRefresh={refreshBoard}
          onAssist={assist}
          onAbortTask={abortTask}
          onUploadFile={uploadFile}
          onDeleteFile={deleteFile}
          onSelectionChange={setSelection}
          selectionCount={selection.shapeIds.length}
          layoutStyle={board?.layoutStyle}
          radialMaxNodes={board?.radialMaxNodes}
          onLayoutStyleChange={changeLayoutStyle}
          aiEnabled={features.aiEnabled}
          sourceUploadEnabled={features.sourceUploadEnabled}
          imageUploadEnabled={features.imageUploadEnabled}
          onFlushReady={handleFlushReady}
        />
      )}
    </main>
  );
}
