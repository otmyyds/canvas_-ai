import { createBindingId, createShapeId, toRichText } from "tldraw";
import type {
  Editor as TldrawEditor,
  TLArrowBinding,
  TLGeoShape,
  TLShape,
  TLShapeId,
  TLShapePartial
} from "@tldraw/editor";
import type { BoardEdge, BoardLayoutStyle, BoardShape } from "@/lib/types";
import {
  createMindmapEdgeBindings,
  updateMindmapEdgeGeometry
} from "@/lib/whiteboard/mindmap-shapes";
import type { MindmapEdgeBinding, MindmapEdgeShape, MindmapNodeShape } from "@/lib/whiteboard/mindmap-shapes";

/** 计算 AI 图形节点的状态指纹（位置/尺寸/标题/视觉属性），用于判断是否被改动 */
export function shapeStateFingerprint(
  x: number,
  y: number,
  width: number,
  height: number,
  title: string,
  visual = ""
): string {
  return JSON.stringify([x, y, width, height, title, visual]);
}

/** geo 视觉属性指纹：用户改颜色/填充/线型也要触发回写 */
export function geoVisualFingerprint(props: {
  geo?: string;
  color?: string;
  fill?: string;
  dash?: string;
}): string {
  return JSON.stringify([props.geo ?? "", props.color ?? "", props.fill ?? "", props.dash ?? ""]);
}

type ShapeId = ReturnType<typeof createShapeId>;

/**
 * 逻辑图里按连通分量判定 fork 节点：分量内任一层级 > 0 的节点即视为思维导图，
 * 整分量使用无边框文字节点和贝塞尔连线。判定规则与后端 BoardActionApplier 保持一致。
 */
function collectForkNodeIds(
  shapes: BoardShape[],
  edges: BoardEdge[],
  layoutStyle: BoardLayoutStyle
): Set<string> {
  const result = new Set<string>();
  if (layoutStyle !== "LOGIC") {
    return result;
  }
  const adjacency = new Map<string, Set<string>>();
  const levelById = new Map<string, number>();
  for (const node of shapes) {
    adjacency.set(node.id, new Set());
    levelById.set(node.id, node.level);
  }
  for (const edge of edges) {
    const from = adjacency.get(edge.fromShapeId);
    const to = adjacency.get(edge.toShapeId);
    if (!from || !to) {
      continue;
    }
    from.add(edge.toShapeId);
    to.add(edge.fromShapeId);
  }
  const visited = new Set<string>();
  for (const node of shapes) {
    if (visited.has(node.id)) {
      continue;
    }
    const component: string[] = [];
    const stack = [node.id];
    visited.add(node.id);
    while (stack.length > 0) {
      const current = stack.pop() as string;
      component.push(current);
      for (const next of adjacency.get(current) ?? []) {
        if (!visited.has(next)) {
          visited.add(next);
          stack.push(next);
        }
      }
    }
    if (component.some((id) => (levelById.get(id) ?? 0) > 0)) {
      for (const id of component) {
        result.add(id);
      }
    }
  }
  return result;
}

export type SyncGraphOptions = {
  /** 回写基线：同步后把实际状态写进去，避免监听器把这些后端值当成用户编辑再回写一次 */
  baseline?: Map<string, string>;
  /** 是否用后端值覆盖已存在且类型正确的节点；增量新增场景只补齐缺失元素，避免覆盖用户尚未回写的编辑 */
  updateExisting?: boolean;
};

type NodeType = "geo" | "mindmap-node";
type EdgeType = "arrow" | "mindmap-edge";

/** tldraw geo 图形支持的全部形状，纳管自由图形时按原类型还原 */
const GEO_TYPES = new Set<string>([
  "arrow-down", "arrow-left", "arrow-right", "arrow-up", "check-box", "cloud", "diamond", "ellipse",
  "heart", "hexagon", "octagon", "oval", "pentagon", "rectangle", "rhombus-2", "rhombus", "star",
  "trapezoid", "triangle", "x-box"
]);

/** 节点实际使用的 geo 形状：纳管时保存的原始 geo 优先，否则按后端的 shape 字段映射 */
function geoTypeOf(node: BoardShape): TLGeoShape["props"]["geo"] {
  const raw = node.visual?.geo ?? node.shape;
  return (GEO_TYPES.has(raw) ? raw : "rectangle") as TLGeoShape["props"]["geo"];
}

