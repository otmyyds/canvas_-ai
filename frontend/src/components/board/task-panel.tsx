"use client";

import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Ban, Bot, Loader2, Send, User } from "lucide-react";
import type { AiTask, AiTaskType, AssistResult } from "@/lib/types";
import { Button, GhostButton, Textarea } from "@/components/ui";

type TaskPanelProps = {
  boardId: string;
  tasks: AiTask[];
  connection: string;
  onAssist: (prompt: string) => Promise<AssistResult>;
  onAbortTask: (taskId: string) => Promise<void>;
};

/** 对话消息（user=用户气泡，assistant=AI 回复或任务卡） */
type ChatMessage = {
  role: "user" | "assistant";
  content: string;
  task?: AiTask;
};

/**
 * 快捷任务入口类型。点击按钮只填充提示词前缀，真正的意图判断交给后端分类器。
 */
const QUICK_TASK_TYPES: AiTaskType[] = [
  "GENERATE_MINDMAP",
  "GENERATE_FLOWCHART",
  "STUDY_PLAN",
  "PRODUCT_REQUIREMENT",
  "GENERATE_IMAGE",
  "DOCUMENT_SUMMARY",
  "CANVAS_STRUCTURE"
];

const taskStatusText: Record<string, string> = {
  CREATED: "已创建",
  QUEUED: "排队中",
  RUNNING: "执行中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  ABORTING: "正在中断",
  ABORTED: "已中断"
};

const stepStatusText: Record<string, string> = {
  PENDING: "待执行",
  RUNNING: "执行中",
  DONE: "完成",
  FAILED: "失败",
  ABORTED: "已中断"
};

const connectionText: Record<string, string> = {
  idle: "未连接",
  connecting: "连接中",
  connected: "已连接",
  closed: "已断开",
  error: "连接异常"
};

const activeStatuses = new Set(["CREATED", "QUEUED", "RUNNING", "ABORTING"]);

/** 任务类型 → 中文标签 */
const taskLabel: Record<AiTaskType, string> = {
  DOCUMENT_SUMMARY: "总结资料",
  CANVAS_STRUCTURE: "整理画布",
  CLEAR_CANVAS: "清空画布",
  GENERATE_IMAGE: "生成图片",
  EDIT_IMAGE: "编辑图片",
  GENERATE_COVER: "生成封面",
  GENERATE_DIAGRAM_ASSET: "生成图解",
  STUDY_PLAN: "学习计划",
  PRODUCT_REQUIREMENT: "产品需求",
  GENERATE_MINDMAP: "思维导图",
  GENERATE_FLOWCHART: "流程图"
};

/** 会话持久化 key（按画布隔离） */
const sessionKey = (boardId: string) => `dc_chat_${boardId}`;

type PersistedMessage = {
  role: "user" | "assistant";
  content: string;
};

function loadSession(boardId: string): ChatMessage[] {
  try {
    const raw = window.localStorage.getItem(sessionKey(boardId));
    if (!raw) {
      return [];
    }
    const list = JSON.parse(raw) as PersistedMessage[];
    return list.map((m) => ({ role: m.role, content: m.content }));
  } catch {
    return [];
  }
}

/**
 * 右侧 AI 助手面板（对话式交互）。
 *
 * 交互模型：
 * - 用户输入 → onAssist 统一提交后端，由后端意图分类器路由：
 *   a) 分类为任务 → 返回 kind=task，父组件刷新 tasks，TaskCard 自动追加
 *   b) 分类为聊天 → 返回 kind=chat，追加 assistant 文本气泡
 * - 对话流中嵌入 TaskCard（任务进度/步骤/中断），与聊天消息混合展示
 *
 * 数据流：
 * 1. 用户输入 → submit() 调 onAssist(prompt)
 * 2. onAssist 调后端 /assist：意图分类 + 分流（聊天/任务）
 * 3. kind=chat → 追加文本气泡；kind=task → 父组件刷新 tasks
 * 4. 新任务出现（tasks[0] 变化）→ 追加一条含 TaskCard 的 assistant 消息
 *
 * 持久化：对话文本按画布隔离存 localStorage（dc_chat_{boardId}），刷新不丢；
 * 任务卡不持久化（任务本身在后端有记录）。
 *
 * @param boardId 画布 id（会话持久化 key 用）
 * @param tasks 任务列表（驱动 TaskCard 进度）
 * @param connection WebSocket 连接状态
 * @param onAssist 统一提交回调（父组件封装，调后端 /assist）
 * @param onAbortTask 中断任务回调
 */
