/**
 * 前端全局类型定义。
 * 与后端 API 返回的 JSON 结构一一对应（后端 DTO 镜像）。
 * 改动时需与后端 DTO 保持同步。
 */

/** 画布：私有/只读分享 */
/** 思维导图排布风格：RADIAL 树形图（径向）/ LOGIC 逻辑图（从左到右） */
export type BoardLayoutStyle = "RADIAL" | "LOGIC";

export type Board = {
  id: string;
  title: string;
  visibility: "PRIVATE" | "SHARED_READONLY";
  /** 当前画布的思维导图排布风格 */
  layoutStyle: BoardLayoutStyle;
  /** 节点总数超过该值后只允许逻辑图 */
  radialMaxNodes: number;
  createdAt: string;
  updatedAt: string;
};

/** 连线（连接两个图形节点） */
export type BoardEdge = {
  id: string;
  boardId: string;
  fromShapeId: string;
  toShapeId: string;
  label?: string;
  style?: string;
  color?: string;
  weight?: number;
  createdAt: string;
};

/** 图形节点（思维导图/流程图节点，存 shapes 表） */
export type BoardShape = {
  id: string;
  boardId: string;
  title: string;
  shape: string;
  level: number;
  x: number;
  y: number;
  width: number;
  height: number;
};

/** 分享画布的只读数据（标题 + 图形节点 + 连线 + 快照） */
export type SharedBoard = {
  boardId: string;
  title: string;
  shapes: BoardShape[];
  edges: BoardEdge[];
  snapshotJson: string | null;
};

/** AI 生成图片资产（存 assets 表） */
export type BoardAsset = {
  id: string;
  boardId: string;
  mimeType: string;
  width: number | null;
  height: number | null;
  createdAt: string;
};

/** AI 任务类型（决定生成方式与 prompt 约束；AGENT_PLAN 是由多条步骤组成的计划任务） */
export type AiTaskType =
  | "AGENT_PLAN"
  | "DOCUMENT_SUMMARY"
  | "CANVAS_STRUCTURE"
  | "STUDY_PLAN"
  | "PRODUCT_REQUIREMENT"
  | "GENERATE_MINDMAP"
  | "GENERATE_FLOWCHART"
  | "GENERATE_IMAGE"
  | "EDIT_ELEMENT"
  | "CLEAR_CANVAS";

/** AI 任务状态机：创建→排队→执行→成功/失败/中断 */
export type AiTaskStatus = "CREATED" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "ABORTING" | "ABORTED";

/** 步骤状态：SKIPPED 表示因前序步骤失败而未执行 */
export type AiTaskStepStatus = "PENDING" | "RUNNING" | "DONE" | "FAILED" | "ABORTED" | "SKIPPED";

/** 任务步骤（计划中的一步：类型、顺序、用户可读说明、执行结果） */
export type AiTaskStep = {
  id: string;
  /** 步骤序号，从 1 开始 */
  seq: number;
  /** 步骤类型，复用 AiTaskType */
  stepType?: AiTaskType;
  title: string;
  /** 给用户看的本步说明 */
  description?: string;
  status: AiTaskStepStatus;
  progress: number;
  message?: string;
  /** 完成后的实际效果说明 */
  resultMessage?: string;
  /** 失败原因 */
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
};

/** AI 任务完整信息（含步骤列表，前端 TaskCard 渲染用） */
export type AiTask = {
  id: string;
  boardId: string;
  type: AiTaskType;
  prompt: string;
  status: AiTaskStatus;
  progress: number;
  currentStep?: string;
  abortRequested: boolean;
  resultJson?: string;
  errorMessage?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt: string;
  steps: AiTaskStep[];
  /** 顶层运行的模型调用次数（分支/审查用量已计入父运行） */
  modelCalls: number;
  toolCalls: number;
  totalTokens: number;
  /** 分支 Worker 数量 */
  branchCount: number;
};

/** AI 助手统一响应：kind=reply 返回纯文本，kind=task 返回后台执行的任务；sessionId 用于多轮会话 */
export type AssistResult =
  | { kind: "reply"; reply: string; sessionId?: string | null }
  | { kind: "task"; task: AiTask; sessionId?: string | null };

/** 上传资料文件（TXT/MD 等自动解析为可检索文本） */
export type SourceFile = {
  id: string;
  boardId: string;
  storageKey: string;
  filename: string;
  mimeType: string;
  size: number;
  textExcerpt?: string;
  parseStatus: "PENDING" | "PARSING" | "READY" | "FAILED";
  createdAt: string;
};

/**
 * AI 产出/WebSocket 推送的画布动作。
 * create_shape 建图形节点，connect 连线，update_shape 修改节点，delete_shape 删节点。
 */
export type BoardAction =
  | {
      type: "create_shape";
      id?: string;
      title: string;
      shape?: string;
      level?: number;
      x: number;
      y: number;
      width?: number;
      height?: number;
    }
  | {
      type: "connect";
      fromShapeId: string;
      toShapeId: string;
      label?: string;
      style?: string;
      color?: string;
      weight?: number;
    }
  | {
      type: "update_shape";
      shapeId: string;
      title?: string;
      shape?: string;
      level?: number;
    }
  | {
      type: "delete_shape";
      shapeId: string;
    };

/** WebSocket 推送的任务事件（前端 use-task-stream 消费） */
export type TaskEvent = {
  type: "task.created" | "task.step" | "task.progress" | "task.actions" | "task.aborted" | "task.failed" | "task.done";
  taskId: string;
  boardId: string;
  stepId?: string;
  title?: string;
  status?: string;
  progress: number;
  message?: string;
  actions?: BoardAction[];
  /** 任务类型（AiTaskType），清空画布等前端需据此响应（如 CLEAR_CANVAS） */
  taskType?: string;
  /** 步骤类型（任务级事件为空） */
  stepType?: AiTaskType;
  /** 步骤序号（任务级事件为空） */
  seq?: number;
  error?: string;
  emittedAt: string;
};
