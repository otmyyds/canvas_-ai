import type { Editor as TldrawEditor, TLArrowBinding, TLGeoShape, TLShapeId } from "tldraw";
import type { BoardEdge, BoardShape } from "@/lib/types";
import { api } from "@/lib/api";
import { deleteShapesForced } from "@/lib/whiteboard/shape-render";

/**
 * 纳管画布上手绘的自由图形与箭头：
 * 在切换逻辑图/树形图前，把非结构节点（id 不是 UUID）的 geo 图形及其箭头转成后端结构，
 * 保持原坐标尺寸和视觉属性，然后删除本地元素，由布局同步统一渲染。
 * 纳管不触发布局，随后的布局切换会按新结构重排。
 */

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function rawId(shapeId: string | TLShapeId): string {
  return String(shapeId).replace(/^shape:/, "");
}

/** 后端连线构成的连通分量里，哪些属于思维导图（含 level > 0，或只有单个待展开的根节点） */
function mindmapComponents(shapes: BoardShape[], edges: BoardEdge[]): Set<string> {
  const adjacency = new Map<string, Set<string>>();
  const levelById = new Map<string, number>();
  for (const shape of shapes) {
    adjacency.set(shape.id, new Set());
    levelById.set(shape.id, shape.level);
  }
  for (const edge of edges) {
    const from = adjacency.get(edge.fromShapeId);
    const to = adjacency.get(edge.toShapeId);
    if (from && to) {
      from.add(edge.toShapeId);
      to.add(edge.fromShapeId);
    }
  }
  const mindmap = new Set<string>();
  const visited = new Set<string>();
  for (const shape of shapes) {
    if (visited.has(shape.id)) {
      continue;
    }
    const component: string[] = [];
    const stack = [shape.id];
    visited.add(shape.id);
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
    // 单节点分量视为待展开的思维导图根，与后端新建节点的层级规则保持一致
    if (component.length === 1 || component.some((id) => (levelById.get(id) ?? 0) > 0)) {
      component.forEach((id) => mindmap.add(id));
    }
  }
  return mindmap;
}

export type AdoptionContext = { shapes: BoardShape[]; edges: BoardEdge[] };

/**
 * 扫描并纳管自由图形。返回纳管节点数；没有可纳管内容时不发请求。
 * 调用方负责在结束后重新拉取画布数据。
 */
