"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  Tldraw,
  createShapeId,
  createTLStore,
  parseTldrawJsonFile,
  serializeTldrawJson,
  toRichText
} from "tldraw";
import type { Editor as TldrawEditor } from "@tldraw/editor";
import {
  ArrowRight,
  ChevronDown,
  ChevronUp,
  Download,
  FileImage,
  FolderOpen,
  GitBranch,
  Hand,
  LayoutGrid,
  ListTree,
  Minus,
  MousePointer2,
  Network,
  RefreshCw,
  Save,
  Shield,
  Square,
  Text,
  Trash2
} from "lucide-react";
import type {
  AiTask,
  AssistResult,
  BoardAsset,
  BoardEdge,
  BoardLayoutStyle,
  BoardShape,
  SourceFile
} from "@/lib/types";
import { api } from "@/lib/api";
import { GhostButton, ToolButton } from "@/components/ui";
import { SourcePanel } from "@/components/board/source-panel";
import { TaskPanel } from "@/components/board/task-panel";
import { ElementInspector } from "@/components/whiteboard/element-inspector";
import { MindmapCollapse } from "@/components/whiteboard/mindmap-collapse";
import {
  deleteShapesForced,
  geoVisualFingerprint,
  hiddenShapeIds,
  shapeStateFingerprint,
  syncGraphVisuals
} from "@/lib/whiteboard/shape-render";
import {
  clearSubtreeDragEnd,
  isSubtreeDragActive,
  setSubtreeDragEnd,
  whiteboardBindingUtils,
  whiteboardShapeUtils
} from "@/lib/whiteboard/mindmap-shapes";
import {
  clearReorderCommit,
  setReorderCommit,
  setStructureContext
} from "@/lib/whiteboard/logic-reorder";
import { adoptFreeStructures } from "@/lib/whiteboard/structure-adoption";

/** 匹配 AI 元素的真实 id（标准 uuid），用于区分 AI 生成的图形节点与手动绘制的元素 */
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** 活跃任务状态：计划执行期间暂停快照保存，避免中间状态落库 */
const ACTIVE_TASK_STATUSES = new Set(["CREATED", "QUEUED", "RUNNING", "ABORTING"]);

type WhiteboardEditorProps = {
  boardId: string;
  boardTitle?: string;
  edges: BoardEdge[];
  shapes: BoardShape[];
  assets: BoardAsset[];
  files: SourceFile[];
  tasks: AiTask[];
  connection: string;
  canvasRevision?: number;
  /** 清空画布信号：CLEAR_CANVAS 步骤完成时递增，触发白板清空 tldraw 元素 */
  clearSignal?: number;
  /** 画布重载信号：任务失败/中断时递增，强制白板重新载入后端回退后的快照 */
  reloadSignal?: number;
  onRefresh: () => Promise<void>;
  onAssist: (prompt: string) => Promise<AssistResult>;
  onAbortTask: (taskId: string) => Promise<void>;
  onUploadFile: (file: File) => Promise<void>;
  onDeleteFile: (fileId: string) => Promise<void>;
  /** 选中变化回调：上报当前选中的图形节点真实 id（供「编辑选中元素」使用） */
  onSelectionChange?: (selection: { shapeIds: string[] }) => void;
  /** 当前选中的元素数量（用于 AI 助手面板提示） */
  selectionCount?: number;
  /** 当前画布的思维导图排布风格 */
  layoutStyle?: BoardLayoutStyle;
  /** 节点总数超过该值后只允许逻辑图 */
  radialMaxNodes?: number;
  /** 切换布局风格：父组件调用后端重排并刷新画布 */
  onLayoutStyleChange?: (style: BoardLayoutStyle) => Promise<void>;
};

type SaveStatus = "idle" | "saving" | "saved" | "error";

