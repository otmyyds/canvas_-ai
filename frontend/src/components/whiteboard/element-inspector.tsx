"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { RotateCw } from "lucide-react";
import type { Editor as TldrawEditor } from "@tldraw/editor";
import { GhostButton } from "@/components/ui";

type ElementInspectorProps = {
  editor: TldrawEditor | null;
};

/** 几何图形类型 */
const GEO_TYPES = [
  "rectangle",
  "ellipse",
  "triangle",
  "diamond",
  "pentagon",
  "hexagon",
  "octagon",
  "star",
  "rhombus",
  "trapezoid",
  "cloud",
  "heart",
  "oval"
];

/** 线型 */
const DASH_STYLES = ["solid", "dashed", "dotted", "draw"] as const;

/** 箭头起终点样式 */
const ARROW_HEADS = ["none", "arrow", "triangle", "square", "dot", "diamond", "inverted", "bar", "pipe"] as const;

/** 颜色 */
const COLORS = [
  "black",
  "blue",
  "green",
  "grey",
  "light-blue",
  "light-green",
  "light-red",
  "light-violet",
  "orange",
  "red",
  "violet",
  "yellow"
];

type SelectedInfo = {
  count: number;
  geo?: string;
  dash?: string;
  arrowStart?: string;
  arrowEnd?: string;
  color?: string;
  width: number;
  height: number;
};

const dashLabel: Record<string, string> = {
  solid: "实线",
  dashed: "虚线",
  dotted: "点线",
  draw: "手绘"
};

export function ElementInspector({ editor }: ElementInspectorProps) {
  const [selected, setSelected] = useState<SelectedInfo | null>(null);

  useEffect(() => {
    if (!editor) {
      return;
    }
    const sync = () => {
      setSelected(inspect(editor));
    };
    sync();
    const unlisten = editor.store.listen(sync, { scope: "document" });
    return () => unlisten();
  }, [editor]);

  const patch = useCallback(
    (patchProps: Record<string, unknown>) => {
      if (!editor) {
        return;
      }
      const ids = editor.getSelectedShapeIds();
      if (ids.length === 0) {
        return;
      }
      editor.updateShapes(
        ids.map((id) => {
          const shape = editor.getShape(id);
          if (!shape) {
            return null;
          }
          const isArrow = shape.type === "arrow";
          const props = { ...(shape.props as Record<string, unknown>), ...patchProps };
          // 箭头样式与常规样式分开处理
          const normalized = isArrow ? props : props;
          void normalized;
          return { id, type: shape.type, props };
        })
      );
    },
    [editor]
  );

  const setSize = useCallback(
    (dim: "w" | "h", value: number) => {
      patch({ [dim]: Math.max(40, value) });
    },
    [patch]
  );

  const setRotate = useCallback(
    (delta: number) => {
      if (!editor) {
        return;
      }
      editor.rotateShapesBy(editor.getSelectedShapeIds(), delta);
    },
    [editor]
  );

  // 拖拽旋转：按住旋钮，角度跟随鼠标相对 shape 中心的变化
  const dragRef = useRef<{ lastAngle: number } | null>(null);
  const startRotateDrag = useCallback(
    (event: React.PointerEvent) => {
      if (!editor) {
        return;
      }
      const ids = editor.getSelectedShapeIds();
      if (ids.length === 0) {
        return;
      }
      event.preventDefault();
      (event.target as HTMLElement).setPointerCapture(event.pointerId);
      const center = editor.getShapePageTransform(ids[0]).point();
      const startPoint = editor.screenToPage({ x: event.clientX, y: event.clientY });
      dragRef.current = {
        lastAngle: Math.atan2(startPoint.y - center.y, startPoint.x - center.x)
      };
    },
    [editor]
  );

  const moveRotateDrag = useCallback(
    (event: React.PointerEvent) => {
      if (!editor || !dragRef.current) {
        return;
      }
      const ids = editor.getSelectedShapeIds();
      if (ids.length === 0) {
        return;
      }
      const center = editor.getShapePageTransform(ids[0]).point();
      const pagePoint = editor.screenToPage({ x: event.clientX, y: event.clientY });
      const angle = Math.atan2(pagePoint.y - center.y, pagePoint.x - center.x);
      const delta = angle - dragRef.current.lastAngle;
      dragRef.current.lastAngle = angle;
      editor.rotateShapesBy(ids, delta);
    },
    [editor]
  );

  const endRotateDrag = useCallback((event: React.PointerEvent) => {
    (event.target as HTMLElement).releasePointerCapture(event.pointerId);
    dragRef.current = null;
  }, []);

  if (!selected || selected.count === 0) {
    return null;
  }

  const canShape = Boolean(selected.geo);
  const canArrow = Boolean(selected.arrowStart || selected.arrowEnd);
  const canColor = Boolean(selected.color);

  return (
    <div className="absolute bottom-4 left-1/2 z-20 w-[26rem] -translate-x-1/2 rounded-md border border-ink/10 bg-white/95 p-3 text-xs text-ink shadow-md">
      <div className="mb-2 flex items-center justify-between">
        <span className="font-medium">元素属性</span>
        <span className="text-ink/45">已选 {selected.count} 个</span>
      </div>

      <div className="space-y-2">
        {canShape ? (
          <div className="flex flex-wrap items-center gap-1">
            <span className="mr-1 w-8 text-ink/55">图形</span>
            {GEO_TYPES.map((geo) => (
              <button
                key={geo}
                type="button"
                onClick={() => patch({ geo })}
                className={`rounded px-1.5 py-0.5 capitalize hover:bg-mist ${
                  selected.geo === geo ? "bg-spruce/15 text-spruce" : "text-ink/70"
                }`}
              >
                {geo}
              </button>
            ))}
          </div>
        ) : null}

        {canArrow ? (
          <div className="flex flex-wrap items-center gap-1">
            <span className="mr-1 w-8 text-ink/55">线型</span>
            {DASH_STYLES.map((dash) => (
              <button
                key={dash}
                type="button"
                onClick={() => patch({ dash })}
                className={`rounded px-1.5 py-0.5 hover:bg-mist ${
                  selected.dash === dash ? "bg-spruce/15 text-spruce" : "text-ink/70"
                }`}
              >
                {dashLabel[dash] ?? dash}
              </button>
            ))}
          </div>
        ) : null}

        {canArrow ? (
          <div className="flex flex-wrap items-center gap-1">
            <span className="mr-1 w-8 text-ink/55">箭头</span>
            {ARROW_HEADS.map((head) => (
              <button
                key={head}
                type="button"
                onClick={() => patch({ arrowheadStart: head, arrowheadEnd: head })}
                className={`rounded px-1.5 py-0.5 hover:bg-mist ${
                  selected.arrowEnd === head ? "bg-spruce/15 text-spruce" : "text-ink/70"
                }`}
              >
                {head}
              </button>
            ))}
          </div>
        ) : null}

        {canColor ? (
          <div className="flex flex-wrap items-center gap-1.5">
            <span className="mr-1 w-8 text-ink/55">颜色</span>
            {COLORS.map((color) => (
              <button
                key={color}
                type="button"
                onClick={() => patch({ color })}
                className={`h-4 w-4 rounded-full border ${
                  selected.color === color ? "ring-2 ring-spruce" : "border-ink/20"
                }`}
                style={{ backgroundColor: colorHex(color) }}
                title={color}
              />
            ))}
          </div>
        ) : null}

        <div className="flex items-center gap-2">
          <span className="w-8 text-ink/55">旋转</span>
          <button
            type="button"
            className="inline-flex h-8 w-8 items-center justify-center rounded-full border border-ink/15 bg-white text-ink/60 transition hover:border-spruce/50 hover:text-spruce active:bg-spruce/10"
            title="按住并拖动旋转，跟随鼠标角度"
            onPointerDown={startRotateDrag}
            onPointerMove={moveRotateDrag}
            onPointerUp={endRotateDrag}
            onPointerCancel={endRotateDrag}
          >
            <RotateCw className="h-4 w-4" />
          </button>
          <span className="text-[11px] text-ink/45">按住拖动</span>
          <GhostButton type="button" onClick={() => setRotate(Math.PI / 4)} className="ml-auto">
            45°
          </GhostButton>
          <GhostButton type="button" onClick={() => setRotate(-Math.PI / 4)}>
            -45°
          </GhostButton>
        </div>

        <div className="flex items-center gap-2">
          <span className="w-8 text-ink/55">尺寸</span>
          <label className="flex items-center gap-1">
            宽
            <input
              type="number"
              value={Math.round(selected.width)}
              min={40}
              className="w-16 rounded border border-ink/15 px-1 py-0.5"
              onChange={(event) => setSize("w", Number(event.target.value))}
            />
          </label>
          <label className="flex items-center gap-1">
            高
            <input
              type="number"
              value={Math.round(selected.height)}
              min={40}
              className="w-16 rounded border border-ink/15 px-1 py-0.5"
              onChange={(event) => setSize("h", Number(event.target.value))}
            />
          </label>
        </div>
      </div>
    </div>
  );
}