export async function adoptFreeStructures(
  editor: TldrawEditor,
  boardId: string,
  context: AdoptionContext
): Promise<number> {
  const backendShapeIds = new Set(context.shapes.map((shape) => shape.id));
  const backendShapeById = new Map(context.shapes.map((shape) => [shape.id, shape]));
  const backendEdgeIds = new Set(context.edges.map((edge) => edge.id));
  const pageId = editor.getCurrentPageId();

  // 自由节点：当前页直属、非结构 id 的 geo 图形
  const freeNodes = new Map<string, TLGeoShape>();
  for (const shape of editor.getCurrentPageShapes()) {
    if (shape.type !== "geo" || shape.parentId !== pageId) {
      continue;
    }
    if (UUID_PATTERN.test(rawId(shape.id))) {
      continue;
    }
    freeNodes.set(String(shape.id), shape as TLGeoShape);
  }
  if (freeNodes.size === 0) {
    return 0;
  }

  type FreeArrow = { shapeId: TLShapeId; from: string; to: string; fromFree: boolean; toFree: boolean };
  const freeArrows: FreeArrow[] = [];
  for (const shape of editor.getCurrentPageShapes()) {
    if (shape.type !== "arrow") {
      continue;
    }
    const id = rawId(shape.id);
    // 结构箭头由后端 edges 生成，只纳管用户手绘箭头（非 UUID id）
    if (UUID_PATTERN.test(id) || backendEdgeIds.has(id)) {
      continue;
    }
    const bindings = editor.getBindingsFromShape<TLArrowBinding>(shape.id, "arrow");
    const start = bindings.find((binding) => binding.props.terminal === "start");
    const end = bindings.find((binding) => binding.props.terminal === "end");
    if (!start || !end) {
      continue;
    }
    const fromKey = String(start.toId);
    const toKey = String(end.toId);
    const fromFree = freeNodes.has(fromKey);
    const toFree = freeNodes.has(toKey);
    if (!fromFree && !toFree) {
      continue;
    }
    // 自由节点用完整 shape id 作为本地 id；结构节点用后端 uuid
    const from = fromFree ? fromKey : (backendShapeIds.has(rawId(fromKey)) ? rawId(fromKey) : null);
    const to = toFree ? toKey : (backendShapeIds.has(rawId(toKey)) ? rawId(toKey) : null);
    if (!from || !to) {
      continue;
    }
    freeArrows.push({ shapeId: shape.id, from, to, fromFree, toFree });
  }

  // 层级：挂在结构父节点下时延续父层级；独立自由簇按思维导图给根 0、子 1
  const mindmap = mindmapComponents(context.shapes, context.edges);
  const anchorParent = new Map<string, string>();
  const freeParent = new Map<string, string>();
  const childrenOf = new Map<string, string[]>();
  for (const arrow of freeArrows) {
    if (!arrow.fromFree && arrow.toFree) {
      anchorParent.set(arrow.to, arrow.from);
    }
    if (arrow.fromFree && arrow.toFree) {
      freeParent.set(arrow.to, arrow.from);
      const children = childrenOf.get(arrow.from) ?? [];
      children.push(arrow.to);
      childrenOf.set(arrow.from, children);
    }
  }
  const levelByNode = new Map<string, number>();
  const queue: { key: string; level: number; propagate: boolean }[] = [];
  for (const key of freeNodes.keys()) {
    if (freeParent.has(key)) {
      continue;
    }
    const parentId = anchorParent.get(key);
    const parentShape = parentId ? backendShapeById.get(parentId) : undefined;
    if (parentShape) {
      const isMindmap = mindmap.has(parentShape.id);
      const start = isMindmap ? parentShape.level + 1 : parentShape.level;
      levelByNode.set(key, start);
      queue.push({ key, level: start, propagate: isMindmap });
    } else {
      levelByNode.set(key, 0);
      queue.push({ key, level: 0, propagate: true });
    }
  }
  while (queue.length > 0) {
    const current = queue.shift() as { key: string; level: number; propagate: boolean };
    for (const child of childrenOf.get(current.key) ?? []) {
      if (levelByNode.has(child)) {
        continue;
      }
      const level = current.propagate ? current.level + 1 : current.level;
      levelByNode.set(child, level);
      queue.push({ key: child, level, propagate: current.propagate });
    }
  }

  const nodes = [...freeNodes.entries()].map(([key, shape]) => {
    const bounds = editor.getShapePageBounds(shape.id);
    const props = shape.props as TLGeoShape["props"];
    const title = editor.getShapeUtil(shape).getText(shape) ?? "";
    return {
      localId: key,
      title: title.trim() || "节点",
      shape: props.geo,
      level: levelByNode.get(key) ?? 0,
      x: bounds?.minX ?? shape.x,
      y: bounds?.minY ?? shape.y,
      width: bounds?.width ?? props.w,
      height: bounds?.height ?? props.h,
      visual: {
        geo: props.geo,
        color: props.color,
        fill: props.fill,
        dash: props.dash
      }
    };
  });

  await api.adoptStructure(boardId, {
    nodes,
    edges: freeArrows.map((arrow) => ({ fromLocalId: arrow.from, toLocalId: arrow.to }))
  });

  // 本地自由图形与箭头删除后，后续同步按后端结构渲染
  deleteShapesForced(editor, [
    ...([...freeNodes.keys()] as TLShapeId[]),
    ...freeArrows.map((arrow) => arrow.shapeId)
  ]);
  return nodes.length;
}