/** 节点最终生效的视觉属性：纳管图形用原属性，AI 节点用统一默认样式 */
type NodeVisual = {
  geo: TLGeoShape["props"]["geo"];
  color: TLGeoShape["props"]["color"];
  fill: TLGeoShape["props"]["fill"];
  dash: TLGeoShape["props"]["dash"];
  labelColor: TLGeoShape["props"]["labelColor"];
};

/**
 * AI 图形节点的默认视觉（不涉及数据库结构，只是渲染参数）：
 * 中心节点蓝色实心 + 白字，其余节点蓝色描边 + 浅色填充，配合 CSS 投影形成层次。
 * 沿用蓝色系是因为已有画布里大量节点保存过这套颜色，新旧节点放在一起不会有色差。
 * 纳管自由图形时保留用户原来的颜色/填充/线型。
 */
function effectiveVisual(node: BoardShape): NodeVisual {
  if (node.visual) {
    const color = (node.visual.color ?? "blue") as TLGeoShape["props"]["color"];
    const fill = (node.visual.fill ?? "semi") as TLGeoShape["props"]["fill"];
    return {
      geo: geoTypeOf(node),
      color,
      fill,
      dash: (node.visual.dash ?? "solid") as TLGeoShape["props"]["dash"],
      labelColor: labelColorFor(color, fill)
    };
  }
  const isCenter = node.level === 0;
  const color = "blue" as const;
  const fill = (isCenter ? "solid" : "semi") as TLGeoShape["props"]["fill"];
  return {
    geo: geoTypeOf(node),
    color,
    fill,
    dash: "solid",
    labelColor: labelColorFor(color, fill)
  };
}

/** 深色实心填充上用白字，浅色填充沿用 tldraw 默认的黑字 */
function labelColorFor(
  color: TLGeoShape["props"]["color"],
  fill: TLGeoShape["props"]["fill"]
): TLGeoShape["props"]["labelColor"] {
  const darkSolid = color === "black" || color === "blue" || color === "green"
    || color === "violet" || color === "red" || color === "orange";
  return fill === "solid" && darkSolid ? "white" : "black";
}

/** 视觉属性指纹：与回写基线使用同一套默认值，避免默认样式被当成用户编辑写回后端 */
function visualFingerprint(node: BoardShape): string {
  const visual = effectiveVisual(node);
  return geoVisualFingerprint(visual);
}

/**
 * 强制删除图形：结构连线是锁定元素，普通 deleteShapes 会跳过它们，
 * 类型转换、清空画布、孤儿清理都必须走这里。
 */
export function deleteShapesForced(editor: TldrawEditor, shapeIds: TLShapeId[]): void {
  if (shapeIds.length === 0) {
    return;
  }
  editor.run(() => editor.deleteShapes(shapeIds), { ignoreShapeLock: true });
}

/** 结构连线统一锁定：旧快照里的未锁连线在同步时补齐，用户只能拖节点不能拖连线 */
function ensureEdgeLocked(editor: TldrawEditor, shape: TLShape): boolean {
  if (shape.isLocked) {
    return false;
  }
  editor.updateShape({ id: shape.id, type: shape.type, isLocked: true } as TLShapePartial);
  return true;
}

type PendingNode = { node: BoardShape; type: NodeType };
type PendingEdge = { edge: BoardEdge; type: EdgeType; fromId: ShapeId; toId: ShapeId };

/**
 * 折叠节点的全部后代（不含折叠节点自身）。
 * 用独立 visited 阻断环，保证折叠节点自己不会被当成后代隐藏。
 */
export function hiddenShapeIds(shapes: BoardShape[], edges: BoardEdge[]): Set<string> {
  const roots = shapes.filter((shape) => shape.collapsed).map((shape) => shape.id);
  if (roots.length === 0) {
    return new Set();
  }
  const childrenOf = new Map<string, string[]>();
  for (const edge of edges) {
    const children = childrenOf.get(edge.fromShapeId) ?? [];
    children.push(edge.toShapeId);
    childrenOf.set(edge.fromShapeId, children);
  }
  const hidden = new Set<string>();
  for (const root of roots) {
    const visited = new Set<string>([root]);
    const queue = [...(childrenOf.get(root) ?? [])];
    while (queue.length > 0) {
      const current = queue.shift() as string;
      if (visited.has(current)) {
        continue;
      }
      visited.add(current);
      hidden.add(current);
      queue.push(...(childrenOf.get(current) ?? []));
    }
  }
  return hidden;
}

