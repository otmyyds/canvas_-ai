import type { Editor as TldrawEditor, TLShapeId } from "tldraw";
import type { BoardEdge, BoardShape } from "@/lib/types";
import type { MindmapEdgeBinding, MindmapNodeShape } from "@/lib/whiteboard/mindmap-shapes";

/**
 * 逻辑图拖拽排序：节点在逻辑图中不能自由移动，拖动等价于调整同级顺序。
 * 拖动过程中按当前指针位置计算目标顺序并显示插入线，松手后提交给后端重排。
 * 一级分支（父节点 level 为 0）还会根据左右半区决定分支固定侧。
 */

export type EdgeSide = "LEFT" | "RIGHT";

/** 目标顺序中的一项：连线 id + 该子节点在一级分支中的目标侧 */
export type ReorderChild = { edgeId: string; childId: string; side: EdgeSide | null };

/** 一次拖拽的排序结果；changed 为 false 时松手应还原位置 */
export type ReorderPlan = {
  parentId: string;
  /** 目标侧内插入位置对应的页面坐标插入线；没有兄弟节点时为 null */
  indicator: { x1: number; x2: number; y: number } | null;
  children: ReorderChild[];
  changed: boolean;
};

/** 当前画布的结构数据，由 whiteboard-editor 同步进来 */
export type StructureContext = { shapes: BoardShape[]; edges: BoardEdge[] };

const contexts = new WeakMap<TldrawEditor, StructureContext>();
const commits = new WeakMap<TldrawEditor, (plan: ReorderPlan) => void>();

export function setStructureContext(editor: TldrawEditor, context: StructureContext): void {
  contexts.set(editor, context);
}

/** 注册松手提交回调：由编辑器调用后端并刷新画布 */
export function setReorderCommit(editor: TldrawEditor, commit: (plan: ReorderPlan) => void): void {
  commits.set(editor, commit);
}

export function clearReorderCommit(editor: TldrawEditor): void {
  commits.delete(editor);
}

/** tldraw 的 shape id（shape:uuid）转后端 uuid */
function uuidOf(shapeId: string | TLShapeId): string {
  return String(shapeId).replace(/^shape:/, "");
}

/** 找节点的父节点：沿 mindmap-edge 的 end → start 反向解析 */
export function findParent(
  editor: TldrawEditor,
  nodeId: TLShapeId
): { parentId: string } | null {
  for (const binding of editor.getBindingsToShape<MindmapEdgeBinding>(nodeId, "mindmap-edge")) {
    if (binding.props.terminal !== "end") {
      continue;
    }
    const starts = editor.getBindingsFromShape<MindmapEdgeBinding>(binding.fromId, "mindmap-edge");
    const start = starts.find((item) => item.props.terminal === "start");
    if (start) {
      return { parentId: uuidOf(start.toId) };
    }
  }
  return null;
}

/** 取节点包围盒；节点不存在时返回 null */
function boundsOf(editor: TldrawEditor, shapeId: TLShapeId) {
  return editor.getShapePageBounds(shapeId) ?? null;
}

/** 一级分支当前所在侧：优先后端记录的 side，没有记录时按节点与根的左右相对位置判断 */
function sideOf(editor: TldrawEditor, edge: BoardEdge, parentCenterX: number): EdgeSide {
  if (edge.side === "LEFT" || edge.side === "RIGHT") {
    return edge.side;
  }
  const bounds = boundsOf(editor, `shape:${edge.toShapeId}` as TLShapeId);
  return bounds && bounds.center.x < parentCenterX ? "LEFT" : "RIGHT";
}

/**
 * 根据节点当前拖动位置计算目标顺序。
 * 找不到父节点、结构上下文缺失时返回 null（调用方还原位置）。
 */
