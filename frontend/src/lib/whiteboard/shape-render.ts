import { createBindingId, createShapeId, toRichText } from "tldraw";
import type { Editor as TldrawEditor, TLGeoShape, TLShapeId, TLShapePartial } from "@tldraw/editor";
import type { BoardEdge, BoardLayoutStyle, BoardShape } from "@/lib/types";
import {
  createMindmapEdgeBindings,
  updateMindmapEdgeGeometry
} from "@/lib/whiteboard/mindmap-shapes";
import type { MindmapEdgeBinding, MindmapEdgeShape, MindmapNodeShape } from "@/lib/whiteboard/mindmap-shapes";

/** 计算 AI 图形节点的状态指纹（位置/尺寸/标题），用于判断是否被改动 */
export function shapeStateFingerprint(x: number, y: number, width: number, height: number, title: string): string {
  return JSON.stringify([x, y, width, height, title]);
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

/**
 * 把后端图形数据同步到 tldraw：创建缺失元素、转换类型、更新位置/尺寸/标题。
 * 类型由当前布局风格决定：
 * - LOGIC 且属于思维导图分量 → mindmap-node / mindmap-edge（Freeplane fork 风格）；
 * - 其他情况 → geo / arrow（原有样式）。
 * 先处理节点再处理连线，保证节点类型转换删掉的绑定会被重新建立。
 * 返回发生变更的元素数量。
 */
export function syncGraphVisuals(
  editor: TldrawEditor,
  shapes: BoardShape[],
  edges: BoardEdge[],
  layoutStyle: BoardLayoutStyle,
  options: SyncGraphOptions = {}
): number {
  const { baseline, updateExisting = true } = options;
  const forkIds = collectForkNodeIds(shapes, edges, layoutStyle);
  const editingShapeId = editor.getEditingShapeId();
  const shapeIdByNode = new Map<string, ShapeId>();
  let changed = 0;

  for (const node of shapes) {
    const shapeId = createShapeId(node.id);
    shapeIdByNode.set(node.id, shapeId);
    const desiredType = forkIds.has(node.id) ? "mindmap-node" : "geo";
    const existing = editor.getShape(shapeId);
    if (!existing) {
      createGraphShape(editor, node, shapeId, desiredType);
      changed++;
      continue;
    }
    if (existing.type !== desiredType) {
      // 正在输入的节点跳过类型转换，等编辑结束后下一轮再处理
      if (shapeId === editingShapeId) {
        continue;
      }
      editor.deleteShapes([shapeId]);
      createGraphShape(editor, node, shapeId, desiredType);
      changed++;
      continue;
    }
    if (updateExisting && shapeId !== editingShapeId
        && updateGraphShape(editor, node, shapeId, desiredType, baseline)) {
      changed++;
    }
  }

  for (const edge of edges) {
    const fromId = shapeIdByNode.get(edge.fromShapeId) ?? createShapeId(edge.fromShapeId);
    const toId = shapeIdByNode.get(edge.toShapeId) ?? createShapeId(edge.toShapeId);
    if (!editor.getShape(fromId) || !editor.getShape(toId)) {
      continue;
    }
    const edgeId = createShapeId(edge.id);
    const desiredType = layoutStyle === "LOGIC"
      && forkIds.has(edge.fromShapeId)
      && forkIds.has(edge.toShapeId)
      ? "mindmap-edge"
      : "arrow";
    const existing = editor.getShape(edgeId);
    if (!existing || existing.type !== desiredType) {
      if (existing) {
        editor.deleteShapes([edgeId]);
      }
      if (desiredType === "mindmap-edge") {
        createMindmapEdge(editor, edgeId, fromId, toId);
      } else {
        createArrowEdge(editor, edge, edgeId, fromId, toId);
      }
      changed++;
      continue;
    }
    if (desiredType === "mindmap-edge") {
      if (ensureMindmapEdgeBindings(editor, edgeId, fromId, toId)) {
        changed++;
      }
      if (updateMindmapEdgeGeometry(editor, edgeId)) {
        changed++;
      }
    }
  }
  return changed;
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
  const sizeChanged = propsUpdate.w !== undefined || propsUpdate.h !== undefined;
  if (sizeChanged) {
    // 后端尺寸已经按 tldraw 的标签参数算好，清掉旧尺寸留下的文字溢出高度
    propsUpdate.growY = 0;
  }
  if (!moved && Object.keys(propsUpdate).length === 0) {
    return false;
  }
  // 先更新基线再应用，store 监听在 updateShapes 内同步触发时不会再排队回写
  baseline?.set(node.id, shapeStateFingerprint(node.x, node.y, node.width, node.height, node.title));
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