/** 每个编辑器一条串行队列：两阶段转换期间不能插入另一轮同步，否则会拿到半成品状态 */
const syncQueues = new WeakMap<TldrawEditor, Promise<number>>();

/**
 * 把后端图形数据同步到 tldraw：创建缺失元素、转换类型、更新位置/尺寸/标题。
 * 类型由当前布局风格决定：
 * - LOGIC 且属于思维导图分量 → mindmap-node / mindmap-edge（Freeplane fork 风格）；
 * - 其他情况 → geo / arrow（原有样式）。
 *
 * 同一 shape id 换类型时不能在同一帧里删了又建：tldraw 的图形组件按 id 复用 React fiber，
 * 而 geo/arrow 与自定义 util 在 component 里调用的 hooks 数量不同，会抛 hooks 顺序错误并被
 * 错误边界锁死。所以先删除类型不符的元素，等 React 提交卸载后，再按新类型重建。
 *
 * 同一编辑器上的调用会串行执行，返回发生变更的元素数量。
 */
export function syncGraphVisuals(
  editor: TldrawEditor,
  shapes: BoardShape[],
  edges: BoardEdge[],
  layoutStyle: BoardLayoutStyle,
  options: SyncGraphOptions = {}
): Promise<number> {
  const previous = syncQueues.get(editor) ?? Promise.resolve(0);
  // 前一轮失败不影响后续同步
  const next = previous.catch(() => 0).then(() => runSyncGraphVisuals(editor, shapes, edges, layoutStyle, options));
  syncQueues.set(editor, next);
  const cleanup = () => {
    if (syncQueues.get(editor) === next) {
      syncQueues.delete(editor);
    }
  };
  void next.then(cleanup, cleanup);
  return next;
}

