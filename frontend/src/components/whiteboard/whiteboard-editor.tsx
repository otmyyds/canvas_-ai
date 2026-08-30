"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
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
  Minus,
  MousePointer2,
  RefreshCw,
  Save,
  Shield,
  Square,
  Text,
  Trash2
} from "lucide-react";
import type { AiTask, AssistResult, BoardEdge, BoardShape, SourceFile } from "@/lib/types";
import { api } from "@/lib/api";
import { GhostButton, IconButton } from "@/components/ui";
import { SourcePanel } from "@/components/board/source-panel";
import { TaskPanel } from "@/components/board/task-panel";
import { ElementInspector } from "@/components/whiteboard/element-inspector";

/** 匹配 AI 元素的真实 id（标准 uuid），用于区分 AI 生成的图形节点与手动绘制的元素 */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

type WhiteboardEditorProps = {
  boardId: string;
  boardTitle?: string;
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
  /** 选中变化回调：上报当前选中的图形节点真实 id（供「编辑选中元素」使用） */
  onSelectionChange?: (selection: { shapeIds: string[] }) => void;
  /** 当前选中的元素数量（用于 AI 助手面板提示） */
  selectionCount?: number;
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
 * 1. 初始化 tldraw store（默认图形 + 图片上传到 RustFS）
 * 2. 加载画布快照 + 后端图形/连线 → 渲染到画布（hydration）
 * 3. 监听画布变更 → 防抖保存 snapshot
 * 4. 监听 AI 任务完成 → 增量同步新图形节点
 * 5. 工具栏：选择/平移/文本/图形/线条/箭头/图片/删除/刷新/整理/导出
 *
 * 双状态源协调：
 * - 用户编辑 → 写 board_snapshots（tldraw 快照）
 * - AI 生成 → 写 shapes/edges 表 → 前端 merge 进 tldraw
 * - 删除时同步删后端（deleteShape），避免刷新复活
 *
 * @param boardId 画布 id
 * @param edges 后端连线列表
 * @param shapes 后端图形节点列表（思维导图/流程图节点）
 * @param canvasRevision AI 任务完成后递增，触发增量同步 effect
 */
export function WhiteboardEditor({
  boardId,
  boardTitle,
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
  onSelectionChange,
  selectionCount = 0
}: WhiteboardEditorProps) {
  /**
   * tldraw store：画布的底层数据源。
   * - 默认图形 utils（defaultShapeUtils）
   * - 图片资产上传到后端（RustFS）
   * 注意：useMemo 保证 store 只创建一次（随 boardId 变化重建）
   */
  const store = useMemo(
    () =>
      createTLStore({
        shapeUtils: defaultShapeUtils,
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
  const edgesRef = useRef(edges);
  const shapesRef = useRef(shapes);
  const filesRef = useRef(files);
  const [editorReady, setEditorReady] = useState(false);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("idle");
  const [message, setMessage] = useState("滚轮/中键拖动平移画布，滚轮缩放已改为平移；选中元素按 Delete 删除");

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

    // 已初始化过当前画布则跳过（避免重复 hydration）
    if (hydratedBoardRef.current === boardId) {
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

      const currentEdges = edgesRef.current;
      const currentShapes = shapesRef.current;

      if (snapshot?.snapshotJson) {
        const parsed = parseTldrawJsonFile({
          json: snapshot.snapshotJson,
          schema: editor.store.schema
        });
        if (parsed.ok) {
          editor.loadSnapshot(parsed.value.getStoreSnapshot());
          const shapeAdded = mergeMissingShapes(editor, currentShapes, currentEdges);
          // 快照可能残留已被后端删除的节点，清理掉避免刷新复活
          const removed = removeOrphanShapes(editor, currentShapes, currentEdges);
          editor.clearHistory();
          editor.selectNone();
          hydratedBoardRef.current = boardId;
          mountedRef.current = true;
          if (shapeAdded > 0 || removed > 0) {
            setMessage(`已载入画布快照，同步 ${shapeAdded} 个节点，清理 ${removed} 个残留`);
            queueSave();
          } else {
            setMessage("已载入画布快照");
          }
          return;
        }
      }

      mergeMissingShapes(editor, currentShapes, currentEdges);
      removeOrphanShapes(editor, currentShapes, currentEdges);
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

  // AI 任务完成后把后端图形节点增量同步进 tldraw（新增 + 清理被删除的残留）
  useEffect(() => {
    if (!editorReady || hydratedBoardRef.current !== boardId || !mountedRef.current) {
      return;
    }

    const editor = editorRef.current;
    if (!editor) {
      return;
    }

    // 增量同步图形节点（思维导图/流程图节点）
    const shapeIds = new Set(editor.getCurrentPageShapes().map((s) => s.id));
    const newShapes = shapes.filter((s) => !shapeIds.has(createShapeId(s.id)));
    if (newShapes.length > 0) {
      mergeMissingShapes(editor, newShapes, edges);
    }
    // 后端已删除、但 tldraw 快照里还残留的图形节点/连线需一并清理，
    // 否则下次刷新时快照会把已删节点"复活"
    const removed = removeOrphanShapes(editor, shapes, edges);
    if (newShapes.length > 0 || removed > 0) {
      setMessage(
        newShapes.length > 0
          ? `已同步 ${newShapes.length} 个图形节点到画布`
          : `已清理 ${removed} 个残留元素`
      );
      queueSave();
    }
  }, [boardId, canvasRevision, edges, editorReady, queueSave, shapes]);

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
    setMessage("画布已清空");
    queueSave();
  }, [clearSignal, editorReady, queueSave]);

  useEffect(() => {
    const unlisten = store.listen(() => {
      if (!mountedRef.current) {
        return;
      }
      queueSave();
    }, { scope: "document" });

    return () => {
      unlisten();
      if (saveTimerRef.current) {
        window.clearTimeout(saveTimerRef.current);
      }
    };
  }, [queueSave, store]);

  const onMount = useCallback((editor: TldrawEditor) => {
    editorRef.current = editor;
    // 让画布获得焦点，保证 Delete/空格 快捷键首击即生效
    editor.focus();
    // 滚轮直接平移画布（默认是缩放），配合手工具/中键拖动
    editor.setCameraOptions({ wheelBehavior: "pan" });
    setEditorReady(true);
  }, []);

  // 监听选中变化，把选中的图形节点真实 id 上报给父组件
  // 映射规则与 deleteSelected 一致：geo 节点去掉 "shape:" 前缀取真实 id
  useEffect(() => {
    if (!editorReady || !onSelectionChange) {
      return;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const sync = () => {
      const shapeIds = new Set<string>();
      for (const id of editor.getSelectedShapeIds()) {
        const shape = editor.getShape(id);
        if (!shape) {
          continue;
        }
        if (shape.type === "geo") {
          const rawId = String(id).replace(/^shape:/, "");
          if (UUID_PATTERN.test(rawId)) {
            shapeIds.add(rawId);
          }
        }
      }
      onSelectionChange({ shapeIds: [...shapeIds] });
    };
    sync();
    const unlisten = editor.store.listen(sync, { scope: "document" });
    return () => unlisten();
  }, [editorReady, onSelectionChange]);

  const setTool = useCallback((toolId: string, hint: string) => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    editor.setCurrentTool(toolId);
    setMessage(hint);
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
    // 同步删除后端图形节点，避免刷新后 mergeMissingShapes 让已删节点复活
    const shapeIdsToDelete: string[] = [];
    for (const id of ids) {
      const shape = editor.getShape(id);
      if (!shape) {
        continue;
      }
      // 图形节点（思维导图/流程图节点）→ 调后端删除 shapes 表记录
      if (shape.type === "geo") {
        // shape.id 是 "shape:<uuid>" 格式，去掉前缀取真实 UUID
        const rawId = String(id).replace(/^shape:/, "");
        if (UUID_PATTERN.test(rawId)) {
          shapeIdsToDelete.push(rawId);
        }
      }
    }
    if (shapeIdsToDelete.length > 0) {
      void Promise.allSettled(shapeIdsToDelete.map((shapeId) => api.deleteShape(shapeId)));
    }

    editor.deleteShapes(ids);
    setMessage(
      shapeIdsToDelete.length > 0
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
      setMessage("画布暂无可整理的元素");
      return;
    }
    setMessage(`已自动整理 ${moved} 个元素`);
    queueSave();
  }, [queueSave]);

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
          <GhostButton onClick={autoArrange} title="按连线分层自动排列元素">
            <LayoutGrid className="h-4 w-4" />
            整理
          </GhostButton>
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
            shapeUtils={defaultShapeUtils}
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
        selectionCount={selectionCount}
      />
    </div>
  );
}

/**
 * 把后端图形节点（思维导图/流程图节点）渲染为 tldraw geo shape。
 * shape 类型映射：ellipse→椭圆，rectangle→矩形，oval→胶囊，diamond→菱形。
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

/**
 * 清理 tldraw 里"后端已删除、但快照残留"的元素。
 * 只处理 AI 生成的元素（id 形如 shape:<uuid>）：
 * - geo 图形节点：uuid 不在后端 shapes 列表 → 删除
 * - arrow 连线：uuid 不在后端 edges 列表 → 删除
 * 手动绘制的元素（id 非 uuid）不受影响。返回清理数量。
 */
function removeOrphanShapes(editor: TldrawEditor, shapes: BoardShape[], edges: BoardEdge[]): number {
  const shapeUuids = new Set(shapes.map((s) => s.id));
  const edgeUuids = new Set(edges.map((e) => e.id));
  const toDelete: ReturnType<typeof createShapeId>[] = [];
  for (const shape of editor.getCurrentPageShapes()) {
    const rawId = String(shape.id).replace(/^shape:/, "");
    if (!UUID_PATTERN.test(rawId)) {
      continue;
    }
    if (shape.type === "geo" && !shapeUuids.has(rawId)) {
      toDelete.push(shape.id);
    } else if (shape.type === "arrow" && !edgeUuids.has(rawId)) {
      toDelete.push(shape.id);
    }
  }
  if (toDelete.length > 0) {
    editor.deleteShapes(toDelete);
  }
  return toDelete.length;
}

function createGraphShape(editor: TldrawEditor, node: BoardShape, shapeId: ReturnType<typeof createShapeId>) {
  const geoType = node.shape === "ellipse" ? "ellipse"
    : node.shape === "oval" ? "oval"
    : node.shape === "diamond" ? "diamond"
    : "rectangle";
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

function createEdgeBinding(
  editor: TldrawEditor,
  edge: BoardEdge,
  shapeIdsByNode: Map<string, ReturnType<typeof createShapeId>>
) {
  const fromId = shapeIdsByNode.get(edge.fromShapeId) ?? createShapeId(edge.fromShapeId);
  const toId = shapeIdsByNode.get(edge.toShapeId) ?? createShapeId(edge.toShapeId);
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

/**
 * 以当前视口中心为锚点计算新元素坐标（叠加数量偏移，连续添加向四周展开）。
 * 只用于用户交互式添加。
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
