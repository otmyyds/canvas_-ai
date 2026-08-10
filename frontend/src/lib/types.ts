export type Board = {
  id: string;
  title: string;
  visibility: "PRIVATE" | "SHARED_READONLY";
  createdAt: string;
  updatedAt: string;
};

export type CardType = "NOTE" | "SUMMARY" | "IDEA" | "SOURCE" | "QUESTION" | "IMAGE" | "PROMPT";

export type BoardCard = {
  id: string;
  boardId: string;
  title: string;
  contentMarkdown: string;
  cardType: CardType;
  x: number;
  y: number;
  width: number;
  height: number;
  createdAt: string;
  updatedAt: string;
};

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
  | "GENERATE_FLOWCHART";

export type AiTaskStatus = "CREATED" | "QUEUED" | "RUNNING" | "SUCCEEDED" | "FAILED" | "ABORTING" | "ABORTED";

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
  error?: string;
  emittedAt: string;
};
