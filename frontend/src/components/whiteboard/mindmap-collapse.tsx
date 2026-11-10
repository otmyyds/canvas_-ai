"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import { createShapeId } from "tldraw";
import type { Editor as TldrawEditor } from "tldraw";
import type { BoardEdge, BoardShape } from "@/lib/types";

/**
 * 逻辑图折叠控件（仿 XMind）：
 * - 鼠标移到节点附近（节点包围盒外扩一圈）时，在节点外侧、连线起点旁显示圆形折叠按钮；
 * - 折叠后的节点常驻显示后代数量角标，点击即可展开；
 * - 只渲染覆盖层，不参与 tldraw 图形体系，节点位置变化时按相机变换实时换算屏幕坐标。
 *
 * 悬浮判定不用 editor.getShapeAtPoint：它默认跳过锁定图形，且只认精确命中，
 * 指针从节点移向按钮的途中容易丢 hover 导致按钮消失。这里改成按屏幕包围盒判断。
 */

type MindmapCollapseProps = {
  editor: TldrawEditor | null;
  shapes: BoardShape[];
  edges: BoardEdge[];
  /** 请求进行中时禁用点击，避免重复提交 */
  busy: boolean;
  onToggle: (shapeId: string, collapsed: boolean) => void;
};

/** 按钮中心与节点边缘的屏幕像素间距 */
const HANDLE_GAP = 12;
/** 悬浮判定在节点包围盒外扩的屏幕像素：要盖住按钮（间距 + 半径）并留出手感 */
const HOVER_PADDING = 26;
/**
 * 按钮随画布缩放的上下限。
 * 按钮是固定屏幕尺寸的覆盖层，缩放到多棵树时节点间距按比例变小，固定 24px 的角标会互相重叠；
 * 因此按相机缩放同步缩小，并保留 0.5 的下限，避免缩得太小无法点击。
 */
const MIN_HANDLE_SCALE = 0.5;
const MAX_HANDLE_SCALE = 1;

/** 每个节点的直接子节点；只走父 → 子方向的连线 */
function childrenOf(edges: BoardEdge[]): Map<string, string[]> {
  const result = new Map<string, string[]>();
  for (const edge of edges) {
    const children = result.get(edge.fromShapeId) ?? [];
    children.push(edge.toShapeId);
    result.set(edge.fromShapeId, children);
  }
  return result;
}

/** 节点的全部后代数量，用于折叠角标显示隐藏了多少节点 */
function descendantCount(children: Map<string, string[]>, rootId: string): number {
  const visited = new Set<string>([rootId]);
  const queue = [...(children.get(rootId) ?? [])];
  while (queue.length > 0) {
    const current = queue.shift() as string;
    if (visited.has(current)) {
      continue;
    }
    visited.add(current);
    queue.push(...(children.get(current) ?? []));
  }
  return visited.size - 1;
}

/** 单个节点的按钮布局：容器内坐标 + 客户端坐标的悬浮判定区 */
type HandleLayout = {
  id: string;
  left: number;
  top: number;
  collapsed: boolean;
  /** 随相机缩放的比例，1 表示原始大小 */
  scale: number;
  /** 客户端坐标（clientX/clientY）下的悬浮判定矩形 */
  zone: { left: number; top: number; right: number; bottom: number };
};