export function TaskPanel({ boardId, tasks, connection, onAssist, onAbortTask }: TaskPanelProps) {
  const [messages, setMessages] = useState<ChatMessage[]>(() => loadSession(boardId));
  const [prompt, setPrompt] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();
  const listRef = useRef<HTMLDivElement>(null);
  const activeTask = useMemo(() => tasks.find((task) => activeStatuses.has(task.status)), [tasks]);
  // 已展示的任务 id，避免任务重复出现
  const shownTaskIdsRef = useRef<Set<string>>(new Set());

  // 切换画布时重置会话
  useEffect(() => {
    setMessages(loadSession(boardId));
    shownTaskIdsRef.current = new Set();
  }, [boardId]);

  // 持久化消息（仅持久化 user/assistant 文本，任务卡不持久化）
  useEffect(() => {
    const persisted: PersistedMessage[] = messages
      .filter((m) => !m.task)
      .map((m) => ({ role: m.role, content: m.content }));
    try {
      window.localStorage.setItem(sessionKey(boardId), JSON.stringify(persisted));
    } catch {
      // 存储失败忽略
    }
  }, [boardId, messages]);

  // 新任务出现时追加一条 AI 消息（去重）
  useEffect(() => {
    const latest = tasks[0];
    if (!latest || shownTaskIdsRef.current.has(latest.id)) {
      return;
    }
    shownTaskIdsRef.current.add(latest.id);
    setMessages((prev) => [...prev, { role: "assistant", content: latest.prompt, task: latest }]);
  }, [tasks]);

  // 滚动到底部
  useEffect(() => {
    listRef.current?.scrollTo({ top: listRef.current.scrollHeight });
  }, [messages]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    const text = prompt.trim();
    if (!text) {
      return;
    }
    setMessages((prev) => [...prev, { role: "user", content: text }]);
    setPrompt("");
    setSubmitting(true);
    setError(undefined);
    try {
      // 统一交给后端意图分类：chat 纯文本 / task 落卡任务
      const result = await onAssist(text);
      if (result.kind === "chat") {
        setMessages((prev) => [...prev, { role: "assistant", content: result.reply }]);
      }
      // kind=task 时父组件刷新 tasks，TaskCard 经 useEffect 自动追加，无需手动处理
    } catch (err) {
      setError(err instanceof Error ? err.message : "操作失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <aside className="flex w-96 shrink-0 flex-col border-l border-ink/10 bg-white">
      <div className="border-b border-ink/10 px-4 py-3">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2">
            <Bot className="h-4 w-4 text-spruce" />
            <h2 className="text-sm font-semibold text-ink">AI 助手</h2>
          </div>
          <span className="text-xs text-ink/50">连接：{connectionText[connection] ?? connection}</span>
        </div>
      </div>

      <div ref={listRef} className="min-h-0 flex-1 space-y-3 overflow-auto p-3">
        {messages.length === 0 ? (
          <div className="rounded-md border border-dashed border-ink/15 p-4 text-xs leading-5 text-ink/50">
            你好，我是画布 AI 助手。直接输入你的需求，例如：
            <br />· 总结当前资料的核心内容
            <br />· 生成「人工智能学习路线」思维导图
            <br />· 画一个用户注册流程图
            <br />· 整理当前画布结构
          </div>
        ) : null}

        {messages.map((message, index) => {
          // 从最新 tasks 里找对应任务，保证状态实时同步（而非 push 时的快照）
          const latestTask = message.task ? tasks.find((t) => t.id === message.task!.id) ?? message.task : undefined;
          return (
            <div key={index} className="space-y-2">
              {message.role === "user" ? (
                <div className="flex justify-end">
                  <div className="max-w-[85%] rounded-xl rounded-br-sm bg-spruce px-3 py-2 text-sm text-white">
                    {message.content}
                  </div>
                </div>
              ) : (
                <div className="flex gap-2">
                  <div className="mt-0.5 shrink-0">
                    <User className="h-3.5 w-3.5 text-spruce" />
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="text-xs text-ink/60">{message.content}</p>
                    {latestTask ? <TaskCard task={latestTask} onAbort={onAbortTask} /> : null}
                  </div>
                </div>
              )}
            </div>
          );
        })}
      </div>

      <div className="border-t border-ink/10 p-3">
        {/* 任务快捷入口 */}
        <div className="mb-2 flex flex-wrap gap-1">
          {QUICK_TASK_TYPES.map((type) => (
            <button
              key={type}
              type="button"
              disabled={submitting || Boolean(activeTask)}
              onClick={() => {
                setPrompt(`${taskLabel[type] ?? type}：`);
              }}
              className="rounded border border-ink/10 bg-mist px-2 py-1 text-[11px] text-ink/70 transition hover:border-spruce/40 hover:text-spruce disabled:opacity-50"
            >
              {taskLabel[type] ?? type}
            </button>
          ))}
        </div>
        {activeTask ? (
          <div className="mb-2 rounded-md border border-spruce/20 bg-spruce/5 px-3 py-2 text-xs text-spruce">
            有任务执行中（{activeTask.currentStep ?? "准备中"} {activeTask.progress}%）...
          </div>
        ) : null}
        <form onSubmit={submit} className="flex items-end gap-2">
          <Textarea
            rows={2}
            value={prompt}
            onChange={(event) => setPrompt(event.target.value)}
            placeholder="输入你的需求，回车发送（Shift+Enter 换行）"
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                submit(event);
              }
            }}
          />
          <div className="flex shrink-0 flex-col gap-1">
            <Button
              className="h-9 w-9 px-0"
              disabled={submitting || Boolean(activeTask)}
              title="发送"
              type="submit"
            >
              {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
            </Button>
            {activeTask ? (
              <GhostButton className="h-9 w-9 px-0" type="button" onClick={() => onAbortTask(activeTask.id)} title="中断">
                <Ban className="h-4 w-4" />
              </GhostButton>
            ) : null}
          </div>
        </form>
        {error ? <p className="mt-2 text-xs text-coral">{error}</p> : null}
      </div>
    </aside>
  );
}

