import { T } from "@tldraw/validate";
import {
  BaseBoxShapeUtil,
  BindingUtil,
  Box,
  FONT_FAMILIES,
  HTMLContainer,
  PathBuilder,
  Rectangle2d,
  RichTextLabel,
  RichTextSVG,
  ShapeUtil,
  SVGContainer,
  TEXT_PROPS,
  createBindingId,
  defaultBindingUtils,
  defaultShapeUtils,
  renderHtmlFromRichTextForMeasurement,
  renderPlaintextFromRichText,
  useDefaultColorTheme,
  useValue
} from "tldraw";
import type {
  Editor as TldrawEditor,
  Geometry2d,
  TLBaseBinding,
  TLBaseShape,
  TLRichText,
  TLShapeId
} from "tldraw";

/**
 * 逻辑图（Freeplane fork 风格）专用的自定义图形：
 * - mindmap-node：无边框纯文字节点，尺寸随标题收缩；
 * - mindmap-edge：三次贝塞尔连线，灰色 1px、无箭头，两端绑定到节点侧边中点。
 * 只在 LOGIC 布局的思维导图分量里使用，树形图与流程图仍用 geo/arrow。
 */

/** fork 节点的字体参数，必须与后端 NodeSizeEstimator.estimateFork 完全一致 */
const FORK_FONT_SIZE = 22;
const FORK_LINE_HEIGHT = TEXT_PROPS.lineHeight;
const FORK_PADDING = 8;
/** 贝塞尔控制点水平偏移，对齐 Freeplane 的 XCTRL / CHILD_XCTRL */
const PARENT_CTRL = 12;
const CHILD_CTRL = 20;
/** 连线固定颜色（Freeplane 默认边色） */
const EDGE_COLOR = "#808080";

const richTextValidator = T.object({ type: T.string, content: T.arrayOf(T.unknown) });

/** 节点属性：宽高 + 富文本，与 geo 的 richText 结构一致 */
export type MindmapNodeShape = TLBaseShape<
  "mindmap-node",
  { w: number; h: number; richText: TLRichText }
>;

/** 连线属性：路径点相对图形左上角，便于整体平移 */
export type MindmapEdgeShape = TLBaseShape<
  "mindmap-edge",
  {
    startX: number;
    startY: number;
    cp1X: number;
    cp1Y: number;
    cp2X: number;
    cp2Y: number;
    endX: number;
    endY: number;
  }
>;

/** 连线绑定：terminal 区分起止两端 */
export type MindmapEdgeBinding = TLBaseBinding<"mindmap-edge", { terminal: "start" | "end" }>;

/**
 * fork 节点文字渲染：用独立函数组件承载 hooks。
 * ShapeUtil.component 是普通方法，直接写 hooks 会被 eslint 规则误判，也不利于 hook 顺序稳定。
 */
function MindmapNodeLabel({ editor, shape }: { editor: TldrawEditor; shape: MindmapNodeShape }) {
  const theme = useDefaultColorTheme();
  const isSelected = useValue(
    "mindmap-node-selected",
    () => editor.getOnlySelectedShapeId() === shape.id,
    [editor, shape.id]
  );
  return (
    <RichTextLabel
      shapeId={shape.id}
      type={shape.type}
      font="draw"
      fontSize={FORK_FONT_SIZE}
      lineHeight={FORK_LINE_HEIGHT}
      padding={FORK_PADDING}
      align="middle"
      verticalAlign="middle"
      richText={shape.props.richText}
      isSelected={isSelected}
      labelColor={theme.black.solid}
      wrap
    />
  );
}

/** 拖拽中的子树：记录后代节点在拖拽开始时的位置 */
type SubtreeDrag = { positions: Map<TLShapeId, { x: number; y: number }> };
const subtreeDrags = new WeakMap<TldrawEditor, SubtreeDrag>();

/**
 * 收集节点在逻辑图里的所有后代：沿着 mindmap-edge 的 start（父）→ end（子）向下遍历。
 * 只走连线关系，不依赖 level，用户手动新建的节点同样适用。
 */