async function runSyncGraphVisuals(
  editor: TldrawEditor,
  shapes: BoardShape[],
  edges: BoardEdge[],
  layoutStyle: BoardLayoutStyle,
  options: SyncGraphOptions
): Promise<number> {
  const { baseline, updateExisting = true } = options;
  // 折叠节点的后代不渲染：先按结构算出隐藏集合，再过滤节点与连线
  const hidden = hiddenShapeIds(shapes, edges);
  if (hidden.size > 0) {
    shapes = shapes.filter((node) => !hidden.has(node.id));
    edges = edges.filter((edge) => !hidden.has(edge.fromShapeId) && !hidden.has(edge.toShapeId));
  }
  const forkIds = collectForkNodeIds(shapes, edges, layoutStyle);
  const editingShapeId = editor.getEditingShapeId();
  const shapeIdByNode = new Map<string, ShapeId>();
  const nodeIds = new Set(shapes.map((node) => node.id));
  let changed = 0;

  // 阶段一：删除类型不符的元素并登记待建项；缺失的元素直接登记
  const pendingNodes: PendingNode[] = [];
  const pendingEdges: PendingEdge[] = [];
  // 类型已匹配的既有元素先收集，阶段二统一放进一个事务更新
  const nodeUpdates: Array<{ node: BoardShape; shapeId: ShapeId; type: NodeType }> = [];
  const edgeUpdates: Array<{ edgeId: ShapeId; type: EdgeType; fromId: ShapeId; toId: ShapeId }> = [];
  const deletedIds: TLShapeId[] = [];
  const arrowBindingsToRestore: TLArrowBinding[] = [];
  // 转换会删除选中元素，先记下来，重建后恢复选中状态
  const selectedIds = new Set(editor.getSelectedShapeIds().map(String));

  for (const node of shapes) {
    const shapeId = createShapeId(node.id);
    shapeIdByNode.set(node.id, shapeId);
    const desiredType: NodeType = forkIds.has(node.id) ? "mindmap-node" : "geo";
    const existing = editor.getShape(shapeId);
    if (!existing) {
      pendingNodes.push({ node, type: desiredType });
      continue;
    }
    if (existing.type !== desiredType) {
      // 正在输入的节点跳过类型转换，等编辑结束后下一轮再处理
      if (shapeId === editingShapeId) {
        continue;
      }
      // 结构连线会随分量一起重建，用户手绘的箭头不在重建范围内，先记下绑定再恢复
      arrowBindingsToRestore.push(...editor.getBindingsToShape<TLArrowBinding>(shapeId, "arrow"));
      deleteShapesForced(editor, [shapeId]);
      deletedIds.push(shapeId);
      pendingNodes.push({ node, type: desiredType });
      changed++;
      continue;
    }
    if (updateExisting && shapeId !== editingShapeId) {
      nodeUpdates.push({ node, shapeId, type: desiredType });
    }
  }

  for (const edge of edges) {
    // 两端不在后端节点列表里的连线直接忽略
    if (!nodeIds.has(edge.fromShapeId) || !nodeIds.has(edge.toShapeId)) {
      continue;
    }
    const fromId = shapeIdByNode.get(edge.fromShapeId) as ShapeId;
    const toId = shapeIdByNode.get(edge.toShapeId) as ShapeId;
    const edgeId = createShapeId(edge.id);
    const desiredType: EdgeType = forkIds.has(edge.fromShapeId) && forkIds.has(edge.toShapeId)
      ? "mindmap-edge"
      : "arrow";
    const existing = editor.getShape(edgeId);
    if (!existing) {
      pendingEdges.push({ edge, type: desiredType, fromId, toId });
      continue;
    }
    if (existing.type !== desiredType) {
      deleteShapesForced(editor, [edgeId]);
      deletedIds.push(edgeId);
      pendingEdges.push({ edge, type: desiredType, fromId, toId });
      changed++;
      continue;
    }
    edgeUpdates.push({ edgeId, type: desiredType, fromId, toId });
  }

  // 阶段二：已有元素的位置/尺寸/文字/锁定状态一次事务内更新。
  // 逐个 updateShape 会让 store 监听和 React 渲染按元素数重复触发，300 个节点时明显卡顿。
  if (nodeUpdates.length > 0 || edgeUpdates.length > 0) {
    editor.run(() => {
      for (const { node, shapeId, type } of nodeUpdates) {
        if (updateGraphShape(editor, node, shapeId, type, baseline)) {
          changed++;
        }
      }
      for (const { edgeId, type, fromId, toId } of edgeUpdates) {
        const shape = editor.getShape(edgeId);
        if (!shape) {
          continue;
        }
        // 结构连线是固定格式：锁定后不能选中/拖动/删除，端点跟随由节点几何实时计算
        if (ensureEdgeLocked(editor, shape)) {
          changed++;
        }
        if (type === "mindmap-edge") {
          if (ensureMindmapEdgeBindings(editor, edgeId, fromId, toId)) {
            changed++;
          }
          if (updateMindmapEdgeGeometry(editor, edgeId)) {
            changed++;
          }
        }
      }
    }, { history: "ignore" });
  }

  // 阶段三：等被删元素的 React 组件真正卸载后再重建，避免同 id 复用旧 fiber 导致 hooks 数量变化
  await waitForShapesUnmounted(editor, deletedIds);
  if (pendingNodes.length > 0 || pendingEdges.length > 0) {
    editor.run(() => {
      for (const { node, type } of pendingNodes) {
        createGraphShape(editor, node, createShapeId(node.id), type);
        changed++;
      }
      for (const { edge, type, fromId, toId } of pendingEdges) {
        const edgeId = createShapeId(edge.id);
        if (type === "mindmap-edge") {
          createMindmapEdge(editor, edgeId, fromId, toId);
        } else {
          createArrowEdge(editor, edge, edgeId, fromId, toId);
        }
        changed++;
      }
    }, { history: "ignore" });
  }
  // 节点重建会断开用户手绘箭头的绑定，按原绑定恢复。
  // 结构连线可能已经从 arrow 转成 mindmap-edge，这种箭头本身已不复存在，不能再挂绑定
  const bindingsRestored = arrowBindingsToRestore.filter((binding) => {
    const arrow = editor.getShape(binding.fromId);
    return arrow?.type === "arrow" && editor.getShape(binding.toId) !== undefined;
  });
  if (bindingsRestored.length > 0) {
    editor.createBindings(bindingsRestored);
  }
  if (deletedIds.length > 0 && selectedIds.size > 0) {
    const restored = [...selectedIds]
      .map((id) => createShapeId(id.replace(/^shape:/, "")))
      .filter((id) => editor.getShape(id));
    if (restored.length > 0) {
      editor.setSelectedShapes(restored);
    }
  }
  return changed;
}

