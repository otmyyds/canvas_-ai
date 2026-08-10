import type { WhiteboardEdge, WhiteboardNode, WhiteboardSpec } from "@/lib/whiteboard/schema";

/** 模板卡片尺寸 */
const W = 340;
const H = 180;
/** 横向间距 */
const GX = 120;
/** 纵向间距 */
const GY = 120;

let seq = 0;
function nextId(prefix: string): string {
  seq += 1;
  return `${prefix}-${seq}`;
}

function resetSeq(): void {
  seq = 0;
}

function card(
  id: string,
  title: string,
  markdown: string,
  cardType: "summary" | "idea" | "source" | "question",
  x: number,
  y: number
): WhiteboardNode {
  return { id, kind: "card", x, y, width: W, height: H, title, markdown, cardType, style: undefined };
}

function arrow(id: string, fromId: string, toId: string, label?: string): WhiteboardEdge {
  return { id, kind: "arrow", fromId, toId, label };
}

export type TemplateId = "mindmap" | "study-plan" | "prd" | "flowchart";

export type WhiteboardTemplate = {
  id: TemplateId;
  name: string;
  description: string;
  build: (rootTitle: string) => WhiteboardSpec;
};

/**
 * 思维导图模板：中心主题 + 若干分支。
 */
const mindmapTemplate: WhiteboardTemplate = {
  id: "mindmap",
  name: "思维导图",
  description: "中心主题向外发散若干分支卡片",
  build: (rootTitle) => {
    resetSeq();
    const root = nextId("node");
    const branches = ["分支一", "分支二", "分支三", "分支四"];
    const nodes: WhiteboardNode[] = [
      card(root, rootTitle || "中心主题", "围绕主题展开思考，用分支记录要点。", "idea", 120, 400)
    ];
    const edges: WhiteboardEdge[] = [];
    let y = 120;
    for (const branch of branches) {
      const id = nextId("node");
      nodes.push(card(id, branch, "记录该分支的要点、例子与延伸问题。", "summary", 120 + W + GX, y));
      edges.push(arrow(nextId("edge"), root, id));
      y += H + GY;
    }
    return { title: rootTitle || "思维导图", nodes, edges, assets: [], notes: [] };
  }
};

/**
 * 学习计划模板：阶段递进，每阶段下有步骤。
 */
const studyPlanTemplate: WhiteboardTemplate = {
  id: "study-plan",
  name: "学习计划",
  description: "阶段 → 步骤的学习路径",
  build: (rootTitle) => {
    resetSeq();
    const stages = ["基础", "进阶", "实战"];
    const root = nextId("node");
    const nodes: WhiteboardNode[] = [
      card(root, rootTitle || "学习计划", "分阶段推进，每阶段包含若干可执行步骤。", "idea", 120, 420)
    ];
    const edges: WhiteboardEdge[] = [];
    const stageIds: string[] = [];
    let y = 120;
    for (const stage of stages) {
      const stageId = nextId("node");
      stageIds.push(stageId);
      nodes.push(card(stageId, `${stage}阶段`, "明确该阶段目标与产出。", "summary", 120 + W + GX, y));
      edges.push(arrow(nextId("edge"), root, stageId));
      let stepY = y;
      for (let i = 1; i <= 2; i++) {
        const stepId = nextId("node");
        nodes.push(card(stepId, `步骤 ${i}`, "列出可执行动作、资源与验收标准。", "idea", 120 + 2 * (W + GX), stepY));
        edges.push(arrow(nextId("edge"), stageId, stepId));
        stepY += H + GY;
      }
      y += 2 * H + 2 * GY;
    }
    void stageIds;
    return { title: rootTitle || "学习计划", nodes, edges, assets: [], notes: [] };
  }
};

/**
 * PRD 模板：目标 → 用户 → 范围 → 里程碑。
 */
const prdTemplate: WhiteboardTemplate = {
  id: "prd",
  name: "产品需求",
  description: "目标 / 用户 / 范围 / 里程碑",
  build: (rootTitle) => {
    resetSeq();
    const sections = [
      { title: "产品目标", markdown: "一句话说清要解决的问题与期望结果。" },
      { title: "目标用户", markdown: "用户画像、核心场景与痛点。" },
      { title: "功能范围", markdown: "MVP 功能清单与边界。" },
      { title: "里程碑", markdown: "关键时间节点与交付物。" }
    ];
    const root = nextId("node");
    const nodes: WhiteboardNode[] = [
      card(root, rootTitle || "产品需求文档", "覆盖目标、用户、范围与里程碑。", "summary", 120, 400)
    ];
    const edges: WhiteboardEdge[] = [];
    let y = 80;
    for (const section of sections) {
      const id = nextId("node");
      nodes.push(card(id, section.title, section.markdown, "idea", 120 + W + GX, y));
      edges.push(arrow(nextId("edge"), root, id));
      y += H + GY;
    }
    return { title: rootTitle || "产品需求文档", nodes, edges, assets: [], notes: [] };
  }
};

/**
 * 流程图模板：开始 → 步骤（可含分支）→ 结束。
 */
const flowchartTemplate: WhiteboardTemplate = {
  id: "flowchart",
  name: "流程图",
  description: "开始 / 处理 / 判断 / 结束",
  build: (rootTitle) => {
    resetSeq();
    const start = nextId("node");
    const step1 = nextId("node");
    const decision = nextId("node");
    const step2 = nextId("node");
    const end = nextId("node");
    const x = 120;
    const nodes: WhiteboardNode[] = [
      card(start, "开始", "流程入口。", "summary", x, 80),
      card(step1, "处理步骤", "描述主要处理动作。", "idea", x, 80 + 1 * (H + GY)),
      card(decision, "判断", "是 / 否分支，流向不同结果。", "question", x, 80 + 2 * (H + GY)),
      card(step2, "另一分支", "处理否分支的结果。", "idea", x + W + GX, 80 + 2 * (H + GY)),
      card(end, "结束", "流程出口。", "summary", x, 80 + 3 * (H + GY))
    ];
    const edges: WhiteboardEdge[] = [
      arrow(nextId("edge"), start, step1),
      arrow(nextId("edge"), step1, decision),
      arrow(nextId("edge"), decision, end, "是"),
      arrow(nextId("edge"), decision, step2, "否"),
      arrow(nextId("edge"), step2, end)
    ];
    void rootTitle;
    return { title: rootTitle || "流程图", nodes, edges, assets: [], notes: [] };
  }
};

export const whiteboardTemplates: WhiteboardTemplate[] = [
  mindmapTemplate,
  studyPlanTemplate,
  prdTemplate,
  flowchartTemplate
];

export function findTemplate(id: string): WhiteboardTemplate | undefined {
  return whiteboardTemplates.find((template) => template.id === id);
}