function collectDescendantIds(editor: TldrawEditor, rootId: TLShapeId): TLShapeId[] {
  const childrenOf = new Map<string, string[]>();
  for (const shape of editor.getCurrentPageShapes()) {
    if (shape.type !== "mindmap-edge") {
      continue;
    }
    const bindings = editor.getBindingsFromShape<MindmapEdgeBinding>(shape.id, "mindmap-edge");
    const start = bindings.find((binding) => binding.props.terminal === "start");
    const end = bindings.find((binding) => binding.props.terminal === "end");
    if (!start || !end) {
      continue;
    }
    const key = String(start.toId);
    const children = childrenOf.get(key) ?? [];
    children.push(String(end.toId));
    childrenOf.set(key, children);
  }
  const result: TLShapeId[] = [];
  const visited = new Set<string>([String(rootId)]);
  const queue = [String(rootId)];
  while (queue.length > 0) {
    const current = queue.shift() as string;
    for (const child of childrenOf.get(current) ?? []) {
      if (!visited.has(child)) {
        visited.add(child);
        result.push(child as TLShapeId);
        queue.push(child);
      }
    }
  }
  return result;
}

/** 拖拽开始时记录子树位置；多选或没有后代时不跟随 */
function beginSubtreeDrag(editor: TldrawEditor, shape: MindmapNodeShape): SubtreeDrag | null {
  if (editor.getSelectedShapeIds().length !== 1) {
    return null;
  }
  const selected = new Set(editor.getSelectedShapeIds().map(String));
  const positions = new Map<TLShapeId, { x: number; y: number }>();
  for (const id of collectDescendantIds(editor, shape.id)) {
    if (selected.has(String(id))) {
      continue;
    }
    const descendant = editor.getShape(id);
    if (descendant) {
      positions.set(id, { x: descendant.x, y: descendant.y });
    }
  }
  return positions.size > 0 ? { positions } : null;
}

/** 按拖拽总位移移动后代节点，连线由绑定的重算负责跟随 */
function moveSubtree(editor: TldrawEditor, initial: MindmapNodeShape, current: MindmapNodeShape) {
  const drag = subtreeDrags.get(editor);
  if (!drag) {
    return;
  }
  const dx = current.x - initial.x;
  const dy = current.y - initial.y;
  editor.updateShapes(
    [...drag.positions].map(([id, position]) => ({
      id,
      type: "mindmap-node" as const,
      x: position.x + dx,
      y: position.y + dy
    }))
  );
}

/** 取消拖拽时把后代放回原位 */
function resetSubtree(editor: TldrawEditor) {
  const drag = subtreeDrags.get(editor);
  if (!drag) {
    return;
  }
  editor.updateShapes(
    [...drag.positions].map(([id, position]) => ({
      id,
      type: "mindmap-node" as const,
      x: position.x,
      y: position.y
    }))
  );
}

/** fork 节点：只渲染文字，双击原地编辑 */
export class MindmapNodeShapeUtil extends BaseBoxShapeUtil<MindmapNodeShape> {
  static type = "mindmap-node" as const;

  static props = {
    w: T.number,
    h: T.number,
    richText: richTextValidator
  };

  getDefaultProps(): MindmapNodeShape["props"] {
    return { w: 100, h: 46, richText: { type: "doc", content: [] } };
  }

  getGeometry(shape: MindmapNodeShape): Geometry2d {
    return new Rectangle2d({ width: shape.props.w, height: shape.props.h, isFilled: true });
  }

  /** 双击进入文字编辑（RichTextLabel 内部走 useEditableRichText） */
  canEdit(): boolean {
    return true;
  }

  /** 固定格式：不允许拖拽改尺寸，尺寸由后端按标题重算 */
  canResize(): boolean {
    return false;
  }

  /** 拖动 fork 节点时整棵子树跟随，避免父子连线被拉断 */
  onTranslateStart(shape: MindmapNodeShape): void {
    const drag = beginSubtreeDrag(this.editor, shape);
    if (drag) {
      subtreeDrags.set(this.editor, drag);
    } else {
      subtreeDrags.delete(this.editor);
    }
  }

  onTranslate(initial: MindmapNodeShape, current: MindmapNodeShape): void {
    moveSubtree(this.editor, initial, current);
  }

  onTranslateEnd(): void {
    subtreeDrags.delete(this.editor);
  }

  onTranslateCancel(): void {
    resetSubtree(this.editor);
    subtreeDrags.delete(this.editor);
  }

  getText(shape: MindmapNodeShape): string {
    return renderPlaintextFromRichText(this.editor, shape.props.richText);
  }

