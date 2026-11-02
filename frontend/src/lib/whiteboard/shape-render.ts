import { createBindingId, createShapeId, toRichText } from "tldraw";
import type { Editor as TldrawEditor, TLGeoShape, TLShapePartial } from "@tldraw/editor";
import type { BoardEdge, BoardShape } from "@/lib/types";

/** 计算 AI 图形节点的状态指纹（位置/尺寸/标题），用于判断是否被改动 */
export function shapeStateFingerprint(x: number, y: number, width: number, height: number, title: string): string {
  return JSON.stringify([x, y, width, height, title]);
}

/**
 * 把后端图形节点（思维导图/流程图节点）渲染为 tldraw geo shape。
 * shape 类型映射：ellipse→椭圆，rectangle→矩形，oval→胶囊，diamond→菱形。
 * 返回新增数量。编辑器与只读分享页共用。
 */
export function mergeMissingShapes(editor: TldrawEditor, shapes: BoardShape[], edges: BoardEdge[]): number {
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
 * 把后端 shapes 里已存在节点的坐标/尺寸/标题同步到 tldraw。
 * AI 的 update_shape 只改后端行，若不回写标题，画布会一直显示旧文案；
 * 整理画布则只会改坐标。新节点由 mergeMissingShapes 负责创建，这里只更新已有节点。
 * 只同步会回写后端的字段（位置、尺寸、标题），避免覆盖用户手动调整的样式。
 * baseline 传入回写基线时，会先把新状态写进去，避免监听器把这些后端值当成用户编辑再回写一次。
 * 返回被更新的节点数量。
 */
export function syncExistingShapes(editor: TldrawEditor, shapes: BoardShape[],
    baseline?: Map<string, string>): number {
  // 正在输入的节点跳过，避免同步打断用户编辑
  const editingShapeId = editor.getEditingShapeId();
  const updates: TLShapePartial<TLGeoShape>[] = [];
  for (const node of shapes) {
    const shapeId = createShapeId(node.id);
    const shape = editor.getShape(shapeId);
    if (!shape || shape.type !== "geo" || shape.id === editingShapeId) {
      continue;
    }
    const props = shape.props as { w?: number; h?: number };
    const propsUpdate: Partial<TLGeoShape["props"]> = {};
    if (editor.getShapeUtil(shape).getText(shape) !== node.title) {
      propsUpdate.richText = toRichText(node.title);
    }
    if ((props.w ?? 0) !== node.width) {
      propsUpdate.w = node.width;
    }
    if ((props.h ?? 0) !== node.height) {
      propsUpdate.h = node.height;
    }
    const moved = shape.x !== node.x || shape.y !== node.y;
    if (!moved && Object.keys(propsUpdate).length === 0) {
      continue;
    }
    // 先更新基线再应用，store 监听在 updateShapes 内同步触发时不会再排队回写
    baseline?.set(node.id, shapeStateFingerprint(node.x, node.y, node.width, node.height, node.title));
    updates.push({ id: shape.id, type: "geo", x: node.x, y: node.y, props: propsUpdate });
  }
  if (updates.length > 0) {
    editor.updateShapes(updates);
  }
  return updates.length;
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