/**
 * 等待这些图形对应的 DOM 节点从画布移除，说明 React 已提交卸载。
 * 画布外的图形由 tldraw 用 display:none 处理，DOM 节点仍在，所以按 data-shape-id 判断可靠。
 * 最多等 2 秒，避免画布未挂载时卡住同步。
 */
function waitForShapesUnmounted(editor: TldrawEditor, shapeIds: TLShapeId[]): Promise<void> {
  if (shapeIds.length === 0) {
    return Promise.resolve();
  }
  const target = new Set(shapeIds.map(String));
  const container = editor.getContainer();
  const deadline = performance.now() + 2000;
  return new Promise((resolve) => {
    const check = () => {
      const stillMounted = [...container.querySelectorAll(".tl-shape")].some((element) => {
        const id = element.getAttribute("data-shape-id");
        return id !== null && target.has(id);
      });
      if (!stillMounted || performance.now() > deadline) {
        resolve();
        return;
      }
      // 用定时器而不是 rAF：后台标签页里 rAF 会被暂停，同步队列不能因此卡死
      window.setTimeout(check, 16);
    };
    check();
  });
}

/** 按节点期望类型创建图形 */
function createGraphShape(editor: TldrawEditor, node: BoardShape, shapeId: ShapeId,
    type: "geo" | "mindmap-node") {
  if (type === "mindmap-node") {
    editor.createShape<MindmapNodeShape>({
      id: shapeId,
      type: "mindmap-node",
      x: node.x,
      y: node.y,
      props: {
        w: node.width,
        h: node.height,
        richText: toRichText(node.title)
      }
    });
    return;
  }
  // 纳管自由图形时保留原视觉属性，AI 节点使用统一默认样式
  const visual = effectiveVisual(node);
  editor.createShape({
    id: shapeId,
    type: "geo",
    x: node.x,
    y: node.y,
    props: {
      geo: visual.geo,
      dash: visual.dash,
      url: "",
      w: node.width,
      h: node.height,
      growY: 0,
      scale: 1,
      labelColor: visual.labelColor,
      color: visual.color,
      fill: visual.fill,
      size: "m",
      font: "draw",
      align: "middle",
      verticalAlign: "middle",
      richText: toRichText(node.title)
    }
  });
}

/**
 * 更新已存在且类型正确的节点。
 * geo 会清掉旧尺寸留下的 growY；mindmap-node 按文字重新写 richText/w/h。
 * 返回是否有改动。
 */
