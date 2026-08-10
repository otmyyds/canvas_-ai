"use client";

import Link from "next/link";
import { useCallback, useEffect, useState } from "react";
import { ArrowLeft, Loader2 } from "lucide-react";
import { api } from "@/lib/api";
import type { AiTaskType, TaskEvent } from "@/lib/types";
import { useBoardStore } from "@/lib/use-board-store";
import { useTaskStream } from "@/lib/use-task-stream";
import { WhiteboardEditor } from "@/components/whiteboard/whiteboard-editor";
import { GhostButton } from "@/components/ui";

type BoardPageProps = {
  params: Promise<{ boardId: string }>;
};

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
  const { board, cards, edges, shapes, tasks, files, setBoard, setCards, setEdges, setShapes, setTasks, setFiles } = useBoardStore();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();
  const [canvasRevision, setCanvasRevision] = useState(0);
  const [editingTitle, setEditingTitle] = useState(false);
  const [titleDraft, setTitleDraft] = useState("");

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
    const [boardResult, cardResult, edgeResult, shapeResult, taskResult, fileResult] = await Promise.all([
      api.getBoard(boardId),
      api.listCards(boardId),
      api.listEdges(boardId),
      api.listShapes(boardId),
      api.listTasks(boardId),
      api.listFiles(boardId)
    ]);
    setBoard(boardResult);
    setCards(cardResult);
    setEdges(edgeResult);
    setShapes(shapeResult);
    setTasks(taskResult);
    setFiles(fileResult);
  }, [boardId, setBoard, setCards, setEdges, setShapes, setFiles, setTasks]);

  useEffect(() => {
    setLoading(true);
    loadAll()
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, [loadAll]);

  const onTaskEvent = useCallback(
    async (event: TaskEvent) => {
      if (event.boardId !== boardId) {
        return;
      }
      setTasks(await api.listTasks(boardId));
      if (event.type === "task.actions" || event.type === "task.done") {
        const [cardResult, edgeResult, shapeResult] = await Promise.all([
          api.listCards(boardId),
          api.listEdges(boardId),
          api.listShapes(boardId)
        ]);
        setCards(cardResult);
        setEdges(edgeResult);
        setShapes(shapeResult);
        setCanvasRevision((value) => value + 1);
      }
    },
    [boardId, setCards, setEdges, setTasks]
  );

  const connection = useTaskStream(boardId, onTaskEvent);

  async function createTask(type: AiTaskType, prompt: string) {
    await api.createTask(boardId, type, prompt);
    setTasks(await api.listTasks(boardId));
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

  async function applyTemplate(templateId: string, rootTitle: string) {
    const { findTemplate } = await import("@/lib/whiteboard/templates");
    const template = findTemplate(templateId);
    if (!template) {
      throw new Error("模板不存在");
    }
    const spec = template.build(rootTitle);
    const cardIdByNode = new Map<string, string>();
    for (const node of spec.nodes) {
      const created = await api.createCard(boardId, {
        title: node.kind === "card" ? node.title : node.id,
        contentMarkdown: node.kind === "card" ? node.markdown : "",
        cardType: node.kind === "card" ? mapCardTypeToBackend(node.cardType) : "NOTE",
        x: node.x,
        y: node.y,
        width: node.width,
        height: node.height
      });
      cardIdByNode.set(node.id, created.id);
    }
    for (const edge of spec.edges) {
      const from = cardIdByNode.get(edge.fromId);
      const to = cardIdByNode.get(edge.toId);
      if (from && to) {
        await api.createEdge(boardId, from, to, edge.label);
      }
    }
    await loadAll();
  }

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
                className="w-64 rounded border border-spruce/40 px-1 py-0.5 text-sm font-semibold text-ink outline-none"
              />
            ) : (
              <h1
                className="cursor-pointer text-sm font-semibold text-ink hover:text-spruce"
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
        <div className="text-xs text-ink/50">{cards.length} 张卡片 · {edges.length} 条连线</div>
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
          cards={cards}
          edges={edges}
          shapes={shapes}
          files={files}
          tasks={tasks}
          connection={connection}
          canvasRevision={canvasRevision}
          onRefresh={loadAll}
          onCreateTask={createTask}
          onAbortTask={abortTask}
          onUploadFile={uploadFile}
          onDeleteFile={deleteFile}
          onApplyTemplate={applyTemplate}
        />
      )}
    </main>
  );
}

function mapCardTypeToBackend(cardType: string) {
  switch (cardType) {
    case "summary":
      return "SUMMARY";
    case "idea":
      return "IDEA";
    case "source":
      return "SOURCE";
    case "question":
      return "QUESTION";
    case "note":
    default:
      return "NOTE";
  }
}