/** 回写后端的图形节点编辑体（标题/位置/尺寸/视觉属性） */
type ShapeUpdate = {
  title: string;
  x: number;
  y: number;
  width: number;
  height: number;
  /** geo 的视觉属性；mindmap-node 传 null 表示不改 */
  visual: Record<string, string> | null;
};

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
  assets,
  files,
  tasks,
  connection,
  canvasRevision = 0,
  clearSignal = 0,
  reloadSignal = 0,
  onRefresh,
  onAssist,
  onAbortTask,
  onUploadFile,
  onDeleteFile,
  onSelectionChange,
  selectionCount = 0,
  layoutStyle = "RADIAL",
  radialMaxNodes = 40,
  onLayoutStyleChange
}: WhiteboardEditorProps) {
  /**
   * tldraw store：画布的底层数据源。
   * - 默认图形 utils + 逻辑图自定义图形（mindmap-node/mindmap-edge）
   * - 图片资产上传到后端（RustFS）
   * 注意：useMemo 保证 store 只创建一次（随 boardId 变化重建）
   */
  const store = useMemo(
    () =>
      createTLStore({
        shapeUtils: whiteboardShapeUtils,
        bindingUtils: whiteboardBindingUtils,
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
    // 只随 boardId 重建 store：boardTitle 变化（重命名）不能重建 store，否则画布内容会被清空成白板
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [boardId]
  );

  const editorRef = useRef<TldrawEditor | null>(null);
  const saveTimerRef = useRef<number | null>(null);
  const mountedRef = useRef(false);
  const hydratedBoardRef = useRef<string | null>(null);
  const edgesRef = useRef(edges);
  const shapesRef = useRef(shapes);
  const assetsRef = useRef(assets);
  const filesRef = useRef(files);
  // 连续添加元素的累计序号：每次手动添加递增，避免用画布元素总数当偏移导致越加越远
  const addPlacementRef = useRef(0);
  // 已回写到后端的 AI 图形节点状态指纹（原始 id -> JSON），避免首次加载误回写
  const syncedShapeRef = useRef<Map<string, string>>(new Map());
  // 待回写后端的节点编辑（原始 id -> 编辑体），防抖后批量提交
  const pendingShapeSyncRef = useRef<Map<string, ShapeUpdate>>(new Map());
  const shapeSyncTimerRef = useRef<number | null>(null);
  // 后端当前标题：判断本次编辑是否改了标题，只有标题变化才需要刷新布局
  const backendTitleRef = useRef<Map<string, string>>(new Map());
  // 标题已提交、等待编辑结束后刷新画布；编辑过程中刷新会打断文字输入
  const titleRelayoutPendingRef = useRef(false);
  // 上一帧正在编辑的图形 id，用于识别“编辑结束”的瞬间
  const editingShapeRef = useRef<string | null>(null);
  // 计划执行期间挂起的保存标记：计划结束后再统一落盘，避免回退前的中间状态覆盖恢复出的快照
  const pendingSaveRef = useRef(false);
  const planActiveRef = useRef(false);
  const prevPlanActiveRef = useRef(false);
  // 已完成的强制重载信号值，避免同一信号重复载入（严格模式下首次被取消时可重试）
  const completedReloadRef = useRef(0);
  // 水合完成后建立回写基线：记录当前所有 AI 图形节点的状态指纹，避免首次编辑被当成基线而丢失
  const seedSyncedShapes = useCallback((editor: TldrawEditor) => {
    for (const shape of editor.getCurrentPageShapes()) {
      if (shape.type !== "geo" && shape.type !== "mindmap-node") {
        continue;
      }
      const rawId = String(shape.id).replace(/^shape:/, "");
      if (!UUID_PATTERN.test(rawId)) {
        continue;
      }
      const props = shape.props as { w?: number; h?: number; geo?: string; color?: string; fill?: string; dash?: string };
      const title = editor.getShapeUtil(shape).getText(shape) ?? "";
      const visual = shape.type === "geo" ? geoVisualFingerprint(props) : "";
      syncedShapeRef.current.set(rawId,
        shapeStateFingerprint(shape.x, shape.y, props.w ?? 0, props.h ?? 0, title, visual));
    }
  }, []);

  // 合并 AI 生成图片资产到画布（以 image shape 渲染，id=shape:<assetId> 去重）
  const mergeAssets = useCallback(async (editor: TldrawEditor, boardAssets: BoardAsset[]) => {
    let added = 0;
    for (const asset of boardAssets) {
      const shapeId = createShapeId(asset.id);
      if (editor.getShape(shapeId)) {
        continue;
      }
      const url = api.assetContentUrl(boardId, asset.id);
      const tldrawAsset = await editor.getAssetForExternalContent({ type: "url", url });
      if (!tldrawAsset) {
        continue;
      }
      editor.createAssets([tldrawAsset]);
      const base = viewportPlacement(editor, addPlacementRef.current++);
      editor.createShape({
        id: shapeId,
        type: "image",
        x: base.x,
        y: base.y,
        props: {
          w: 512,
          h: 512,
          playing: false,
          url,
          assetId: tldrawAsset.id,
          crop: null,
          flipX: false,
          flipY: false,
          altText: "AI 生成图片"
        }
      });
      added += 1;
    }
    return added;
  }, [boardId]);

  const [editorReady, setEditorReady] = useState(false);
  const [saveStatus, setSaveStatus] = useState<SaveStatus>("idle");
  const [message, setMessage] = useState("滚轮/中键拖动平移画布，滚轮缩放已改为平移；选中元素按 Delete 删除");

  edgesRef.current = edges;
  shapesRef.current = shapes;
  assetsRef.current = assets;
  filesRef.current = files;
  // 后端标题只在 shapes 变化时重建索引，避免每次 store 事件都扫描全部节点
  useEffect(() => {
    backendTitleRef.current = new Map(shapes.map((shape) => [shape.id, shape.title]));
  }, [shapes]);
  // 是否存在正在执行的计划任务：渲染期同步到 ref，供保存监听即时判断
  const planActive = tasks.some((task) => task.type === "AGENT_PLAN" && ACTIVE_TASK_STATUSES.has(task.status));
  planActiveRef.current = planActive;

  const saveSnapshot = useCallback(async () => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }

    try {
      const snapshotJson = await serializeTldrawJson(editor);
      await api.saveSnapshot(boardId, snapshotJson);
      pendingSaveRef.current = false;
      setSaveStatus("saved");
      setMessage("画布已保存");
    } catch {
      setSaveStatus("error");
      setMessage("画布保存失败，请稍后重试");
    }
  }, [boardId]);

  /**
   * 立即落盘快照（提交 AI 任务前调用）。
   * 与 saveSnapshot 不同：失败必须抛出，让上层中止提交，避免后端检查点抓到旧快照。
   */
  const flushSnapshot = useCallback(async () => {
    if (saveTimerRef.current) {
      window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    try {
      const snapshotJson = await serializeTldrawJson(editor);
      await api.saveSnapshot(boardId, snapshotJson);
      pendingSaveRef.current = false;
      setSaveStatus("saved");
      setMessage("画布已保存");
    } catch (error) {
      setSaveStatus("error");
      setMessage("画布保存失败，无法提交 AI 任务");
      throw error;
    }
  }, [boardId]);

  const queueSave = useCallback(() => {
    // 计划执行期间不写服务端：回退会重建画布，中间快照落库会覆盖恢复结果
    if (planActiveRef.current) {
      pendingSaveRef.current = true;
      return;
    }
    if (saveTimerRef.current) {
      window.clearTimeout(saveTimerRef.current);
    }
    setSaveStatus("saving");
    saveTimerRef.current = window.setTimeout(() => {
      void saveSnapshot();
    }, 1000);
  }, [saveSnapshot]);

  // 提交任务前先落盘快照：后端检查点依赖最新 tldraw 快照，不能拿到 1 秒防抖前的旧数据
  const assistWithSnapshot = useCallback(
    async (prompt: string) => {
      await flushSnapshot();
      return onAssist(prompt);
    },
    [flushSnapshot, onAssist]
  );

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
          // 快照里的标题/尺寸可能落后于后端（AI 改过标题但没重新存过快照），此处对账；
          // 同时按当前布局风格把节点/连线转换成对应类型
          const synced = await syncGraphVisuals(editor, currentShapes, currentEdges, layoutStyle);
          // 快照可能残留已被后端删除的节点，清理掉避免刷新复活
          const removed = removeOrphanShapes(editor, currentShapes, currentEdges, assetsRef.current);
          editor.clearHistory();
          editor.selectNone();
          hydratedBoardRef.current = boardId;
          mountedRef.current = true;
          seedSyncedShapes(editor);
          const assetAdded = await mergeAssets(editor, assetsRef.current);
          if (synced > 0 || removed > 0 || assetAdded > 0) {
            setMessage(`已载入画布快照，同步 ${synced} 个元素，清理 ${removed} 个残留`);
            queueSave();
          } else {
            setMessage("已载入画布快照");
          }
          return;
        }
      }

      const synced = await syncGraphVisuals(editor, currentShapes, currentEdges, layoutStyle);
      removeOrphanShapes(editor, currentShapes, currentEdges, assetsRef.current);
      editor.clearHistory();
      editor.selectNone();
      hydratedBoardRef.current = boardId;
      mountedRef.current = true;
      seedSyncedShapes(editor);
      const assetAdded = await mergeAssets(editor, assetsRef.current);
      if (assetAdded > 0 || synced > 0) {
        queueSave();
      }
      setMessage("已载入原始内容");
    };

    void initialize();

    return () => {
      cancelled = true;
    };
  }, [boardId, boardTitle, editorReady, layoutStyle, mergeAssets, queueSave]);

  // 计划结束时处理执行期间挂起的保存：成功的计划才落盘；
  // 失败/中断时后端已回退画布，由 reloadSignal 载入恢复后的快照，本地状态直接丢弃
  useEffect(() => {
    const wasActive = prevPlanActiveRef.current;
    prevPlanActiveRef.current = planActive;
    if (!wasActive || planActive || !pendingSaveRef.current) {
      return;
    }
    pendingSaveRef.current = false;
    const latestPlan = tasks.find((task) => task.type === "AGENT_PLAN");
    if (latestPlan?.status === "SUCCEEDED") {
      queueSave();
    }
  }, [planActive, queueSave, tasks]);

  // 任务失败/中断后强制重载：丢弃失败前的本地状态，载入后端回退后的快照
  useEffect(() => {
    if (reloadSignal <= 0 || !editorReady || completedReloadRef.current === reloadSignal) {
      return;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    let cancelled = false;
    const reload = async () => {
      // 停止保存并丢弃挂起标记，阻止重载过程中的 store 变更回写服务端
      if (saveTimerRef.current) {
        window.clearTimeout(saveTimerRef.current);
        saveTimerRef.current = null;
      }
      pendingSaveRef.current = false;
      mountedRef.current = false;
      const snapshot = await api.getBoardSnapshot(boardId).catch(() => null);
      if (cancelled) {
        return;
      }
      if (snapshot?.snapshotJson) {
        const parsed = parseTldrawJsonFile({
          json: snapshot.snapshotJson,
          schema: editor.store.schema
        });
        if (parsed.ok) {
          editor.loadSnapshot(parsed.value.getStoreSnapshot());
        }
      }
      // 快照与后端表对账：补齐缺失节点、清理回退后已不存在的残留、恢复后端字段
      await syncGraphVisuals(editor, shapesRef.current, edgesRef.current, layoutStyle);
      removeOrphanShapes(editor, shapesRef.current, edgesRef.current, assetsRef.current);
      await mergeAssets(editor, assetsRef.current);
      if (cancelled) {
        return;
      }
      editor.clearHistory();
      editor.selectNone();
      hydratedBoardRef.current = boardId;
      mountedRef.current = true;
      seedSyncedShapes(editor);
      completedReloadRef.current = reloadSignal;
      setMessage("画布已回退到计划开始前");
      queueSave();
    };
    void reload();
    return () => {
      cancelled = true;
    };
  }, [reloadSignal, boardId, editorReady, layoutStyle, mergeAssets, queueSave, seedSyncedShapes]);

  // AI 任务完成后把后端图形节点增量同步进 tldraw（新增 + 清理被删除的残留）
  useEffect(() => {
    if (!editorReady || hydratedBoardRef.current !== boardId || !mountedRef.current) {
      return;
    }

    const editor = editorRef.current;
    if (!editor) {
      return;
    }

    // 增量补齐后端新增的图形节点/连线；已有节点的数值由下一条 canvasRevision 对账 effect 负责
    let cancelled = false;
    const sync = async () => {
      const synced = await syncGraphVisuals(editor, shapes, edges, layoutStyle, { updateExisting: false });
      if (cancelled || !mountedRef.current) {
        return;
      }
      // 后端已删除、但 tldraw 快照里还残留的图形节点/连线需一并清理，
      // 否则下次刷新时快照会把已删节点"复活"
      const removed = removeOrphanShapes(editor, shapes, edges, assets);
      if (synced > 0 || removed > 0) {
        setMessage(
          synced > 0
            ? `已同步 ${synced} 个图形元素到画布`
            : `已清理 ${removed} 个残留元素`
        );
        queueSave();
      }
    };
    void sync();
    return () => {
      cancelled = true;
    };
  }, [boardId, canvasRevision, edges, editorReady, layoutStyle, queueSave, shapes]);

  // AI 任务完成后把新生成的图片资产并入画布
  useEffect(() => {
    if (!editorReady || hydratedBoardRef.current !== boardId || !mountedRef.current) {
      return;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const shapeIds = new Set(editor.getCurrentPageShapes().map((s) => s.id));
    const newAssets = assets.filter((a) => !shapeIds.has(createShapeId(a.id)));
    if (newAssets.length === 0) {
      return;
    }
    void mergeAssets(editor, newAssets).then((added) => {
      if (added > 0) {
        setMessage(`已同步 ${added} 张生成图片到画布`);
        queueSave();
      }
    });
  }, [boardId, canvasRevision, assets, editorReady, mergeAssets, queueSave]);

  // AI 任务完成后，把后端已存在节点的新坐标/标题同步回 tldraw
  // （整理画布的重排坐标、update_shape 改写的标题都属于这条路径）
  useEffect(() => {
    if (canvasRevision <= 0 || !editorReady || hydratedBoardRef.current !== boardId || !mountedRef.current) {
      return;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    let cancelled = false;
    const sync = async () => {
      const updated = await syncGraphVisuals(editor, shapes, edges, layoutStyle, {
        baseline: syncedShapeRef.current
      });
      if (cancelled || !mountedRef.current) {
        return;
      }
      if (updated > 0) {
        // 同步后刷新回写基线，避免监听器把这些后端值当成用户编辑再写回去
        seedSyncedShapes(editor);
        setMessage(`已同步 ${updated} 个图形元素的样式`);
        queueSave();
      }
    };
    void sync();
    return () => {
      cancelled = true;
    };
  }, [canvasRevision, editorReady, shapes, edges, layoutStyle, boardId, queueSave, seedSyncedShapes]);

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
      // 结构连线已锁定，清空必须忽略锁
      deleteShapesForced(editor, shapeIds);
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

  /** 标题提交后若已退出文字编辑，刷新一次画布，把后端重排后的坐标和尺寸同步回来 */
  const refreshAfterTitleChange = useCallback(() => {
    if (!titleRelayoutPendingRef.current
      || pendingShapeSyncRef.current.size > 0
      || shapeSyncTimerRef.current != null) {
      return;
    }
    const editor = editorRef.current;
    if (editor?.getEditingShapeId()) {
      return;
    }
    titleRelayoutPendingRef.current = false;
    void onRefresh();
  }, [onRefresh]);

  // 用户拖动/改字/改尺寸 AI 图形节点 → 防抖回写 shapes 表（统一 tldraw 快照与 shapes 双状态源）
  const collectUserEdits = useCallback((editor: TldrawEditor) => {
    // 逻辑图子树拖拽期间全是预览位置：既不该落库，也不该进入回写基线。
    // 拖拽结束时由 setSubtreeDragEnd 注册的回调统一刷新基线。
    if (isSubtreeDragActive(editor)) {
      return;
    }
    for (const shape of editor.getCurrentPageShapes()) {
      if (shape.type !== "geo" && shape.type !== "mindmap-node") {
        continue;
      }
      const rawId = String(shape.id).replace(/^shape:/, "");
      if (!UUID_PATTERN.test(rawId)) {
        continue;
      }
      const props = shape.props as { w?: number; h?: number; geo?: string; color?: string; fill?: string; dash?: string };
      const title = editor.getShapeUtil(shape).getText(shape) ?? "";
      const isGeo = shape.type === "geo";
      const visual = isGeo
        ? { geo: props.geo ?? "", color: props.color ?? "", fill: props.fill ?? "", dash: props.dash ?? "" }
        : null;
      const fingerprint = shapeStateFingerprint(shape.x, shape.y, props.w ?? 0, props.h ?? 0, title,
        visual ? geoVisualFingerprint(visual) : "");
      const prev = syncedShapeRef.current.get(rawId);
      syncedShapeRef.current.set(rawId, fingerprint);
      if (prev === undefined || prev === fingerprint) {
        continue;
      }
      // 标题变化会让后端重算尺寸并重排分量；位置/颜色变化不需要。
      // 后端标题索引还没建立时不标记，避免把首次拖动误判成改名。
      const backendTitle = backendTitleRef.current.get(rawId);
      if (backendTitle !== undefined && backendTitle !== title) {
        titleRelayoutPendingRef.current = true;
      }
      pendingShapeSyncRef.current.set(rawId, {
        title,
        x: shape.x,
        y: shape.y,
        width: props.w ?? 0,
        height: props.h ?? 0,
        visual
      });
    }
    if (pendingShapeSyncRef.current.size > 0 && shapeSyncTimerRef.current == null) {
      shapeSyncTimerRef.current = window.setTimeout(() => {
        const updates = [...pendingShapeSyncRef.current];
        pendingShapeSyncRef.current.clear();
        shapeSyncTimerRef.current = null;
        void Promise.allSettled(updates.map(([id, update]) => api.updateShape(id, update)))
          .then(refreshAfterTitleChange);
      }, 500);
    }
  }, [refreshAfterTitleChange]);

  useEffect(() => {
    const unlisten = store.listen(() => {
      const editor = editorRef.current;
      if (!editor || !mountedRef.current) {
        return;
      }
      collectUserEdits(editor);
      // 文字编辑结束的瞬间再刷新，避免重排结果在输入过程中打断光标
      const editing = editor.getEditingShapeId();
      if (!editing && editingShapeRef.current != null) {
        refreshAfterTitleChange();
      }
      editingShapeRef.current = editing ? String(editing) : null;
    }, { scope: "document" });

    return () => {
      unlisten();
      if (shapeSyncTimerRef.current != null) {
        window.clearTimeout(shapeSyncTimerRef.current);
      }
    };
  }, [collectUserEdits, refreshAfterTitleChange, store]);

  // 子树拖拽结束后按当前画布状态重建回写基线：预览位置不算用户编辑，
  // 否则松手后会把这些中间坐标批量 PATCH 给后端，下一次重排就会读出错乱的位置。
  useEffect(() => {
    if (!editorReady) {
      return;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    setSubtreeDragEnd(editor, () => seedSyncedShapes(editor));
    return () => clearSubtreeDragEnd(editor);
  }, [editorReady, seedSyncedShapes]);

  /**
   * 逻辑图拖动排序的上下文与提交回调：
   * 拖拽过程中按当前结构（后端 shapes/edges）计算目标顺序，松手后提交并刷新画布。
   */
  useEffect(() => {
    if (!editorReady) {
      return;
    }
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    setStructureContext(editor, { shapes, edges });
    setReorderCommit(editor, (plan) => {
      void api.reorderChildren(boardId, {
        parentId: plan.parentId,
        children: plan.children.map((child) => ({ edgeId: child.edgeId, side: child.side }))
      })
        .then(() => onRefresh())
        .catch((err) => setMessage(err instanceof Error ? err.message : "调整顺序失败"));
    });
    return () => clearReorderCommit(editor);
  }, [boardId, edges, editorReady, onRefresh, shapes]);

  const onMount = useCallback((editor: TldrawEditor) => {
    editorRef.current = editor;
    // 让画布获得焦点，保证 Delete/空格 快捷键首击即生效
    editor.focus();
    // 滚轮直接平移画布（默认是缩放），配合手工具/中键拖动
    editor.setCameraOptions({ wheelBehavior: "pan" });
    // 画布固定浅色：节点默认样式按浅色配色设计，跟随系统暗色会导致文字对比度失效
    editor.user.updateUserPreferences({ colorScheme: "light" });
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
        if (shape.type === "geo" || shape.type === "mindmap-node") {
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

  // 工具按钮的高亮状态：只跟踪工具栏切换，键盘快捷键切换不在此处同步
  const [activeTool, setActiveTool] = useState("select");

  const setTool = useCallback((toolId: string, hint: string) => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    editor.setCurrentTool(toolId);
    setActiveTool(toolId);
    setMessage(hint);
  }, []);

  const addText = useCallback(() => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const id = createShapeId();
    const base = viewportPlacement(editor, addPlacementRef.current++);
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
      const base = viewportPlacement(editor, addPlacementRef.current++);
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
          // 手绘图形默认色改为蓝色，与旧版默认的绿色区分开
          color: "blue",
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

  /** 新建节点：无选中时创建根节点，选中 1 个节点时在其下新建子节点 */
  const addChildNode = useCallback(async () => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const selected = editor.getSelectedShapeIds();

    // 未选中任何元素：在画布上新建独立根节点
    if (selected.length === 0) {
      try {
        const created = await api.createShape(boardId, { parentId: null, title: "中心主题" });
        await onRefresh();
        setMessage(`已新建根节点：${created.title}`);
      } catch (err) {
        setMessage(err instanceof Error ? err.message : "新建根节点失败");
      }
      return;
    }

    if (selected.length > 1) {
      setMessage("请最多选择一个节点作为父节点");
      return;
    }
    const shape = editor.getShape(selected[0]);
    if (!shape || (shape.type !== "geo" && shape.type !== "mindmap-node")) {
      setMessage("请先选中一个图形节点作为父节点");
      return;
    }
    const parentId = String(selected[0]).replace(/^shape:/, "");
    if (!UUID_PATTERN.test(parentId)) {
      setMessage("该元素不是画布结构节点，无法新建子节点");
      return;
    }
    const parentTitle = editor.getShapeUtil(shape).getText(shape) || "选中节点";
    try {
      const created = await api.createShape(boardId, { parentId, title: "新节点" });
      await onRefresh();
      setMessage(`已在「${parentTitle}」下新建节点：${created.title}`);
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "新建节点失败");
    }
  }, [boardId, onRefresh]);

  /** 新建兄弟节点：找到选中节点的父节点，再在父节点下创建一个同层节点 */
  const addSiblingNode = useCallback(async () => {
    const editor = editorRef.current;
    if (!editor) {
      return;
    }
    const selected = editor.getSelectedShapeIds();
    if (selected.length !== 1) {
      setMessage("请先选中一个节点，再新建兄弟节点");
      return;
    }
    const shape = editor.getShape(selected[0]);
    if (!shape || (shape.type !== "geo" && shape.type !== "mindmap-node")) {
      setMessage("请先选中一个图形节点");
      return;
    }
    const shapeId = String(selected[0]).replace(/^shape:/, "");
    if (!UUID_PATTERN.test(shapeId)) {
      setMessage("该元素不是画布结构节点，无法新建兄弟节点");
      return;
    }
    // 兄弟节点的父节点就是当前节点的入边起点；没有入边说明当前是根节点
    const parentEdge = edges.find((edge) => edge.toShapeId === shapeId);
    if (!parentEdge) {
      setMessage("根节点没有兄弟节点，可在其下新建子节点");
      return;
    }
    try {
      await api.createShape(boardId, { parentId: parentEdge.fromShapeId, title: "新节点" });
      await onRefresh();
      setMessage("已新建兄弟节点");
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "新建兄弟节点失败");
    }
  }, [boardId, edges, onRefresh]);

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
    const base = viewportPlacement(editor, addPlacementRef.current++);
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
    // 结构连线（mindmap-edge 或后端连线对应的箭头）是节点结构的产物，不允许单独删除
    const edgeIds = new Set(edges.map((edge) => edge.id));
    // 同步删除后端记录（图形节点 + 图片资产），避免刷新后 mergeMissingShapes/mergeAssets 让已删元素复活
    const shapeIdsToDelete: string[] = [];
    const assetIdsToDelete: string[] = [];
    const deleteIds: (typeof ids)[number][] = [];
    let blockedEdges = 0;
    for (const id of ids) {
      const shape = editor.getShape(id);
      if (!shape) {
        continue;
      }
      // shape.id 是 "shape:<uuid>" 格式，去掉前缀取真实 UUID
      const rawId = String(id).replace(/^shape:/, "");
      if (!UUID_PATTERN.test(rawId)) {
        deleteIds.push(id);
        continue;
      }
      // 图形节点（思维导图/流程图节点，含逻辑图 fork 节点）→ 删 shapes 表记录
      if (shape.type === "geo" || shape.type === "mindmap-node") {
        shapeIdsToDelete.push(rawId);
        deleteIds.push(id);
      }
      // 图片（AI 生成资产）→ 删 assets 表记录 + RustFS 对象
      else if (shape.type === "image") {
        assetIdsToDelete.push(rawId);
        deleteIds.push(id);
      }
      // 结构连线只能通过删除节点移除
      else if (shape.type === "mindmap-edge" || edgeIds.has(rawId)) {
        blockedEdges++;
      } else {
        deleteIds.push(id);
      }
    }
    if (deleteIds.length === 0) {
      setMessage(blockedEdges > 0 ? "连线由结构决定，请删除节点" : "请先选中要删除的元素");
      return;
    }
    if (shapeIdsToDelete.length > 0) {
      // 思维导图节点由后端连带删除子树和连线，删完重新拉取画布对账
      void Promise.allSettled(shapeIdsToDelete.map((shapeId) => api.deleteShape(shapeId))).then(() => onRefresh());
    }
    if (assetIdsToDelete.length > 0) {
      void Promise.allSettled(assetIdsToDelete.map((assetId) => api.deleteAsset(boardId, assetId)));
    }

    editor.deleteShapes(deleteIds);
    const syncedCount = shapeIdsToDelete.length + assetIdsToDelete.length;
    setMessage(
      blockedEdges > 0
        ? "连线由结构决定，已删除节点，连线随结构一起移除"
        : syncedCount > 0
          ? `已删除 ${deleteIds.length} 个元素（同步后端）`
          : "已删除选中元素"
    );
  }, [boardId, edges, onRefresh]);

  // 全局 Delete/Backspace 删除选中的元素（hideUi 移除了 tldraw 内置快捷键）
  const deleteRef = useRef(deleteSelected);
  deleteRef.current = deleteSelected;
  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      // 输入框、文本域或可编辑区域（含 tldraw 图形内文字编辑）不触发，避免编辑文字时误删图形
      if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable)) {
        return;
      }
      const editor = editorRef.current;
      if (!editor) {
        return;
      }
      // 正在编辑图形内文字（tldraw 编辑态），Backspace 删除字符而非图形
      if (editor.getEditingShapeId()) {
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

  const [shapeOpen, setShapeOpen] = useState(false);
  const [exportOpen, setExportOpen] = useState(false);
  const [layoutOpen, setLayoutOpen] = useState(false);
  const [layoutBusy, setLayoutBusy] = useState(false);
  const [collapseBusy, setCollapseBusy] = useState(false);

  /** 折叠/展开节点：后端重排后刷新画布，隐藏的后代由同步流程统一删除 */
  const toggleCollapse = useCallback(
    (shapeId: string, collapsed: boolean) => {
      if (collapseBusy) {
        return;
      }
      setCollapseBusy(true);
      void api.setCollapsed(boardId, shapeId, collapsed)
        .then(() => onRefresh())
        .catch((err) => setMessage(err instanceof Error ? err.message : "折叠失败"))
        .finally(() => setCollapseBusy(false));
    },
    [boardId, collapseBusy, onRefresh]
  );
  // 任务执行中禁止切换布局：后端落库与布局重排共用画布行锁，避免互相覆盖
  const taskRunning = tasks.some((task) => ACTIVE_TASK_STATUSES.has(task.status));
  const radialDisabled = shapes.length > radialMaxNodes;

  /** 整理画布：先把自由图形/箭头纳管为后端结构，再让后端按连通分量重排整张画布 */
  const arrangeBoard = useCallback(async () => {
    const editor = editorRef.current;
    if (!editor || layoutBusy || taskRunning) {
      return;
    }
    setLayoutBusy(true);
    try {
      const adopted = await adoptFreeStructures(editor, boardId, { shapes, edges });
      await api.applyBoardLayout(boardId);
      await onRefresh();
      setMessage(adopted > 0 ? `已纳管 ${adopted} 个自由图形并整理画布` : "已整理画布");
    } catch (err) {
      setMessage(err instanceof Error ? err.message : "整理画布失败");
      // 纳管可能已经成功并删除了本地图形，失败时也要刷新，让后端结构重新渲染
      await onRefresh().catch(() => undefined);
    } finally {
      setLayoutBusy(false);
    }
  }, [boardId, edges, layoutBusy, onRefresh, shapes, taskRunning]);

  /** 切换排布风格：后端立即重排，父组件刷新 shapes 后由 canvasRevision 同步回 tldraw */
  const changeLayout = useCallback(
    async (style: BoardLayoutStyle) => {
      setLayoutOpen(false);
      if (!onLayoutStyleChange || layoutBusy) {
        return;
      }
      setLayoutBusy(true);
      try {
        // 切换前先纳管画布上手绘的图形与箭头，让它们参与布局
        const editor = editorRef.current;
        if (editor) {
          const adopted = await adoptFreeStructures(editor, boardId, { shapes, edges });
          if (adopted > 0) {
            setMessage(`已纳管 ${adopted} 个自由图形，正在按新布局重排`);
          }
        }
        await onLayoutStyleChange(style);
        setMessage(style === "RADIAL" ? "已切换为树形图布局" : "已切换为逻辑图布局");
      } catch (err) {
        setMessage(err instanceof Error ? err.message : "切换布局失败");
      } finally {
        setLayoutBusy(false);
      }
    },
    [boardId, edges, layoutBusy, onLayoutStyleChange, shapes]
  );

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

  // 把 AI 图形节点导出为 Markdown 标题大纲（标题取 tldraw 当前文字，层级用后端 level）
  const exportMarkdown = useCallback(() => {
    const editor = editorRef.current;
    const lines: string[] = [];
    for (const node of shapes) {
      let title = node.title;
      if (editor) {
        const shape = editor.getShape(createShapeId(node.id));
        if (shape && (shape.type === "geo" || shape.type === "mindmap-node")) {
          title = editor.getShapeUtil(shape).getText(shape) ?? node.title;
        }
      }
      const depth = Math.min(Math.max(node.level, 0), 5);
      lines.push(`${"#".repeat(depth + 1)} ${title}`);
    }
    if (lines.length === 0) {
      setMessage("画布暂无图形节点可导出");
      return;
    }
    const blob = new Blob([lines.join("\n\n")], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = `${boardTitle || "画布"}.md`;
    a.click();
    URL.revokeObjectURL(url);
    setMessage("画布已导出为 Markdown");
  }, [boardTitle, shapes]);

  const fileInputRef = useRef<HTMLInputElement>(null);
  const [sourceOpen, setSourceOpen] = useState(false);
  const [toolbarHidden, setToolbarHidden] = useState(false);

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
          <div className={`absolute left-2 z-30 w-72 shrink-0 rounded-lg border border-ink/10 bg-white shadow-lg ${toolbarHidden ? "top-16" : "top-24"}`}>
            <SourcePanel files={files} onUpload={onUploadFile} onDelete={onDeleteFile} />
          </div>
        </>
      ) : null}

      <section
        className="relative min-h-0 flex-1 overflow-hidden bg-canvas"
        onClick={() => setSourceOpen(false)}
      >
        {toolbarHidden ? (
          <div className="absolute left-4 top-4 z-20" onClick={(event) => event.stopPropagation()}>
            <button
              type="button"
              onClick={() => setToolbarHidden(false)}
              className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-ink/10 bg-white/95 px-2.5 text-xs font-medium text-ink/60 shadow-lg backdrop-blur transition hover:border-accent/40 hover:text-accent"
              title="显示工具栏"
            >
              <ChevronDown className="h-3.5 w-3.5" />
              工具栏
            </button>
          </div>
        ) : (
        <div
          className="absolute left-4 top-4 z-20 flex max-w-[calc(100%-2rem)] flex-wrap items-center gap-1 rounded-xl border border-ink/10 bg-white/95 p-1.5 shadow-lg backdrop-blur"
          onClick={(event) => event.stopPropagation()}
        >
          {/* 资料与图片 */}
          <ToolButton
            active={sourceOpen}
            onClick={() => {
              setSourceOpen((value) => !value);
              setShapeOpen(false);
              setLayoutOpen(false);
              setExportOpen(false);
            }}
            title="上传/管理资料"
          >
            <FolderOpen className="h-4 w-4" />
            资料
          </ToolButton>
          <ToolButton onClick={() => fileInputRef.current?.click()} title="插入图片">
            <FileImage className="h-4 w-4" />
            图片
          </ToolButton>

          <span className="mx-0.5 h-6 w-px bg-ink/10" />

          {/* 选择 / 平移：二选一的分段按钮 */}
          <div className="flex items-center gap-0.5 rounded-lg bg-ink/[0.04] p-0.5">
            <ToolButton
              active={activeTool === "select"}
              onClick={() => setTool("select", "已切换到选择工具")}
            >
              <MousePointer2 className="h-4 w-4" />
              选择
            </ToolButton>
            <ToolButton
              active={activeTool === "hand"}
              onClick={() => setTool("hand", "已切换到抓手工具：按住拖动可平移画布，滚轮缩放；切回选择可编辑")}
            >
              <Hand className="h-4 w-4" />
              平移
            </ToolButton>
          </div>

          <span className="mx-0.5 h-6 w-px bg-ink/10" />

          {/* 插入元素 */}
          <ToolButton onClick={addText}>
            <Text className="h-4 w-4" />
            文本
          </ToolButton>
          <div className="relative">
            <ToolButton
              active={shapeOpen}
              onClick={() => {
                setShapeOpen((value) => !value);
                setLayoutOpen(false);
                setExportOpen(false);
              }}
            >
              <Square className="h-4 w-4" />
              图形
              <ChevronDown className="h-3 w-3 text-ink/35" />
            </ToolButton>
            {shapeOpen ? (
              <div className="menu-panel absolute left-0 top-full z-30 mt-1 grid w-44 grid-cols-2 gap-0.5 rounded-lg border border-ink/10 bg-white p-1 shadow-lg">
                {SHAPE_TYPES.map((shape) => (
                  <button
                    key={shape.value}
                    type="button"
                    onClick={() => {
                      setShapeOpen(false);
                      addShape(shape.value);
                    }}
                    className="flex items-center gap-1.5 rounded-md px-1.5 py-1 text-left text-[11px] capitalize text-ink/80 transition hover:bg-accent/10 hover:text-accent"
                  >
                    {shape.icon}
                    <span>{shape.label}</span>
                  </button>
                ))}
              </div>
            ) : null}
          </div>
          <ToolButton
            onClick={() => void addChildNode()}
            title="未选中时新建根节点，选中节点时在其下新建子节点"
          >
            <GitBranch className="h-4 w-4" />
            子节点
          </ToolButton>
          <ToolButton onClick={() => void addSiblingNode()} title="在选中节点的同级新建节点">
            <ListTree className="h-4 w-4" />
            兄弟节点
          </ToolButton>
          <ToolButton active={activeTool === "line"} onClick={addLine}>
            <Minus className="h-4 w-4" />
            线条
          </ToolButton>
          <ToolButton active={activeTool === "arrow"} onClick={addArrow}>
            <ArrowRight className="h-4 w-4" />
            箭头
          </ToolButton>

          <span className="mx-0.5 h-6 w-px bg-ink/10" />

          {/* 结构：整理与布局切换 */}
          <ToolButton
            onClick={() => void arrangeBoard()}
            disabled={taskRunning || layoutBusy}
            title={taskRunning ? "任务执行中，暂不能整理画布" : "把手绘图形和箭头纳入结构并整张重排"}
          >
            <LayoutGrid className="h-4 w-4" />
            整理
          </ToolButton>
          <div className="relative">
            <ToolButton
              active={layoutOpen}
              onClick={() => {
                setLayoutOpen((value) => !value);
                setShapeOpen(false);
                setExportOpen(false);
              }}
              disabled={taskRunning || layoutBusy}
              title={taskRunning ? "任务执行中，暂不能切换布局" : "切换思维导图排布"}
            >
              <Network className="h-4 w-4" />
              布局
              <ChevronDown className="h-3 w-3 text-ink/35" />
            </ToolButton>
            {layoutOpen ? (
              <div className="menu-panel absolute left-0 top-full z-30 mt-1 w-56 rounded-lg border border-ink/10 bg-white p-1 shadow-lg">
                <button
                  type="button"
                  onClick={() => void changeLayout("LOGIC")}
                  className={`flex w-full items-center rounded-md px-2 py-1.5 text-left text-xs transition hover:bg-accent/5 ${
                    layoutStyle === "LOGIC" ? "bg-accent/10 font-medium text-accent" : "text-ink"
                  }`}
                >
                  逻辑图
                  <span className="ml-2 text-[10px] font-normal text-ink/45">根居中，分支左右展开</span>
                </button>
                <button
                  type="button"
                  disabled={radialDisabled}
                  onClick={() => void changeLayout("RADIAL")}
                  className={`flex w-full items-center rounded-md px-2 py-1.5 text-left text-xs transition hover:bg-accent/5 disabled:cursor-not-allowed disabled:opacity-45 ${
                    layoutStyle === "RADIAL" ? "bg-accent/10 font-medium text-accent" : "text-ink"
                  }`}
                >
                  树形图
                  <span className="ml-2 text-[10px] font-normal text-ink/45">
                    {radialDisabled ? `超过 ${radialMaxNodes} 个节点不可用` : "径向辐射"}
                  </span>
                </button>
              </div>
            ) : null}
          </div>

          <span className="mx-0.5 h-6 w-px bg-ink/10" />

          {/* 画布操作 */}
          <ToolButton onClick={() => void refresh()} title="重新拉取后端数据">
            <RefreshCw className="h-4 w-4" />
            刷新
          </ToolButton>
          <ToolButton className="w-8 px-0" onClick={() => void saveSnapshot()} title="立即保存">
            <Save className="h-4 w-4" />
          </ToolButton>
          <div className="relative">
            <ToolButton
              active={exportOpen}
              onClick={() => {
                setExportOpen((value) => !value);
                setShapeOpen(false);
                setLayoutOpen(false);
              }}
              title="导出画布"
            >
              <Download className="h-4 w-4" />
              导出
              <ChevronDown className="h-3 w-3 text-ink/35" />
            </ToolButton>
            {exportOpen ? (
              <div className="menu-panel absolute right-0 top-full z-30 mt-1 w-40 rounded-lg border border-ink/10 bg-white p-1 shadow-lg">
                <button
                  type="button"
                  onClick={() => {
                    setExportOpen(false);
                    void exportPng();
                  }}
                  className="block w-full rounded-md px-2 py-1.5 text-left text-xs text-ink transition hover:bg-accent/5"
                >
                  导出 PNG
                </button>
                <button
                  type="button"
                  onClick={() => {
                    setExportOpen(false);
                    exportMarkdown();
                  }}
                  className="block w-full rounded-md px-2 py-1.5 text-left text-xs text-ink transition hover:bg-accent/5"
                >
                  导出 Markdown
                </button>
              </div>
            ) : null}
          </div>

          <span className="mx-0.5 h-6 w-px bg-ink/10" />

          <ToolButton
            className="!text-coral hover:!bg-coral/10 hover:!text-coral"
            onClick={deleteSelected}
            title="删除选中元素"
          >
            <Trash2 className="h-4 w-4" />
            删除
          </ToolButton>

          <span className="mx-0.5 h-6 w-px bg-ink/10" />

          <ToolButton
            className="w-8 px-0"
            onClick={() => {
              setToolbarHidden(true);
              setShapeOpen(false);
              setLayoutOpen(false);
              setExportOpen(false);
            }}
            title="隐藏工具栏"
          >
            <ChevronUp className="h-4 w-4" />
          </ToolButton>
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
        )}

        <div className={`absolute left-4 z-20 max-w-[26rem] rounded-lg border border-ink/10 bg-white/95 px-3 py-2 text-xs text-ink/60 shadow-sm backdrop-blur ${toolbarHidden ? "top-16" : "top-24"}`}>
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
            shapeUtils={whiteboardShapeUtils}
            bindingUtils={whiteboardBindingUtils}
          />
          {/* 逻辑图折叠控件：覆盖在画布上，按节点位置实时定位 */}
          {layoutStyle === "LOGIC" && editorReady ? (
            <MindmapCollapse
              editor={editorRef.current}
              shapes={shapes}
              edges={edges}
              busy={collapseBusy}
              onToggle={toggleCollapse}
            />
          ) : null}
        </div>

        {editorReady ? <ElementInspector editor={editorRef.current} /> : null}
      </section>

      <TaskPanel
        boardId={boardId}
        tasks={tasks}
        connection={connection}
        onAssist={assistWithSnapshot}
        onAbortTask={onAbortTask}
        selectionCount={selectionCount}
      />
    </div>
  );
}