function TaskCard({ task, onAbort }: { task: AiTask; onAbort: (taskId: string) => Promise<void> }) {
  const totalSteps = task.steps.length;
  const currentStepIndex = task.steps.findIndex((s) => s.status === "RUNNING" || s.status === "PENDING");
  const currentIndex = currentStepIndex >= 0 ? currentStepIndex + 1 : task.steps.filter((s) => s.status === "DONE").length;

  return (
    <div className="mt-2 rounded-md border border-ink/10 bg-mist p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-ink">{taskStatusText[task.status] ?? task.status}</span>
        {activeStatuses.has(task.status) && totalSteps > 0 ? (
          <span className="text-[11px] text-spruce">第 {currentIndex} / {totalSteps} 步</span>
        ) : null}
        {activeStatuses.has(task.status) ? (
          <button type="button" onClick={() => void onAbort(task.id)} className="text-[11px] text-coral hover:underline">
            中断
          </button>
        ) : null}
      </div>

      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-white">
        <div className="h-full rounded-full bg-spruce transition-all" style={{ width: `${task.progress}%` }} />
      </div>

      {/* 横向流水线步骤条 */}
      {task.steps.length > 0 ? (
        <div className="mt-3 flex items-center gap-1 overflow-x-auto">
          {task.steps.map((step, index) => (
            <div key={step.id} className="flex items-center gap-1">
              <div className="flex min-w-0 flex-col items-center gap-1">
                <span
                  className={`flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] ${
                    step.status === "DONE"
                      ? "bg-spruce text-white"
                      : step.status === "RUNNING"
                        ? "bg-spruce/15 text-spruce"
                        : "bg-ink/10 text-ink/40"
                  }`}
                >
                  {step.status === "DONE" ? "✓" : step.status === "RUNNING" ? "…" : index + 1}
                </span>
                <span
                  className={`whitespace-nowrap text-[10px] ${
                    step.status === "RUNNING" ? "font-medium text-spruce" : "text-ink/50"
                  }`}
                >
                  {step.title}
                </span>
              </div>
              {index < task.steps.length - 1 ? (
                <span className="mb-4 text-ink/20">─</span>
              ) : null}
            </div>
          ))}
        </div>
      ) : null}

      {/* 当前步骤实时 message */}
      {task.steps.find((s) => s.status === "RUNNING")?.message ? (
        <p className="mt-2 animate-pulse text-[11px] leading-4 text-spruce">
          {task.steps.find((s) => s.status === "RUNNING")?.message}
        </p>
      ) : null}

      {task.errorMessage ? <p className="mt-2 text-xs text-coral">{task.errorMessage}</p> : null}
    </div>
  );
}