function updateGraphShape(editor: TldrawEditor, node: BoardShape, shapeId: ShapeId,
    type: "geo" | "mindmap-node", baseline?: Map<string, string>): boolean {
  const shape = editor.getShape(shapeId);
  if (!shape) {
    return false;
  }
  const titleChanged = editor.getShapeUtil(shape).getText(shape) !== node.title;
  const moved = shape.x !== node.x || shape.y !== node.y;
  // 与创建时使用同一套视觉属性，保证同步后的样式一致
  const visual = effectiveVisual(node);

  if (type === "mindmap-node") {
    const props = shape.props as MindmapNodeShape["props"];
    const propsUpdate: Partial<MindmapNodeShape["props"]> = {};
    if (titleChanged) {
      propsUpdate.richText = toRichText(node.title);
    }
    if (props.w !== node.width) {
      propsUpdate.w = node.width;
    }
    if (props.h !== node.height) {
      propsUpdate.h = node.height;
    }
    if (!moved && Object.keys(propsUpdate).length === 0) {
      return false;
    }
    // 先写入本次目标状态的基线，避免 store 监听把后端同步当成用户编辑再回写一次
    baseline?.set(node.id, shapeStateFingerprint(node.x, node.y, node.width, node.height, node.title));
    editor.updateShape<MindmapNodeShape>({
      id: shapeId,
      type: "mindmap-node",
      x: node.x,
      y: node.y,
      props: propsUpdate
    });
    // 改名时 onBeforeUpdate 可能按文字加高，基线再记录更新后的实际尺寸
    if (baseline) {
      const updated = editor.getShape<MindmapNodeShape>(shapeId);
      if (updated) {
        baseline.set(node.id, shapeStateFingerprint(updated.x, updated.y,
          updated.props.w, updated.props.h, editor.getShapeUtil(updated).getText(updated) ?? ""));
      }
    }
    return true;
  }

  const props = shape.props as TLGeoShape["props"];
  const propsUpdate: Partial<TLGeoShape["props"]> = {};
  if (titleChanged) {
    propsUpdate.richText = toRichText(node.title);
  }
  if (props.w !== node.width) {
    propsUpdate.w = node.width;
  }
  if (props.h !== node.height) {
    propsUpdate.h = node.height;
  }
  if (props.geo !== visual.geo) {
    propsUpdate.geo = visual.geo;
  }
  if (props.color !== visual.color) {
    propsUpdate.color = visual.color;
  }
  if (props.fill !== visual.fill) {
    propsUpdate.fill = visual.fill;
  }
  if (props.dash !== visual.dash) {
    propsUpdate.dash = visual.dash;
  }
  if (props.labelColor !== visual.labelColor) {
    propsUpdate.labelColor = visual.labelColor;
  }
  const sizeChanged = propsUpdate.w !== undefined || propsUpdate.h !== undefined;
  if (sizeChanged) {
    // 后端尺寸已经按 tldraw 的标签参数算好，清掉旧尺寸留下的文字溢出高度
    propsUpdate.growY = 0;
  }
  if (!moved && Object.keys(propsUpdate).length === 0) {
    return false;
  }
  // 先更新基线再应用，store 监听在 updateShapes 内同步触发时不会再排队回写；
  // 指纹用生效后的默认视觉，避免 AI 节点每次同步都把默认样式写回后端
  baseline?.set(node.id, shapeStateFingerprint(node.x, node.y, node.width, node.height, node.title,
    visualFingerprint(node)));
  editor.updateShape<TLGeoShape>({
    id: shapeId,
    type: "geo",
    x: node.x,
    y: node.y,
    props: propsUpdate
  });
  if (titleChanged) {
    // 改标题时 tldraw 会按旧高度补 growY，再归零一次
    const reset: TLShapePartial<TLGeoShape>[] = [{ id: shapeId, type: "geo", props: { growY: 0 } }];
    editor.updateShapes(reset);
  }
  return true;
}

/** 创建贝塞尔连线：先建图形与两端绑定，再按节点实际位置写路径 */
function createMindmapEdge(editor: TldrawEditor, edgeId: ShapeId, parentId: ShapeId, childId: ShapeId) {
  editor.createShape<MindmapEdgeShape>({
    id: edgeId,
    type: "mindmap-edge",
    x: 0,
    y: 0,
    isLocked: true,
    props: {
      startX: 0,
      startY: 0,
      cp1X: 12,
      cp1Y: 0,
      cp2X: 148,
      cp2Y: 0,
      endX: 160,
      endY: 0
    }
  });
  createMindmapEdgeBindings(editor, edgeId, parentId, childId);
  updateMindmapEdgeGeometry(editor, edgeId);
}

/** 确保连线绑定存在且指向当前父子节点；节点类型转换会删掉旧绑定，需要重建 */
function ensureMindmapEdgeBindings(
  editor: TldrawEditor,
  edgeId: ShapeId,
  parentId: ShapeId,
  childId: ShapeId
): boolean {
  const bindings = editor.getBindingsFromShape<MindmapEdgeBinding>(edgeId, "mindmap-edge");
  const start = bindings.find((binding) => binding.props.terminal === "start");
  const end = bindings.find((binding) => binding.props.terminal === "end");
  if (start?.toId === parentId && end?.toId === childId) {
    return false;
  }
  if (bindings.length > 0) {
    editor.deleteBindings(bindings.map((binding) => binding.id));
  }
  createMindmapEdgeBindings(editor, edgeId, parentId, childId);
  return true;
}

/** 创建内建箭头连线（树形图 / 流程图沿用原有样式） */
function createArrowEdge(
  editor: TldrawEditor,
  edge: BoardEdge,
  arrowId: ShapeId,
  fromId: ShapeId,
  toId: ShapeId
) {
  // 连线样式：curve→arc 曲线（加大 bend），straight→直线（bend=0）；颜色/粗细按 edge 配置
  const isCurve = edge.style === "curve";
  // tldraw arrow color 只接受内建颜色名（black/blue/green...），不能是 hex 或空串
  const color = mapEdgeColor(edge.color);
  const weight = edge.weight ?? 2;

  editor.createShape({
    id: arrowId,
    type: "arrow",
    isLocked: true,
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
