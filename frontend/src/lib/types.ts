/**
 * 前端全局类型定义。
 * 与后端 API 返回的 JSON 结构一一对应（后端 DTO 镜像）。
 * 改动时需与后端 DTO 保持同步。
 */

/** 画布：私有/只读分享 */
export type Board = {
  id: string;
  title: string;
  visibility: "PRIVATE" | "SHARED_READONLY";
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

/** AI 任务类型（决定生成方式与 prompt 约束） */
export type AiTaskType =
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

/** 任务步骤（加载技能/收集上下文/规划操作/应用操作） */
export type AiTaskStep = {
  id: string;
  title: string;
  status: "PENDING" | "RUNNING" | "DONE" | "FAILED" | "ABORTED";
  progress: number;
  message?: string;
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
};

/** AI 助手统一响应：kind=chat 返回纯文本，kind=task 返回落图形节点任务 */
export type AssistResult =
  | { kind: "chat"; reply: string }
  | { kind: "task"; task: AiTask };

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
 * create_shape 建图形节点，connect 连线，delete_shape 删节点。
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
  error?: string;
  emittedAt: string;
};