/**
 * 清理 tldraw 里"后端已删除、但快照残留"的元素。
 * 只处理 AI 生成的元素（id 形如 shape:<uuid>）：
 * - geo / mindmap-node 图形节点：uuid 不在后端 shapes 列表 → 删除
 * - arrow / mindmap-edge 连线：uuid 不在后端 edges 列表 → 删除
 * - image 图片：uuid 不在后端 assets 列表 → 删除
 * 手动绘制的元素（id 非 uuid）不受影响。返回清理数量。
 */
function removeOrphanShapes(editor: TldrawEditor, shapes: BoardShape[], edges: BoardEdge[],
    assets: BoardAsset[]): number {
  // 折叠节点的后代按"不存在"处理：本地删除，展开后由后端数据重建
  const hidden = hiddenShapeIds(shapes, edges);
  const shapeUuids = new Set(shapes.filter((s) => !hidden.has(s.id)).map((s) => s.id));
  const edgeUuids = new Set(edges
    .filter((e) => !hidden.has(e.fromShapeId) && !hidden.has(e.toShapeId))
    .map((e) => e.id));
  const assetUuids = new Set(assets.map((a) => a.id));
  const toDelete: ReturnType<typeof createShapeId>[] = [];
  for (const shape of editor.getCurrentPageShapes()) {
    const rawId = String(shape.id).replace(/^shape:/, "");
    if (!UUID_PATTERN.test(rawId)) {
      continue;
    }
    if ((shape.type === "geo" || shape.type === "mindmap-node") && !shapeUuids.has(rawId)) {
      toDelete.push(shape.id);
    } else if ((shape.type === "arrow" || shape.type === "mindmap-edge") && !edgeUuids.has(rawId)) {
      toDelete.push(shape.id);
    } else if (shape.type === "image" && !assetUuids.has(rawId)) {
      toDelete.push(shape.id);
    }
  }
  if (toDelete.length > 0) {
    // 后端已删除的结构连线仍是锁定元素，普通删除会跳过
    deleteShapesForced(editor, toDelete);
  }
  return toDelete.length;
}

/**
 * 以当前视口中心为锚点计算新元素坐标（叠加数量偏移，连续添加向四周展开）。
 * 只用于用户交互式添加。
 */
function viewportPlacement(editor: TldrawEditor, index: number) {
  const center = editor.getViewportPageBounds().center;
  const column = index % 3;
  const row = Math.floor(index / 3) % 3;
  return {
    x: center.x - 170 + column * 240,
    y: center.y - 110 + row * 180
  };
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
