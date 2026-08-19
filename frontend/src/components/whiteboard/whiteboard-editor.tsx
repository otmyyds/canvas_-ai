"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  AssetRecordType,
  Tldraw,
  createBindingId,
  createShapeId,
  createTLStore,
  defaultBindingUtils,
  defaultShapeUtils,
  parseTldrawJsonFile,
  serializeTldrawJson,
  toRichText
} from "tldraw";
import type { Editor as TldrawEditor } from "@tldraw/editor";
import {
  ArrowRight,
  Download,
  FileImage,
  FolderOpen,
  Hand,
  LayoutGrid,
  LayoutTemplate,
  Minus,
  MousePointer2,
  Plus,
  RefreshCw,
  Save,
  Shield,
  Square,
  Text,
  Trash2
} from "lucide-react";
import type { AiTask, AssistResult, BoardCard, BoardEdge, BoardShape, SourceFile } from "@/lib/types";
import { api } from "@/lib/api";
import { GhostButton, IconButton } from "@/components/ui";
import { SourcePanel } from "@/components/board/source-panel";
import { TaskPanel } from "@/components/board/task-panel";
import { ElementInspector } from "@/components/whiteboard/element-inspector";
import { whiteboardTemplates } from "@/lib/whiteboard/templates";
import { CardShapeUtil } from "@/lib/whiteboard/card-shape-util";

const CARD_SHAPE_UTILS = [...defaultShapeUtils, CardShapeUtil];

type WhiteboardEditorProps = {
  boardId: string;
  boardTitle?: string;
  cards: BoardCard[];
  edges: BoardEdge[];
  shapes: BoardShape[];
  files: SourceFile[];
  tasks: AiTask[];
  connection: string;
  canvasRevision?: number;
  /** 清空画布信号：CLEAR_CANVAS 任务完成时递增，触发白板清空 tldraw 元素 */
  clearSignal?: number;
  onRefresh: () => Promise<void>;
  onAssist: (prompt: string) => Promise<AssistResult>;
  onAbortTask: (taskId: string) => Promise<void>;
  onUploadFile: (file: File) => Promise<void>;
  onDeleteFile: (fileId: string) => Promise<void>;
  onApplyTemplate: (templateId: string, rootTitle: string) => Promise<void>;
};

type SaveStatus = "idle" | "saving" | "saved" | "error";

const SHAPE_TYPES: Array<{ value: string; label: string; icon: string }> = [
  { value: "rectangle", label: "矩形", icon: "▭" },
  { value: "ellipse", label: "椭圆", icon: "◯" },
  { value: "triangle", label: "三角", icon: "△" },
  { value: "diamond", label: "菱形", icon: "◇" },
  { value: "pentagon", label: "五边", icon: "⬠" },
  { value: "hexagon", label: "六边", icon: "⬡" },
  { value: "octagon", label: "八边", icon: "⏢" },
  { value: "star", label: "星形", icon: "★" },
  { value: "cloud", label: "云朵", icon: "☁" },
  { value: "heart", label: "心形", icon: "♥" },
  { value: "rhombus", label: "梯形", icon: "⯁" },
  { value: "oval", label: "胶囊", icon: "⭘" }
];

/**
 * 主编辑器组件：tldraw 无限画布 + 白板交互 + 数据同步。
 *
 * 职责：
 * 1. 初始化 tldraw store（含自定义 card shape + 图片上传到 RustFS）
 * 2. 加载画布快照 + 后端卡片/图形/连线 → 渲染到画布（hydration）
 * 3. 监听画布变更 → 防抖保存 snapshot
 * 4. 监听 AI 任务完成 → 增量同步新卡片/图形节点
 * 5. 工具栏：选择/平移/卡片/文本/图形/线条/箭头/图片/删除/刷新/整理/模板/导出
 *
 * 双状态源协调：
 * - 用户编辑 → 写 board_snapshots（tldraw 快照）
 * - AI 生成 → 写 cards/shapes/edges 表 → 前端 merge 进 tldraw
 * - 删除时同步删后端（deleteCard/deleteShape），避免刷新复活
 *
 * @param boardId 画布 id
 * @param cards 后端卡片列表（AI 生成/手动创建）
 * @param edges 后端连线列表
 * @param shapes 后端图形节点列表（思维导图/流程图节点）
 * @param canvasRevision AI 任务完成后递增，触发增量同步 effect
 */