function inspect(editor: TldrawEditor): SelectedInfo | null {
  const ids = editor.getSelectedShapeIds();
  if (ids.length === 0) {
    return null;
  }
  const shapes = ids
    .map((id) => editor.getShape(id))
    .filter((shape): shape is NonNullable<typeof shape> => Boolean(shape));
  if (shapes.length === 0) {
    return null;
  }
  const first = shapes[0];
  // 逻辑图 fork 节点/贝塞尔连线是固定格式，没有可调样式，不显示样式面板
  if (first.type !== "geo" && first.type !== "arrow") {
    return null;
  }
  const props = first.props as Record<string, unknown>;
  const width = typeof props.w === "number" ? props.w : 340;
  const height = typeof props.h === "number" ? props.h : 220;
  return {
    count: shapes.length,
    geo: typeof props.geo === "string" ? props.geo : undefined,
    dash: typeof props.dash === "string" ? props.dash : undefined,
    arrowStart: typeof props.arrowheadStart === "string" ? props.arrowheadStart : undefined,
    arrowEnd: typeof props.arrowheadEnd === "string" ? props.arrowheadEnd : undefined,
    color: typeof props.color === "string" ? props.color : undefined,
    width,
    height
  };
}

function colorHex(color: string): string {
  const map: Record<string, string> = {
    black: "#000000",
    blue: "#4263eb",
    green: "#2f9e44",
    grey: "#868e96",
    "light-blue": "#74c0fc",
    "light-green": "#b2f2bb",
    "light-red": "#ffc9c9",
    "light-violet": "#d0bfff",
    orange: "#f76707",
    red: "#e03131",
    violet: "#7048e8",
    yellow: "#fdd835"
  };
  return map[color] ?? "#000000";
}