  /**
   * 用户改名时按当前宽度重新测量文字高度，必要时加高盒子，避免文字被裁切。
   * 宽度随后端重排修正，这里只处理高度。
   */
  onBeforeUpdate(prev: MindmapNodeShape, next: MindmapNodeShape): MindmapNodeShape | void {
    if (prev.props.richText === next.props.richText) {
      return;
    }
    const html = renderHtmlFromRichTextForMeasurement(this.editor, next.props.richText);
    const measured = this.editor.textMeasure.measureHtml(html, {
      ...TEXT_PROPS,
      fontFamily: FONT_FAMILIES.draw,
      fontSize: FORK_FONT_SIZE,
      maxWidth: Math.max(1, next.props.w - FORK_PADDING * 2),
      padding: "0px"
    });
    const minHeight = Math.ceil(measured.h) + FORK_PADDING * 2;
    if (next.props.h < minHeight) {
      return { ...next, props: { ...next.props, h: minHeight } };
    }
  }

  component(shape: MindmapNodeShape) {
    return (
      <HTMLContainer
        style={{ width: shape.props.w, height: shape.props.h, overflow: "hidden" }}
      >
        <MindmapNodeLabel editor={this.editor} shape={shape} />
      </HTMLContainer>
    );
  }

  indicator(shape: MindmapNodeShape) {
    return <rect width={shape.props.w} height={shape.props.h} />;
  }

  toSvg(shape: MindmapNodeShape) {
    return (
      <RichTextSVG
        bounds={new Box(0, 0, shape.props.w, shape.props.h)}
        richText={shape.props.richText}
        fontSize={FORK_FONT_SIZE}
        font="draw"
        align="middle"
        verticalAlign="middle"
        wrap
        labelColor="#000000"
        padding={FORK_PADDING}
        showTextOutline={false}
      />
    );
  }
}

/** 生成连线的 SVG path 数据 */
function edgePathData(props: MindmapEdgeShape["props"]): string {
  return `M ${props.startX} ${props.startY} C ${props.cp1X} ${props.cp1Y} ${props.cp2X} ${props.cp2Y} ${props.endX} ${props.endY}`;
}

/** 贝塞尔连线：无箭头、固定灰色 1px，曲线跟随绑定节点实时重算 */
export class MindmapEdgeShapeUtil extends ShapeUtil<MindmapEdgeShape> {
  static type = "mindmap-edge" as const;

  static props = {
    startX: T.number,
    startY: T.number,
    cp1X: T.number,
    cp1Y: T.number,
    cp2X: T.number,
    cp2Y: T.number,
    endX: T.number,
    endY: T.number
  };

  getDefaultProps(): MindmapEdgeShape["props"] {
    return { startX: 0, startY: 0, cp1X: 12, cp1Y: 0, cp2X: 148, cp2Y: 0, endX: 160, endY: 0 };
  }

  /** 连线几何必须用真实三次贝塞尔，不要用包围盒近似 */
  getGeometry(shape: MindmapEdgeShape): Geometry2d {
    const { startX, startY, cp1X, cp1Y, cp2X, cp2Y, endX, endY } = shape.props;
    return new PathBuilder()
      .moveTo(startX, startY)
      .cubicBezierTo(endX, endY, cp1X, cp1Y, cp2X, cp2Y)
      .toGeometry();
  }

  canBind(): boolean {
    return true;
  }

  canResize(): boolean {
    return false;
  }

  hideResizeHandles(): boolean {
    return true;
  }

  component(shape: MindmapEdgeShape) {
    return (
      <SVGContainer style={{ minWidth: 50, minHeight: 50 }}>
        <path
          d={edgePathData(shape.props)}
          fill="none"
          stroke={EDGE_COLOR}
          strokeWidth={1}
          strokeLinecap="round"
        />
      </SVGContainer>
    );
  }

  indicator(shape: MindmapEdgeShape) {
    return <path d={edgePathData(shape.props)} />;
  }

  toSvg(shape: MindmapEdgeShape) {
    return (
      <path
        d={edgePathData(shape.props)}
        fill="none"
        stroke={EDGE_COLOR}
        strokeWidth={1}
        strokeLinecap="round"
      />
    );
  }
}

/** 连线绑定：节点变化时重算曲线 */
export class MindmapEdgeBindingUtil extends BindingUtil<MindmapEdgeBinding> {
  static type = "mindmap-edge" as const;

