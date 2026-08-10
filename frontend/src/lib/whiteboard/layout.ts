import type { WhiteboardEdge, WhiteboardNode, WhiteboardSpec } from "@/lib/whiteboard/schema";

/** 节点默认尺寸 */
const CARD_WIDTH = 340;
const CARD_HEIGHT = 220;
/** 相邻节点横向间距 */
const H_GAP = 80;
/** 相邻层纵向间距 */
const V_GAP = 100;
/** 节点距画布原点边距 */
const MARGIN = 120;

/**
 * 简单自动布局：
 * 1. 依据连线把节点分成若干连通分量；
 * 2. 每个分量内部按拓扑层级排布（分层横向，层内纵向错开）；
 * 3. 无连线的孤立节点按 3 列网格排布；
 * 4. 分量之间按列拼接，避免重叠。
 * 返回新的 spec（仅调整坐标，不改变节点内容与连线）。
 */
export function autoLayout(spec: WhiteboardSpec): WhiteboardSpec {
  const nodes = spec.nodes ?? [];
  if (nodes.length === 0) {
    return spec;
  }
  const edges = spec.edges ?? [];
  const nodeById = new Map(nodes.map((node) => [node.id, node]));

  const { components, isolated } = splitComponents(nodes, edges);

  const placed = new Map<string, { x: number; y: number }>();
  let cursorX = MARGIN;

  for (const component of components) {
    const { positions, maxX } = layoutComponent(component, nodeById, edges, cursorX);
    positions.forEach((pos, id) => placed.set(id, pos));
    cursorX = maxX + 120;
  }

  // 孤立节点网格（3 列）
  let col = 0;
  let row = 0;
  for (const id of isolated) {
    const node = nodeById.get(id);
    const w = node?.width ?? CARD_WIDTH;
    const h = node?.height ?? CARD_HEIGHT;
    placed.set(id, {
      x: cursorX + col * (w + H_GAP),
      y: MARGIN + row * (h + V_GAP)
    });
    col += 1;
    if (col >= 3) {
      col = 0;
      row += 1;
    }
  }

  const result: WhiteboardNode[] = nodes.map((node) => {
    const pos = placed.get(node.id) ?? node;
    return { ...node, x: pos.x, y: pos.y };
  });

  return { ...spec, nodes: result };
}

/**
 * 根据连线切分节点：返回有连线的连通分量与孤立节点。
 */
function splitComponents(
  nodes: WhiteboardNode[],
  edges: WhiteboardEdge[]
): { components: string[][]; isolated: string[] } {
  const adj = new Map<string, Set<string>>();
  for (const node of nodes) {
    adj.set(node.id, new Set());
  }
  for (const edge of edges) {
    if (adj.has(edge.fromId)) {
      adj.get(edge.fromId)!.add(edge.toId);
    }
    if (adj.has(edge.toId)) {
      adj.get(edge.toId)!.add(edge.fromId);
    }
  }

  const visited = new Set<string>();
  const components: string[][] = [];
  for (const node of nodes) {
    if (visited.has(node.id)) {
      continue;
    }
    const queue = [node.id];
    visited.add(node.id);
    const component: string[] = [];
    while (queue.length > 0) {
      const current = queue.shift()!;
      component.push(current);
      for (const neighbor of adj.get(current) ?? []) {
        if (!visited.has(neighbor)) {
          visited.add(neighbor);
          queue.push(neighbor);
        }
      }
    }
    components.push(component);
  }

  const isolated: string[] = [];
  const connected: string[][] = [];
  for (const component of components) {
    const hasEdge = component.some((id) => (adj.get(id)?.size ?? 0) > 0);
    if (component.length === 1 && !hasEdge) {
      isolated.push(component[0]);
    } else {
      connected.push(component);
    }
  }
  return { components: connected, isolated };
}

/**
 * 对单个连通分量做 BFS 分层布局：根节点（入度为 0）在最左列，子节点逐列向右。
 */
function layoutComponent(
  component: string[],
  nodeById: Map<string, WhiteboardNode>,
  edges: WhiteboardEdge[],
  startX: number
): { positions: Map<string, { x: number; y: number }>; maxX: number } {
  const positions = new Map<string, { x: number; y: number }>();
  const inComponent = new Set(component);
  const inDegree = new Map<string, number>(component.map((id) => [id, 0]));
  for (const edge of edges) {
    if (edge.fromId !== edge.toId && inComponent.has(edge.fromId) && inComponent.has(edge.toId)) {
      inDegree.set(edge.toId, (inDegree.get(edge.toId) ?? 0) + 1);
    }
  }

  // BFS 分层：从入度为 0 的根开始
  const layerOf = new Map<string, number>();
  const roots = component.filter((id) => (inDegree.get(id) ?? 0) === 0);
  const queue = [...roots];
  roots.forEach((id) => layerOf.set(id, 0));
  while (queue.length > 0) {
    const current = queue.shift()!;
    const currentLayer = layerOf.get(current) ?? 0;
    for (const edge of edges) {
      if (edge.fromId === current && inComponent.has(edge.toId) && !layerOf.has(edge.toId)) {
        layerOf.set(edge.toId, currentLayer + 1);
        queue.push(edge.toId);
      }
    }
  }
  // 环内剩余节点回退到第 0 层
  for (const id of component) {
    if (!layerOf.has(id)) {
      layerOf.set(id, 0);
    }
  }

  // 按层分桶，层间横向推进
  const layerKeys = [...new Set(layerOf.values())].sort((a, b) => a - b);
  let x = startX;
  let maxX = startX;
  for (const layerKey of layerKeys) {
    const ids = component.filter((id) => layerOf.get(id) === layerKey);
    let y = MARGIN;
    let layerMaxX = x;
    for (const id of ids) {
      const node = nodeById.get(id);
      const w = node?.width ?? CARD_WIDTH;
      const h = node?.height ?? CARD_HEIGHT;
      positions.set(id, { x, y });
      layerMaxX = Math.max(layerMaxX, x + w);
      y += h + V_GAP;
    }
    maxX = Math.max(maxX, layerMaxX);
    x = layerMaxX + H_GAP;
  }
  return { positions, maxX };
}