export function MindmapCollapse({ editor, shapes, edges, busy, onToggle }: MindmapCollapseProps) {
  const [hoveredId, setHoveredId] = useState<string | null>(null);
  const [tick, setTick] = useState(0);

  const children = useMemo(() => childrenOf(edges), [edges]);

  // 相机平移/缩放与节点移动后重新计算按钮位置
  useEffect(() => {
    if (!editor) {
      return;
    }
    const unlisten = editor.store.listen(() => setTick((value) => value + 1), { scope: "all" });
    return () => unlisten();
  }, [editor]);

  const handles = useMemo<HandleLayout[]>(() => {
    if (!editor) {
      return [];
    }
    // tick 只是为了在相机/图形变化后触发重算
    void tick;
    const rect = editor.getContainer().getBoundingClientRect();
    // 角标与间距一起按相机缩放，节点密集时不会互相压住
    const zoom = editor.getZoomLevel();
    const scale = Math.min(MAX_HANDLE_SCALE, Math.max(MIN_HANDLE_SCALE, zoom));
    const gap = HANDLE_GAP * scale;
    const layouts: HandleLayout[] = [];
    for (const node of shapes) {
      const nodeChildren = children.get(node.id) ?? [];
      if (nodeChildren.length === 0) {
        continue;
      }
      const bounds = editor.getShapePageBounds(createShapeId(node.id));
      if (!bounds) {
        continue;
      }
      const childBounds = nodeChildren
        .map((childId) => editor.getShapePageBounds(createShapeId(childId)))
        .filter((box): box is NonNullable<typeof box> => Boolean(box));
      // 子节点在节点的哪一侧：优先取可见子节点中心均值；
      // 折叠后子节点不在画布上，改用节点相对父节点的位置判断，与逻辑图左右分侧一致
      let toRight: boolean;
      if (childBounds.length > 0) {
        const averageX = childBounds.reduce((sum, box) => sum + box.center.x, 0) / childBounds.length;
        toRight = averageX >= bounds.center.x;
      } else {
        const parentEdge = edges.find((edge) => edge.toShapeId === node.id);
        const parentBounds = parentEdge
          ? editor.getShapePageBounds(createShapeId(parentEdge.fromShapeId))
          : undefined;
        toRight = !parentBounds || bounds.center.x >= parentBounds.center.x;
      }
      const edgePoint = editor.pageToScreen({
        x: toRight ? bounds.maxX : bounds.minX,
        y: bounds.center.y
      });
      const screenX = edgePoint.x + (toRight ? gap : -gap);
      const screenTopLeft = editor.pageToScreen({ x: bounds.minX, y: bounds.minY });
      const screenBottomRight = editor.pageToScreen({ x: bounds.maxX, y: bounds.maxY });
      layouts.push({
        id: node.id,
        left: screenX - rect.left,
        top: edgePoint.y - rect.top,
        collapsed: Boolean(node.collapsed),
        scale,
        zone: {
          left: Math.min(screenTopLeft.x, screenX) - HOVER_PADDING,
          top: screenTopLeft.y - HOVER_PADDING,
          right: Math.max(screenBottomRight.x, screenX) + HOVER_PADDING,
          bottom: screenBottomRight.y + HOVER_PADDING
        }
      });
    }
    return layouts;
  }, [editor, shapes, edges, children, tick]);

  // 指针位置判定走最新一次渲染的布局，避免闭包拿到旧坐标
  const handlesRef = useRef(handles);
  handlesRef.current = handles;

  // 窗口级监听 + 捕获阶段：按钮盖在画布上方时也能持续跟踪指针
  useEffect(() => {
    if (!editor) {
      return;
    }
    const handleMove = (event: PointerEvent) => {
      const { clientX, clientY } = event;
      const hit = handlesRef.current.find(
        (handle) =>
          clientX >= handle.zone.left &&
          clientX <= handle.zone.right &&
          clientY >= handle.zone.top &&
          clientY <= handle.zone.bottom
      );
      setHoveredId(hit ? hit.id : null);
    };
    const handleLeave = () => setHoveredId(null);
    window.addEventListener("pointermove", handleMove, true);
    window.addEventListener("pointerleave", handleLeave);
    return () => {
      window.removeEventListener("pointermove", handleMove, true);
      window.removeEventListener("pointerleave", handleLeave);
    };
  }, [editor]);

  // 折叠加常驻显示，展开节点只在悬浮时显示
  const visible = handles.filter((handle) => handle.collapsed || handle.id === hoveredId);
  if (!editor || visible.length === 0) {
    return null;
  }

  return (
    <div className="pointer-events-none absolute inset-0 z-20">
      {visible.map((handle) => {
        const count = handle.collapsed ? descendantCount(children, handle.id) : 0;
        return (
          <button
            key={handle.id}
            type="button"
            disabled={busy}
            title={handle.collapsed ? `展开 ${count} 个后代节点` : "折叠子节点"}
            className="pointer-events-auto absolute flex h-6 min-w-6 items-center justify-center rounded-full border border-[#808080]/45 bg-white px-1 text-[10px] font-medium text-[#5a5a5a] shadow-sm transition hover:border-spruce hover:text-spruce disabled:opacity-50"
            style={{
              left: handle.left,
              top: handle.top,
              transform: `translate(-50%, -50%) scale(${handle.scale})`
            }}
            onPointerDown={(event) => event.stopPropagation()}
            onClick={(event) => {
              event.stopPropagation();
              onToggle(handle.id, !handle.collapsed);
            }}
          >
            {handle.collapsed ? count : "−"}
          </button>
        );
      })}
    </div>
  );
}