export function WhiteboardEditor({
  boardId,
  boardTitle,
  cards,
  edges,
  shapes,
  files,
  tasks,
  connection,
  canvasRevision = 0,
  clearSignal = 0,
  onRefresh,
  onAssist,
  onAbortTask,
  onUploadFile,
  onDeleteFile,
  onApplyTemplate
}: WhiteboardEditorProps) {
  /**
   * tldraw store：画布的底层数据源。
   * - 自定义 shape utils（CardShapeUtil 卡片 + 默认图形）
   * - 图片资产上传到后端（RustFS）
   * 注意：useMemo 保证 store 只创建一次（随 boardId 变化重建）
   */
  const store = useMemo(
    () =>
      createTLStore({
        shapeUtils: CARD_SHAPE_UTILS,
        bindingUtils: defaultBindingUtils,
        assets: {
          async upload(_asset, file) {
            const uploaded = await api.uploadFile(boardId, file);
            return {
              src: api.fileContentUrl(boardId, uploaded.id),
              meta: {
                boardId,
                fileId: uploaded.id
              }
            };
          },
          resolve(asset) {
            return asset.props.src ?? null;
          }
        },
        defaultName: boardTitle ?? "文档画布"
      }),
    [boardId, boardTitle]
  );

  const editorRef = useRef<TldrawEditor | null>(null);
  const saveTimerRef = useRef<number | null>(null);
  const mountedRef = useRef(false);
  const hydratedBoardRef = useRef<string | null>(null);
  const knownCardIdsRef = useRef<Set<string>>(new Set());
  // 已删除卡片的墓碑，供 Ctrl+Z 恢复时重建后端记录
  const deletedCardsRef = useRef<
    Array<{ cardId: string; shape: { x: number; y: number; props?: Record<string, unknown> } }>
  >([]);
  const cardsRef = useRef(cards);
  const edgesRef = useRef(edges);
  const shapesRef = useRef(shapes);
  const filesRef = useRef(files);
  const [editorReady, setEditorReady] = useState(false);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("idle");
  const [message, setMessage] = useState("滚轮/中键拖动平移画布，滚轮缩放已改为平移；选中元素按 Delete 删除");

  cardsRef.current = cards;
  edgesRef.current = edges;
  shapesRef.current = shapes;
  filesRef.current = files;

  const saveSnapshot = useCallback(async () => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }

    try {
      const snapshotJson = await serializeTldrawJson(editor);
      await api.saveSnapshot(boardId, snapshotJson);
      setSaveStatus("saved");
      setMessage("画布已保存");
    } catch {
      setSaveStatus("error");
      setMessage("画布保存失败，请稍后重试");
    }
  }, [boardId]);

  const queueSave = useCallback(() => {
    if (saveTimerRef.current) {
      window.clearTimeout(saveTimerRef.current);
    }
    setSaveStatus("saving");
    saveTimerRef.current = window.setTimeout(() => {
      void saveSnapshot();
    }, 1000);
  }, [saveSnapshot]);

  useEffect(() => {
    if (!editorReady) {
      return;
    }

    // 切换画布时重置 hydration 状态
    if (hydratedBoardRef.current !== boardId) {
      knownCardIdsRef.current = new Set();
    } else {
      return;
    }

    let cancelled = false;
    const initialize = async () => {
      const editor = editorRef.current;
      if (!editor) {
        return;
      }

      mountedRef.current = false;
      const snapshot = await api.getBoardSnapshot(boardId).catch(() => null);
      if (cancelled) {
        return;
      }

      const currentCards = cardsRef.current;
      const currentEdges = edgesRef.current;
      const currentShapes = shapesRef.current;
      const currentFiles = filesRef.current;

      if (snapshot?.snapshotJson) {
        const parsed = parseTldrawJsonFile({
          json: snapshot.snapshotJson,
          schema: editor.store.schema
        });
        if (parsed.ok) {
          editor.loadSnapshot(parsed.value.getStoreSnapshot());
          const migrated = migrateLegacyCardShapes(editor, currentCards);
          const added = mergeMissingCards(editor, {
            boardId,
            cards: currentCards,
            edges: currentEdges,
            files: currentFiles
          });
          const shapeAdded = mergeMissingShapes(editor, currentShapes, currentEdges);
          knownCardIdsRef.current = new Set(currentCards.map((card) => card.id));
          editor.clearHistory();
          editor.selectNone();
          hydratedBoardRef.current = boardId;
          mountedRef.current = true;
          if (migrated > 0 || added > 0 || shapeAdded > 0) {
            setMessage(`已载入画布快照，迁移 ${migrated} 张旧卡片，同步 ${added} 张新卡片、${shapeAdded} 个节点`);
            queueSave();
          } else {
            setMessage("已载入画布快照");
          }
          return;
        }
      }

      await loadLegacyContent(editor, {
        boardId,
        cards: currentCards,
        edges: currentEdges,
        files: currentFiles,
        boardTitle
      });
      mergeMissingShapes(editor, currentShapes, currentEdges);
      knownCardIdsRef.current = new Set(currentCards.map((card) => card.id));
      editor.clearHistory();
      editor.selectNone();
      hydratedBoardRef.current = boardId;
      mountedRef.current = true;
      setMessage("已载入原始内容");
    };

    void initialize();

    return () => {
      cancelled = true;
    };
  }, [boardId, boardTitle, editorReady, queueSave]);

  // AI 任务完成后把后端 cards 增量同步进 tldraw
  useEffect(() => {
    if (!editorReady || hydratedBoardRef.current !== boardId || !mountedRef.current) {
      return;
    }

    const editor = editorRef.current;
    if (!editor) {
      return;
    }

    // 增量同步卡片
    const known = knownCardIdsRef.current;
    const missing = cards.filter((card) => !known.has(card.id));
    if (missing.length > 0) {
      const added = mergeMissingCards(editor, {
        boardId,
        cards: missing,
        edges,
        files
      });
      if (added > 0) {
        setMessage(`已同步 ${added} 张 AI 生成卡片到画布`);
        queueSave();
      }
    }
    knownCardIdsRef.current = new Set(cards.map((card) => card.id));

    // 增量同步图形节点（思维导图/流程图节点）
    const shapeIds = new Set(editor.getCurrentPageShapes().map((s) => s.id));
    const newShapes = shapes.filter((s) => !shapeIds.has(createShapeId(s.id)));
    if (newShapes.length > 0) {
      mergeMissingShapes(editor, newShapes, edges);
      setMessage(`已同步 ${newShapes.length} 个图形节点到画布`);
      queueSave();
    }
  }, [boardId, canvasRevision, cards, edges, editorReady, files, queueSave, shapes]);

  // 清空画布：CLEAR_CANVAS 任务完成时清空 tldraw 全部元素，并保存空快照
  useEffect(() => {
    if (clearSignal <= 0 || !editorReady) {
      return;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const shapeIds = [...editor.getCurrentPageShapeIds()];
    if (shapeIds.length > 0) {
      editor.deleteShapes(shapeIds);
    }
    editor.clearHistory();
    knownCardIdsRef.current = new Set();
    deletedCardsRef.current = [];
    setMessage("画布已清空");
    queueSave();
  }, [clearSignal, editorReady, queueSave]);

  useEffect(() => {
    const unlisten = store.listen((entry) => {
      if (!mountedRef.current) {
        return;
      }
      // 检测 undo 恢复：被删卡片的 shape 重新出现 → 重建后端卡片
      const added = entry?.changes?.added;
      const addedRecords = added ? Object.values(added) : [];
      if (addedRecords.length > 0) {
        const tombstone = deletedCardsRef.current;
        if (tombstone.length > 0) {
          for (const record of addedRecords) {
            if (record.typeName !== "shape") {
              continue;
            }
            const shape = record as { id: string; type: string; meta?: Record<string, unknown> };
            const cardId = (shape.meta?.cardId as string | undefined) ?? shape.id;
            const idx = tombstone.findIndex((item) => item.cardId === cardId);
            if (idx >= 0 && shape.type === "card") {
              const item = tombstone.splice(idx, 1)[0];
              const props = item.shape.props ?? {};
              void api
                .createCard(boardId, {
                  title: (props.title as string) ?? "未命名卡片",
                  contentMarkdown: (props.markdown as string) ?? "",
                  cardType: ((props.cardType as string) ?? "NOTE") as BoardCard["cardType"],
                  x: item.shape.x,
                  y: item.shape.y,
                  width: (props.w as number) ?? 340,
                  height: (props.h as number) ?? 220
                })
                .then(() => {
                  knownCardIdsRef.current.add(cardId);
                })
                .catch(() => {
                  // 重建失败静默
                });
            }
          }
        }
      }
      queueSave();
    }, { scope: "document" });

    return () => {
      unlisten();
      if (saveTimerRef.current) {
        window.clearTimeout(saveTimerRef.current);
      }
    };
  }, [boardId, queueSave, store]);

  const onMount = useCallback((editor: TldrawEditor) => {
    editorRef.current = editor;
    // 让画布获得焦点，保证 Delete/空格 快捷键首击即生效
    editor.focus();
    // 滚轮直接平移画布（默认是缩放），配合手工具/中键拖动
    editor.setCameraOptions({ wheelBehavior: "pan" });
    setEditorReady(true);
  }, []);

  const setTool = useCallback((toolId: string, hint: string) => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    editor.setCurrentTool(toolId);
    setMessage(hint);
  }, []);

  const addNote = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const id = createShapeId();
    const base = viewportPlacement(editor, editor.getCurrentPageShapes().length);
    editor.createShape({
      id,
      type: "card",
      x: base.x,
      y: base.y,
      props: {
        w: 340,
        h: 220,
        title: "新建卡片",
        markdown: "在这里输入内容，支持 **Markdown** 语法",
        cardType: "NOTE"
      }
    });
    editor.select(id);
    setMessage("已添加卡片");
  }, []);

  const addText = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const id = createShapeId();
    const base = viewportPlacement(editor, editor.getCurrentPageShapes().length);
    editor.createShape({
      id,
      type: "text",
      x: base.x + 24,
      y: base.y + 24,
      props: {
        color: "black",
        size: "m",
        font: "draw",
        textAlign: "middle",
        w: 240,
        richText: toRichText("文本"),
        scale: 1,
        autoSize: true
      }
    });
    editor.select(id);
    setMessage("已添加文本");
  }, []);

  const addShape = useCallback(
    (geo: string = "rectangle") => {
      const editor = editorRef.current;
      if (!editor) {
        return;
      }
      const id = createShapeId();
      const base = viewportPlacement(editor, editor.getCurrentPageShapes().length);
      editor.createShape({
        id,
        type: "geo",
        x: base.x,
        y: base.y,
        props: {
          geo,
          dash: "draw",
          url: "",
          w: 220,
          h: 140,
          growY: 0,
          scale: 1,
          labelColor: "black",
          color: "green",
          fill: "none",
          size: "m",
          font: "draw",
          align: "middle",
          verticalAlign: "middle",
          richText: toRichText(geo)
        }
      });
      editor.select(id);
      setMessage(`已添加 ${geo} 图形`);
    },
    []
  );

  const addLine = useCallback(() => {
    setTool("line", "已切换到线条工具");
  }, [setTool]);

  const addArrow = useCallback(() => {
    setTool("arrow", "已切换到箭头工具");
  }, [setTool]);

  const addImage = useCallback(async (file: File) => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }

    const asset = await editor.getAssetForExternalContent({ type: "file", file });
    if (!asset) {
      setMessage("图片处理失败");
      return;
    }

    editor.createAssets([asset]);
    const id = createShapeId();
    const base = viewportPlacement(editor, editor.getCurrentPageShapes().length);
    editor.createShape({
      id,
      type: "image",
      x: base.x,
      y: base.y,
      props: {
        w: 360,
        h: 240,
        playing: false,
        url: asset.props.src ?? "",
        assetId: asset.id,
        crop: null,
        flipX: false,
        flipY: false,
        altText: file.name
      }
    });
    editor.select(id);
    setMessage("已添加图片");
  }, []);

  const deleteSelected = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const ids = [...editor.getSelectedShapeIds()];
    if (!ids.length) {
      setMessage("请先选中要删除的元素");
      return;
    }
    // 同步删除后端卡片，避免刷新后 mergeMissingCards 让已删卡片复活
    // 同时存墓碑，供 Ctrl+Z undo 时重建后端卡片
    const cardIds: string[] = [];
    const shapeIdsToDelete: string[] = [];
    const deletedData: Array<{ cardId: string; shape: { x: number; y: number; props?: Record<string, unknown> } }> = [];
    for (const id of ids) {
      const shape = editor.getShape(id);
      if (!shape) {
        continue;
      }
      // 图形节点（思维导图/流程图节点）→ 调后端删除 shapes 表记录
      if (shape.type === "geo") {
        // shape.id 是 "shape:<uuid>" 格式，去掉前缀取真实 UUID
        const rawId = String(id).replace(/^shape:/, "");
        if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(rawId)) {
          shapeIdsToDelete.push(rawId);
        }
        continue;
      }
      const metaCardId = (shape.meta?.cardId as string | undefined) ?? undefined;
      const cardId = metaCardId ?? (shape.type === "card" ? shape.id : undefined);
      if (cardId) {
        cardIds.push(cardId);
        deletedData.push({ cardId, shape: { x: shape.x, y: shape.y, props: shape.props as Record<string, unknown> } });
      }
    }
    if (cardIds.length > 0) {
      const known = knownCardIdsRef.current;
      cardIds.forEach((cardId) => known.delete(cardId));
      deletedCardsRef.current.push(...deletedData);
      void Promise.allSettled(cardIds.map((cardId) => api.deleteCard(cardId)));
    }
    if (shapeIdsToDelete.length > 0) {
      void Promise.allSettled(shapeIdsToDelete.map((shapeId) => api.deleteShape(shapeId)));
    }

    editor.deleteShapes(ids);
    setMessage(
      cardIds.length + shapeIdsToDelete.length > 0
        ? `已删除 ${ids.length} 个元素（同步后端）`
        : "已删除选中元素"
    );
  }, []);

  // 全局 Delete/Backspace 删除选中的元素（hideUi 移除了 tldraw 内置快捷键）
  const deleteRef = useRef(deleteSelected);
  deleteRef.current = deleteSelected;
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const tag = (event.target as HTMLElement)?.tagName;
      // 输入框内不触发（避免编辑时误删）
      if (tag === "INPUT" || tag === "TEXTAREA") {
        return;
      }
      const editor = editorRef.current;
      if (!editor) {
        return;
      }
      const mod = event.ctrlKey || event.metaKey;
      if (mod && event.key.toLowerCase() === "z") {
        event.preventDefault();
        if (event.shiftKey) {
          editor.redo();
        } else {
          editor.undo();
        }
        return;
      }
      if (event.key === "Delete" || event.key === "Backspace") {
        event.preventDefault();
        deleteRef.current();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  const refresh = useCallback(async () => {
    await onRefresh();
    setMessage("已刷新资料和任务");
  }, [onRefresh]);

  const autoArrange = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const moved = autoLayoutTldraw(editor);
    if (moved === 0) {
      setMessage("画布暂无可整理的卡片");
      return;
    }
    setMessage(`已自动整理 ${moved} 个元素`);
    queueSave();
  }, [queueSave]);

  const [templateOpen, setTemplateOpen] = useState(false);
  const [shapeOpen, setShapeOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);

  const exportPng = useCallback(async () => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    try {
      const result = await editor.toImage([...editor.getCurrentPageShapeIds()], { format: "png" });
      const url = URL.createObjectURL(result.blob);
      const a = document.createElement("a");
      a.href = url;
      a.download = `${boardTitle || "画布"}.png`;
      a.click();
      URL.revokeObjectURL(url);
      setMessage("画布已导出为 PNG");
    } catch {
      setMessage("PNG 导出失败");
    }
  }, [boardTitle]);

  const exportMarkdown = useCallback(() => {
    const lines: string[] = [];
    for (const card of cards) {
      lines.push(`## ${card.title}\n\n${card.contentMarkdown ?? ""}\n`);
    }
    if (lines.length === 0) {
      setMessage("画布暂无卡片可导出");
      return;
    }
    const blob = new Blob([lines.join("\n---\n\n")], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${boardTitle || "画布"}.md`;
    a.click();
    URL.revokeObjectURL(url);
    setMessage(`已导出 ${cards.length} 张卡片为 Markdown`);
  }, [boardTitle, cards]);

  const applyTemplate = useCallback(
    async (templateId: string) => {
      setTemplateOpen(false);
      try {
        const rootTitle = window.prompt("请输入模板标题（可留空）", "");
        await onApplyTemplate(templateId, rootTitle ?? "");
        setMessage("模板已创建");
      } catch (err) {
        setMessage(err instanceof Error ? err.message : "模板创建失败");
      }
    },
    [onApplyTemplate]
  );

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [sourceOpen, setSourceOpen] = useState(false);

  return (
    <div className="flex min-h-0 flex-1 overflow-hidden">
      {sourceOpen ? (
        <>
          {/* 遮罩：点击面板外任意处关闭 */}
          <div
            className="absolute inset-0 z-20"
            onClick={() => setSourceOpen(false)}
            aria-label="关闭资料面板"
          />
          <div className="absolute left-2 top-16 z-30 w-72 shrink-0 rounded-lg border border-ink/10 bg-white shadow-lg">
            <SourcePanel files={files} onUpload={onUploadFile} onDelete={onDeleteFile} />
          </div>
        </>
      ) : null}

      <section
        className="relative min-h-0 flex-1 overflow-hidden bg-[#eef4f1]"
        onClick={() => setSourceOpen(false)}
      >
        <div
          className="absolute left-4 top-4 z-20 flex flex-wrap gap-2"
          onClick={(event) => event.stopPropagation()}
        >
          <GhostButton onClick={() => setSourceOpen((value) => !value)} title="上传/管理资料">
            <FolderOpen className="h-4 w-4" />
            资料
          </GhostButton>
          <GhostButton onClick={() => setTool("select", "已切换到选择工具")}>
            <MousePointer2 className="h-4 w-4" />
            选择
          </GhostButton>
          <GhostButton onClick={() => setTool("hand", "已切换到抓手工具：按住拖动可平移画布，滚轮缩放；切回选择可编辑")}>
            <Hand className="h-4 w-4" />
            平移
          </GhostButton>
          <GhostButton onClick={addNote}>
            <Plus className="h-4 w-4" />
            卡片
          </GhostButton>
          <GhostButton onClick={addText}>
            <Text className="h-4 w-4" />
            文本
          </GhostButton>
          <div className="relative">
            <GhostButton onClick={() => setShapeOpen((value) => !value)}>
              <Square className="h-4 w-4" />
              图形
            </GhostButton>
            {shapeOpen ? (
              <div className="absolute left-0 top-full z-30 mt-1 grid w-40 grid-cols-2 gap-0.5 rounded-md border border-ink/10 bg-white p-1 shadow-md">
                {SHAPE_TYPES.map((shape) => (
                  <button
                    key={shape.value}
                    type="button"
                    onClick={() => {
                      setShapeOpen(false);
                      addShape(shape.value);
                    }}
                    className="flex items-center gap-1.5 rounded px-1.5 py-1 text-left text-[11px] capitalize text-ink/80 hover:bg-mist"
                  >
                    {shape.icon}
                    <span>{shape.label}</span>
                  </button>
                ))}
              </div>
            ) : null}
          </div>
          <GhostButton onClick={addLine}>
            <Minus className="h-4 w-4" />
            线条
          </GhostButton>
          <GhostButton onClick={addArrow}>
            <ArrowRight className="h-4 w-4" />
            箭头
          </GhostButton>
          <GhostButton onClick={() => fileInputRef.current?.click()}>
            <FileImage className="h-4 w-4" />
            图片
          </GhostButton>
          <GhostButton onClick={deleteSelected}>
            <Trash2 className="h-4 w-4" />
            删除
          </GhostButton>
          <GhostButton onClick={refresh}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </GhostButton>
          <GhostButton onClick={autoArrange} title="按连线分层自动排列卡片">
            <LayoutGrid className="h-4 w-4" />
            整理
          </GhostButton>
          <div className="relative">
            <GhostButton onClick={() => setTemplateOpen((value) => !value)} title="创建模板画布">
              <LayoutTemplate className="h-4 w-4" />
              模板
            </GhostButton>
            {templateOpen ? (
              <div className="absolute left-0 top-full z-30 mt-1 w-44 rounded-md border border-ink/10 bg-white p-1 shadow-md">
                {whiteboardTemplates.map((template) => (
                  <button
                    key={template.id}
                    type="button"
                    onClick={() => void applyTemplate(template.id)}
                    className="block w-full rounded px-2 py-1.5 text-left text-xs text-ink hover:bg-mist"
                  >
                    {template.name}
                    <span className="block text-[11px] text-ink/50">{template.description}</span>
                  </button>
                ))}
              </div>
            ) : null}
          </div>
          <div className="relative">
            <GhostButton onClick={() => setExportOpen((value) => !value)} title="导出画布">
              <Download className="h-4 w-4" />
              导出
            </GhostButton>
            {exportOpen ? (
              <div className="absolute left-0 top-full z-30 mt-1 w-40 rounded-md border border-ink/10 bg-white p-1 shadow-md">
                <button
                  type="button"
                  onClick={() => {
                    setExportOpen(false);
                    void exportPng();
                  }}
                  className="block w-full rounded px-2 py-1.5 text-left text-xs text-ink hover:bg-mist"
                >
                  导出 PNG
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setExportOpen(false);
                    exportMarkdown();
                  }}
                  className="block w-full rounded px-2 py-1.5 text-left text-xs text-ink hover:bg-mist"
                >
                  导出 Markdown
                </button>
              </div>
            ) : null}
          </div>
          <IconButton onClick={() => void saveSnapshot()} title="立即保存">
            <Save className="h-4 w-4" />
          </IconButton>
          <input
            ref={fileInputRef}
            type="file"
            className="hidden"
            accept="image/*"
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) {
                void addImage(file);
              }
              event.target.value = "";
            }}
          />
        </div>

        <div className="absolute left-4 top-16 z-20 max-w-[26rem] rounded-md border border-ink/10 bg-white/95 px-3 py-2 text-xs text-ink/60 shadow-sm">
          <div className="flex items-center gap-2">
            <Shield className="h-3.5 w-3.5" />
            <span>{message}</span>
          </div>
          <div className="mt-1 text-[11px] text-ink/45">{saveStatusText(saveStatus)}</div>
        </div>

        <div className="absolute inset-0">
          <Tldraw
            store={store}
            onMount={onMount}
            hideUi
            autoFocus={false}
            className="h-full w-full"
            shapeUtils={CARD_SHAPE_UTILS}
          />
        </div>

        {editorReady ? <ElementInspector editor={editorRef.current} /> : null}
      </section>

      <TaskPanel
        boardId={boardId}
        tasks={tasks}
        connection={connection}
        onAssist={onAssist}
        onAbortTask={onAbortTask}
      />
    </div>
  );
}

async function loadLegacyContent(
  editor: TldrawEditor,
  input: {
    boardId: string;
    boardTitle?: string;
    cards: BoardCard[];
    edges: BoardEdge[];
    files: SourceFile[];
  }
) {
  const shapeIdsByCardId = new Map<string, ReturnType<typeof createShapeId>>();

  for (const [index, card] of input.cards.entries()) {
    createCardShape(editor, input.boardId, card, index, input.files, shapeIdsByCardId);
  }

  for (const edge of input.edges) {
    createEdgeBinding(editor, edge, shapeIdsByCardId);
  }
}

/**
 * 把后端图形节点（思维导图/流程图节点）渲染为 tldraw geo shape。
 * shape 类型映射：ellipse→椭圆，rectangle→矩形，oval→胶囊。
 * 返回新增数量。
 */
function mergeMissingShapes(editor: TldrawEditor, shapes: BoardShape[], edges: BoardEdge[]): number {
  let added = 0;
  for (const node of shapes) {
    const shapeId = createShapeId(node.id);
    if (editor.getShape(shapeId)) {
      continue;
    }
    try {
      createGraphShape(editor, node, shapeId);
      added += 1;
    } catch (err) {
      // 单个节点失败不阻塞其他节点
      console.error("创建图形节点失败", node.id, err);
    }
  }
  // 创建图形节点间的连线（edges 引用 shape id）
  if (edges.length > 0) {
    const shapeIdByNode = new Map(shapes.map((s) => [s.id, createShapeId(s.id)]));
    for (const edge of edges) {
      try {
        createEdgeBinding(editor, edge, shapeIdByNode);
      } catch (err) {
        console.error("创建连线失败", edge.id, err);
      }
    }
  }
  return added;
}

function createGraphShape(editor: TldrawEditor, node: BoardShape, shapeId: ReturnType<typeof createShapeId>) {
  const geoType = node.shape === "ellipse" ? "ellipse" : node.shape === "oval" ? "oval" : "rectangle";
  const isCenter = node.level === 0;
  editor.createShape({
    id: shapeId,
    type: "geo",
    x: node.x,
    y: node.y,
    props: {
      geo: geoType,
      dash: "solid",
      url: "",
      w: node.width,
      h: node.height,
      growY: 0,
      scale: 1,
      labelColor: "black",
      color: isCenter ? "green" : "blue",
      fill: isCenter ? "solid" : "semi",
      size: "m",
      font: "draw",
      align: "middle",
      verticalAlign: "middle",
      richText: toRichText(node.title)
    }
  });
}

/**
 * 把旧快照中的 note/geo 卡片迁移为自定义 card shape。
 * 旧卡片 shape 的 id 等于 createShapeId(card.id)，据此识别并保留坐标。
 * 返回迁移数量。
 */
function migrateLegacyCardShapes(editor: TldrawEditor, cards: BoardCard[]): number {
  let migrated = 0;
  const cardById = new Map(cards.map((card) => [card.id, card]));

  for (const shape of editor.getCurrentPageShapes()) {
    if (shape.type !== "note" && shape.type !== "geo") {
      continue;
    }
    const card = cardById.get(shape.id);
    if (!card) {
      continue;
    }
    const props = shape.props as Record<string, unknown>;
    const text = extractRichTextText(props.richText) || card.contentMarkdown || "";
    const firstLine = text.split("\n")[0]?.trim() ?? "";
    const markdown = text.split("\n").slice(1).join("\n").trim();
    editor.updateShape({
      id: shape.id,
      type: "card",
      x: shape.x,
      y: shape.y,
      meta: { cardId: card.id },
      props: {
        w: card.width,
        h: card.height,
        title: card.title || firstLine,
        markdown: markdown || text,
        cardType: card.cardType
      }
    });
    migrated += 1;
  }
  return migrated;
}

/** 从 tldraw richText 结构中提取纯文本 */
function extractRichTextText(richText: unknown): string {
  if (!richText || typeof richText !== "object") {
    return "";
  }
  const root = richText as { content?: Array<{ content?: Array<{ text?: string }> }> };
  if (!Array.isArray(root.content)) {
    return "";
  }
  const lines: string[] = [];
  for (const block of root.content) {
    const parts: string[] = [];
    for (const inline of block.content ?? []) {
      if (inline.text) {
        parts.push(inline.text);
      }
    }
    lines.push(parts.join(""));
  }
  return lines.join("\n");
}

function mergeMissingCards(
  editor: TldrawEditor,
  input: {
    boardId: string;
    cards: BoardCard[];
    edges: BoardEdge[];
    files: SourceFile[];
  }
) {
  const shapeIdsByCardId = new Map<string, ReturnType<typeof createShapeId>>();
  let added = 0;
  const existingCount = editor.getCurrentPageShapes().length;

  for (const [index, card] of input.cards.entries()) {
    const shapeId = createShapeId(card.id);    if (editor.getShape(shapeId)) {
      shapeIdsByCardId.set(card.id, shapeId);
      continue;
    }
    createCardShape(editor, input.boardId, card, existingCount + index, input.files, shapeIdsByCardId);
    added += 1;
  }

  for (const edge of input.edges) {
    createEdgeBinding(editor, edge, shapeIdsByCardId);
  }

  return added;
}

function createCardShape(
  editor: TldrawEditor,
  boardId: string,
  card: BoardCard,
  index: number,
  files: SourceFile[],
  shapeIdsByCardId: Map<string, ReturnType<typeof createShapeId>>
) {
  const base = nextPlacement(index);
  const shapeId = createShapeId(card.id);
  shapeIdsByCardId.set(card.id, shapeId);

  if (card.cardType === "IMAGE") {
    const fileId = extractSourceFileId(card.contentMarkdown);
    if (!fileId) {
      return;
    }
    const file = files.find((item) => item.id === fileId);
    const assetId = AssetRecordType.createId();
    editor.createAssets([
      {
        id: assetId,
        typeName: "asset",
        type: "image",
        meta: {
          boardId,
          fileId
        },
        props: {
          src: api.fileContentUrl(boardId, fileId),
          w: card.width,
          h: card.height,
          mimeType: file?.mimeType ?? "image/*",
          name: card.title,
          fileSize: file?.size ?? undefined,
          isAnimated: false
        }
      }
    ]);
    editor.createShape({
      id: shapeId,
      type: "image",
      x: card.x ?? base.x,
      y: card.y ?? base.y,
      meta: { cardId: card.id },
      props: {
        w: card.width,
        h: card.height,
        playing: false,
        url: api.fileContentUrl(boardId, fileId),
        assetId,
        crop: null,
        flipX: false,
        flipY: false,
        altText: card.title
      }
    });
    return;
  }

  if (card.cardType === "SOURCE") {
    editor.createShape({
      id: shapeId,
      type: "card",
      x: card.x ?? base.x,
      y: card.y ?? base.y,
      meta: { cardId: card.id },
      props: {
        w: card.width,
        h: card.height,
        title: card.title,
        markdown: card.contentMarkdown ?? "",
        cardType: card.cardType
      }
    });
    return;
  }

  editor.createShape({
    id: shapeId,
    type: "card",
    x: card.x ?? base.x,
    y: card.y ?? base.y,
    meta: { cardId: card.id },
    props: {
      w: card.width,
      h: card.height,
      title: card.title,
      markdown: card.contentMarkdown ?? "",
      cardType: card.cardType
    }
  });
}

function createEdgeBinding(
  editor: TldrawEditor,
  edge: BoardEdge,
  shapeIdsByCardId: Map<string, ReturnType<typeof createShapeId>>
) {
  const fromId = shapeIdsByCardId.get(edge.fromCardId) ?? createShapeId(edge.fromCardId);
  const toId = shapeIdsByCardId.get(edge.toCardId) ?? createShapeId(edge.toCardId);
  if (!editor.getShape(fromId) || !editor.getShape(toId)) {
    return;
  }

  const arrowId = createShapeId(edge.id);
  if (editor.getShape(arrowId)) {
    return;
  }
  // 连线样式：curve→arc 曲线（加大 bend），straight→直线（bend=0）；颜色/粗细按 edge 配置
  const isCurve = edge.style === "curve";
  // tldraw arrow color 只接受内建颜色名（black/blue/green...），不能是 hex 或空串
  const color = mapEdgeColor(edge.color);
  const weight = edge.weight ?? 2;

  editor.createShape({
    id: arrowId,
    type: "arrow",
    props: {
      kind: "arc",
      labelColor: "black",
      color,
      fill: "none",
      dash: "solid",
      size: weight >= 3 ? "l" : weight === 1 ? "s" : "m",
      arrowheadStart: "none",
      arrowheadEnd: "arrow",
      font: "draw",
      start: { x: 0, y: 0 },
      end: { x: 160, y: 0 },
      bend: isCurve ? 0.3 : 0,
      text: edge.label ?? "",
      labelPosition: 0.5,
      scale: 1,
      elbowMidPoint: 0.5
    }
  });
  editor.createBindings([
    {
      id: createBindingId(),
      type: "arrow",
      fromId: arrowId,
      toId: fromId,
      props: {
        terminal: "start",
        normalizedAnchor: { x: 0.5, y: 0.5 },
        isExact: false,
        isPrecise: false,
        snap: "none"
      }
    },
    {
      id: createBindingId(),
      type: "arrow",
      fromId: arrowId,
      toId,
      props: {
        terminal: "end",
        normalizedAnchor: { x: 0.5, y: 0.5 },
        isExact: false,
        isPrecise: false,
        snap: "none"
      }
    }
  ]);
}

function nextPlacement(index: number) {
  const column = index % 3;
  const row = Math.floor(index / 3);
  return {
    x: 120 + column * 240,
    y: 120 + row * 180
  };
}

/**
 * 以当前视口中心为锚点计算新元素坐标（叠加数量偏移，连续添加向四周展开）。
 * 只用于用户交互式添加；加载/同步卡片仍用 nextPlacement（相机状态不可靠）。
 */
function viewportPlacement(editor: TldrawEditor, index: number) {
  const center = editor.getViewportPageBounds().center;
  const column = index % 3;
  const row = Math.floor(index / 3);
  return {
    x: center.x - 170 + column * 240,
    y: center.y - 110 + row * 180
  };
}

function extractSourceFileId(markdown: string) {
  const match = markdown.match(/sourceFileId:([0-9a-fA-F-]+)/);
  return match?.[1];
}

/** 把后端颜色映射为 tldraw 内建颜色名（tldraw arrow color 不接受 hex） */
function mapEdgeColor(color?: string): string {
  if (!color || color.trim() === "") {
    return "green";
  }
  const trimmed = color.trim().toLowerCase();
  // 已是内建颜色名
  const builtin = ["black", "blue", "green", "grey", "light-blue", "light-green", "light-red", "light-violet",
    "orange", "red", "violet", "yellow", "white"];
  if (builtin.includes(trimmed)) {
    return trimmed;
  }
  // hex → 就近映射
  const hex = trimmed.replace("#", "");
  if (/^[0-9a-f]{6}$/i.test(hex)) {
    const r = parseInt(hex.slice(0, 2), 16);
    const g = parseInt(hex.slice(2, 4), 16);
    const b = parseInt(hex.slice(4, 6), 16);
    if (r > 180 && g > 180 && b > 180) {
      return "light-green";
    }
    if (r > 150 && g < 120 && b < 120) {
      return "red";
    }
    if (b > 150 && r < 120 && g < 120) {
      return "blue";
    }
    if (r > 150 && g > 120 && b < 120) {
      return "orange";
    }
    return "green";
  }
  return "green";
}

function saveStatusText(status: SaveStatus) {
  switch (status) {
    case "saving":
      return "正在保存";
    case "saved":
      return "已保存";
    case "error":
      return "保存失败";
    case "idle":
    default:
      return "未保存";
  }
}

/** 自动整理间距与分层 */
const ARRANGE_MARGIN = 120;
const ARRANGE_H_GAP = 80;
const ARRANGE_V_GAP = 100;
const ARRANGE_W = 340;
const ARRANGE_H = 220;

/**
 * 对 tldraw 当前画布的元素做分层自动整理。
 * 依据箭头 binding 构建连接图：箭头 A 两端分别连两个端点 shape。
 * 布局：BFS 分层（入度 0 的根在最上层），每层内节点横向并排，层与层纵向堆叠，避免重叠。
 * 返回被移动的元素数量。
 */
function autoLayoutTldraw(editor: TldrawEditor): number {
  const shapes = editor.getCurrentPageShapes();
  const movable = shapes.filter((shape) => shape.type !== "arrow" && shape.type !== "frame" && shape.type !== "embed");
  if (movable.length === 0) {
    return 0;
  }
  const ids = new Set(movable.map((shape) => shape.id));
  const adj = new Map<string, Set<string>>();
  for (const shape of movable) {
    adj.set(shape.id, new Set());
  }
  for (const arrow of shapes.filter((shape) => shape.type === "arrow")) {
    const bindings = editor.getBindingsInvolvingShape(arrow.id, "arrow");
    // binding: fromId 是箭头 id，toId 是端点 shape id
    const endpoints: string[] = [];
    for (const binding of bindings) {
      const endpoint = binding.toId === arrow.id ? binding.fromId : binding.toId;
      if (endpoint !== arrow.id && ids.has(endpoint)) {
        endpoints.push(endpoint);
      }
    }
    // 首尾端点两两相连（无向）
    for (let i = 0; i < endpoints.length; i++) {
      for (let j = i + 1; j < endpoints.length; j++) {
        adj.get(endpoints[i])!.add(endpoints[j]);
        adj.get(endpoints[j])!.add(endpoints[i]);
      }
    }
  }

  // BFS 分层（入度 0 为根，最上层）
  const inDegree = new Map<string, number>();
  for (const id of ids) {
    inDegree.set(id, 0);
  }
  for (const [, targets] of adj) {
    for (const to of targets) {
      inDegree.set(to, (inDegree.get(to) ?? 0) + 1);
    }
  }
  const layerOf = new Map<string, number>();
  const queue: string[] = movable.filter((s) => (inDegree.get(s.id) ?? 0) === 0).map((s) => s.id);
  queue.forEach((id) => layerOf.set(id, 0));
  while (queue.length > 0) {
    const current = queue.shift()!;
    const layer = layerOf.get(current) ?? 0;
    for (const neighbor of adj.get(current) ?? []) {
      if (!layerOf.has(neighbor)) {
        layerOf.set(neighbor, layer + 1);
        queue.push(neighbor);
      }
    }
  }
  for (const id of ids) {
    if (!layerOf.has(id)) {
      layerOf.set(id, 0);
    }
  }

  // 每层内节点横向并排（按 x 原顺序稳定），层与层纵向堆叠
  const layerKeys = [...new Set(layerOf.values())].sort((a, b) => a - b);
  const updates: Array<{ id: ReturnType<typeof createShapeId>; type: string; x: number; y: number }> = [];
  let y = ARRANGE_MARGIN;
  for (const layerKey of layerKeys) {
    const layerIds = movable.filter((s) => layerOf.get(s.id) === layerKey);
    let x = ARRANGE_MARGIN;
    let layerMaxY = y;
    for (const shape of layerIds) {
      if (shape.x !== x || shape.y !== y) {
        updates.push({ id: shape.id, type: shape.type, x, y });
      }
      const props = shape.props as { w?: number; h?: number };
      const w = props?.w ?? ARRANGE_W;
      const h = props?.h ?? ARRANGE_H;
      x += w + ARRANGE_H_GAP;
      layerMaxY = Math.max(layerMaxY, y + h);
    }
    y = layerMaxY + ARRANGE_V_GAP;
  }

  if (updates.length === 0) {
    return 0;
  }
  editor.updateShapes(updates);
  return updates.length;
}
