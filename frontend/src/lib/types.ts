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

/** 卡片类型（AI 生成或手动创建） */
export type CardType = "NOTE" | "SUMMARY" | "IDEA" | "SOURCE" | "QUESTION" | "IMAGE" | "PROMPT";

/** 知识卡片（画布内容单元） */
export type BoardCard = {
  id: string;
  boardId: string;
  title: string;
  /** Markdown 正文（卡片渲染为 markdown） */
  contentMarkdown: string;
  cardType: CardType;
  x: number;
  y: number;
  width: number;
  height: number;
  createdAt: string;
  updatedAt: string;
};

/** 连线（连接两个卡片/图形节点） */
export type BoardEdge = {
  id: string;
  boardId: string;
  fromCardId: string;
  toCardId: string;
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

/** AI 任务类型（决定生成方式与 prompt 约束） */
export type AiTaskType =
  | "DOCUMENT_SUMMARY"
  | "CANVAS_STRUCTURE"
  | "GENERATE_IMAGE"
  | "EDIT_IMAGE"
  | "GENERATE_COVER"
  | "GENERATE_DIAGRAM_ASSET"
  | "STUDY_PLAN"
  | "PRODUCT_REQUIREMENT"
  | "GENERATE_MINDMAP"
  | "GENERATE_FLOWCHART"
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

/** AI 助手统一响应：kind=chat 返回纯文本，kind=task 返回落卡任务 */
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
 * create_card 建卡片，update_card 更新，connect 连线，create_group 分组。
 */
export type BoardAction =
  | {
      type: "create_card";
      title: string;
      markdown?: string;
      cardType?: string;
      x: number;
      y: number;
      width?: number;
      height?: number;
      sourceFileIds?: string[];
    }
  | {
      type: "update_card";
      cardId: string;
      title?: string;
      markdown?: string;
    }
  | {
      type: "connect";
      fromCardId: string;
      toCardId: string;
      label?: string;
    }
  | {
      type: "create_group";
      title: string;
      cardIds: string[];
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