  static props = {
    terminal: T.literalEnum("start", "end")
  };

  getDefaultProps(): MindmapEdgeBinding["props"] {
    return { terminal: "start" };
  }

  onAfterChangeToShape({ binding }: { binding: MindmapEdgeBinding }) {
    updateMindmapEdgeGeometry(this.editor, binding.fromId);
  }
}

/** 取连线两端的绑定；缺少任一端返回 null */
function edgeBindings(editor: TldrawEditor, edgeShapeId: TLShapeId) {
  const bindings = editor.getBindingsFromShape<MindmapEdgeBinding>(edgeShapeId, "mindmap-edge");
  const start = bindings.find((item) => item.props.terminal === "start");
  const end = bindings.find((item) => item.props.terminal === "end");
  if (!start || !end) {
    return null;
  }
  return { start, end };
}

/**
 * 按父子节点的包围盒重算贝塞尔曲线：
 * 起止点取朝向对方一侧的边中点，控制点沿水平方向偏移，与 Freeplane BezierEdgeView 一致。
 * 几何没有变化时不写回，避免 store 更新循环。
 */
export function updateMindmapEdgeGeometry(editor: TldrawEditor, edgeShapeId: TLShapeId): boolean {
  const edgeShape = editor.getShape<MindmapEdgeShape>(edgeShapeId);
  if (!edgeShape || edgeShape.type !== "mindmap-edge") {
    return false;
  }
  const bindings = edgeBindings(editor, edgeShapeId);
  if (!bindings) {
    return false;
  }
  const parentBounds = editor.getShapePageBounds(bindings.start.toId);
  const childBounds = editor.getShapePageBounds(bindings.end.toId);
  if (!parentBounds || !childBounds) {
    return false;
  }
  // 逻辑图始终左右展开：子节点在右侧就用父右中点连子左中点，否则镜像
  const dir = childBounds.center.x >= parentBounds.center.x ? 1 : -1;
  const start = {
    x: dir > 0 ? parentBounds.maxX : parentBounds.minX,
    y: parentBounds.center.y
  };
  const end = {
    x: dir > 0 ? childBounds.minX : childBounds.maxX,
    y: childBounds.center.y
  };
  const cp1 = { x: start.x + dir * PARENT_CTRL, y: start.y };
  const cp2 = { x: end.x - dir * CHILD_CTRL, y: end.y };

  const minX = Math.min(start.x, end.x, cp1.x, cp2.x);
  const minY = Math.min(start.y, end.y, cp1.y, cp2.y);
  const props = {
    startX: start.x - minX,
    startY: start.y - minY,
    cp1X: cp1.x - minX,
    cp1Y: cp1.y - minY,
    cp2X: cp2.x - minX,
    cp2Y: cp2.y - minY,
    endX: end.x - minX,
    endY: end.y - minY
  };
  const current = edgeShape.props;
  const sameProps = (Object.keys(props) as (keyof typeof props)[]).every(
    (key) => Math.abs(current[key] - props[key]) < 0.01
  );
  if (sameProps && Math.abs(edgeShape.x - minX) < 0.01 && Math.abs(edgeShape.y - minY) < 0.01) {
    return false;
  }
  editor.updateShape<MindmapEdgeShape>({
    id: edgeShapeId,
    type: "mindmap-edge",
    x: minX,
    y: minY,
    props
  });
  return true;
}

/** 为连线建立父子两端的绑定 */
export function createMindmapEdgeBindings(
  editor: TldrawEditor,
  edgeShapeId: TLShapeId,
  parentShapeId: TLShapeId,
  childShapeId: TLShapeId
) {
  editor.createBindings<MindmapEdgeBinding>([
    {
      id: createBindingId(),
      type: "mindmap-edge",
      fromId: edgeShapeId,
      toId: parentShapeId,
      props: { terminal: "start" }
    },
    {
      id: createBindingId(),
      type: "mindmap-edge",
      fromId: edgeShapeId,
      toId: childShapeId,
      props: { terminal: "end" }
    }
  ]);
}

/** 编辑器与分享页共用的 shape/binding 集合 */
export const whiteboardShapeUtils = [
  ...defaultShapeUtils,
  MindmapNodeShapeUtil,
  MindmapEdgeShapeUtil
];
export const whiteboardBindingUtils = [...defaultBindingUtils, MindmapEdgeBindingUtil];