export function computeReorder(editor: TldrawEditor, node: MindmapNodeShape): ReorderPlan | null {
  const context = contexts.get(editor);
  if (!context) {
    return null;
  }
  const parent = findParent(editor, node.id);
  if (!parent) {
    return null;
  }
  const parentShape = context.shapes.find((shape) => shape.id === parent.parentId);
  const parentBounds = boundsOf(editor, `shape:${parent.parentId}` as TLShapeId);
  const draggedBounds = boundsOf(editor, node.id);
  if (!parentBounds || !draggedBounds) {
    return null;
  }
  const siblings = context.edges.filter((edge) => edge.fromShapeId === parent.parentId);
  const draggedEdge = siblings.find((edge) => edge.toShapeId === uuidOf(node.id));
  if (!draggedEdge) {
    return null;
  }
  // 只有一级分支提交左右侧；层级更深的节点只在同一父节点的兄弟之间排序
  const rootBranches = (parentShape?.level ?? 0) === 0;
  const draggedId = uuidOf(node.id);
  const draggedSide: EdgeSide | null = rootBranches
    ? draggedBounds.center.x < parentBounds.center.x ? "LEFT" : "RIGHT"
    : null;

  // 目标侧内的其他兄弟保持原顺序，再按纵向中心计算插入点
  const sameSide: BoardEdge[] = [];
  const otherSide: BoardEdge[] = [];
  for (const edge of siblings) {
    if (edge.id === draggedEdge.id) {
      continue;
    }
    if (!rootBranches || sideOf(editor, edge, parentBounds.center.x) === draggedSide) {
      sameSide.push(edge);
    } else {
      otherSide.push(edge);
    }
  }
  const positioned = sameSide
    .map((edge) => ({
      edge,
      bounds: boundsOf(editor, `shape:${edge.toShapeId}` as TLShapeId)
    }))
    .sort((left, right) => (left.bounds?.center.y ?? Number.MAX_VALUE) - (right.bounds?.center.y ?? Number.MAX_VALUE));
  let insertIndex = 0;
  while (insertIndex < positioned.length
      && (positioned[insertIndex].bounds?.center.y ?? Number.MAX_VALUE) < draggedBounds.center.y) {
    insertIndex++;
  }
  const sideChildren: ReorderChild[] = positioned.map((item) => ({
    edgeId: item.edge.id,
    childId: item.edge.toShapeId,
    side: rootBranches ? draggedSide : null
  }));
  sideChildren.splice(insertIndex, 0, {
    edgeId: draggedEdge.id,
    childId: draggedId,
    side: rootBranches ? draggedSide : null
  });

  // 根节点提交顺序统一为右侧在前、左侧在后，侧内顺序与拖动结果一致；非根节点只有一个列表
  let ordered: ReorderChild[] = sideChildren;
  if (rootBranches) {
    const opposite: ReorderChild[] = otherSide.map((edge) => ({
      edgeId: edge.id,
      childId: edge.toShapeId,
      side: sideOf(editor, edge, parentBounds.center.x)
    }));
    ordered = draggedSide === "RIGHT" ? [...sideChildren, ...opposite] : [...opposite, ...sideChildren];
  }

  const originalOrder = siblings.map((edge) => edge.id);
  const newOrder = ordered.map((child) => child.edgeId);
  const orderChanged = originalOrder.some((edgeId, index) => edgeId !== newOrder[index]);
  const originalSide = rootBranches ? sideOf(editor, draggedEdge, parentBounds.center.x) : null;
  const changed = orderChanged || (rootBranches && originalSide !== draggedSide);

  return {
    parentId: parent.parentId,
    children: ordered,
    changed,
    indicator: indicatorFor(positioned, insertIndex)
  };
}

/** 计算插入线：位于相邻兄弟之间，横向覆盖这一侧所有兄弟 */
function indicatorFor(
  siblings: { bounds: { minX: number; maxX: number; minY: number; maxY: number; center: { y: number } } | null }[],
  insertIndex: number
): { x1: number; x2: number; y: number } | null {
  const valid = siblings.filter((item) => item.bounds !== null);
  if (valid.length === 0) {
    return null;
  }
  const minX = Math.min(...valid.map((item) => item.bounds!.minX));
  const maxX = Math.max(...valid.map((item) => item.bounds!.maxX));
  if (insertIndex === 0) {
    return { x1: minX, x2: maxX, y: valid[0].bounds!.minY - 8 };
  }
  if (insertIndex >= valid.length) {
    return { x1: minX, x2: maxX, y: valid[valid.length - 1].bounds!.maxY + 8 };
  }
  const previous = valid[insertIndex - 1].bounds!;
  const next = valid[insertIndex].bounds!;
  return { x1: minX, x2: maxX, y: (previous.maxY + next.minY) / 2 };
}

/** 松手时提交目标顺序；没有注册回调说明当前页只读 */
export function commitReorder(editor: TldrawEditor, plan: ReorderPlan): boolean {
  const commit = commits.get(editor);
  if (!commit) {
    return false;
  }
  commit(plan);
  return true;
}

/** 插入线 DOM：挂在编辑器容器内，随相机变换按屏幕坐标实时更新 */
let indicatorElement: HTMLDivElement | null = null;

export function showReorderIndicator(editor: TldrawEditor, plan: ReorderPlan | null): void {
  if (!plan?.indicator) {
    hideReorderIndicator();
    return;
  }
  const container = editor.getContainer();
  if (!indicatorElement) {
    indicatorElement = document.createElement("div");
    indicatorElement.style.position = "absolute";
    indicatorElement.style.height = "2px";
    indicatorElement.style.borderRadius = "1px";
    indicatorElement.style.background = "#0f766e";
    indicatorElement.style.pointerEvents = "none";
    indicatorElement.style.zIndex = "400";
  }
  if (indicatorElement.parentElement !== container) {
    indicatorElement.remove();
    container.appendChild(indicatorElement);
  }
  // pageToScreen 是相对浏览器窗口的坐标，减去容器位置后才能在容器内绝对定位
  const rect = container.getBoundingClientRect();
  const start = editor.pageToScreen({ x: plan.indicator.x1, y: plan.indicator.y });
  const end = editor.pageToScreen({ x: plan.indicator.x2, y: plan.indicator.y });
  indicatorElement.style.left = `${Math.min(start.x, end.x) - rect.left}px`;
  indicatorElement.style.top = `${start.y - rect.top - 1}px`;
  indicatorElement.style.width = `${Math.abs(end.x - start.x)}px`;
  indicatorElement.style.display = "block";
}

export function hideReorderIndicator(): void {
  if (indicatorElement) {
    indicatorElement.style.display = "none";
  }
}
