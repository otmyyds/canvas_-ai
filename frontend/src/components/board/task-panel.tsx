"use client";

import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { Ban, Bot, Loader2, Send, User } from "lucide-react";
import type { AiTask, AiTaskType } from "@/lib/types";
import { Button, GhostButton, Textarea } from "@/components/ui";

type TaskPanelProps = {
  tasks: AiTask[];
  connection: string;
  onCreateTask: (type: AiTaskType, prompt: string) => Promise<void>;
  onAbortTask: (taskId: string) => Promise<void>;
};

type ChatMessage = {
  role: "user" | "assistant";
  content: string;
  task?: AiTask;
};

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

/** 根据用户输入推断任务类型 */
function inferTaskType(prompt: string): AiTaskType {
  const text = prompt.toLowerCase();
  if (text.includes("思维导图") || text.includes("导图")) return "GENERATE_MINDMAP";
  if (text.includes("流程") || text.includes("flow")) return "GENERATE_FLOWCHART";
  if (text.includes("总结") || text.includes("摘要") || text.includes("概括")) return "DOCUMENT_SUMMARY";
  if (text.includes("学习计划") || text.includes("学习路径")) return "STUDY_PLAN";
  if (text.includes("产品") || text.includes("prd") || text.includes("需求")) return "PRODUCT_REQUIREMENT";
  if (text.includes("图片") || text.includes("生成图")) return "GENERATE_IMAGE";
  if (text.includes("整理") || text.includes("结构")) return "CANVAS_STRUCTURE";
  return "CANVAS_STRUCTURE";
}

export function TaskPanel({ tasks, connection, onCreateTask, onAbortTask }: TaskPanelProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [prompt, setPrompt] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();
  const listRef = useRef<HTMLDivElement>(null);
  const activeTask = useMemo(() => tasks.find((task) => activeStatuses.has(task.status)), [tasks]);

  // 新任务出现时追加一条 AI 消息
  useEffect(() => {
    const latest = tasks[0];
    if (!latest) {
      return;
    }
    const alreadyShown = messages.some((m) => m.task?.id === latest.id);
    if (alreadyShown) {
      return;
    }
    setMessages((prev) => [...prev, { role: "assistant", content: latest.prompt, task: latest }]);
  }, [tasks, messages]);

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
    const type = inferTaskType(text);
    setMessages((prev) => [...prev, { role: "user", content: text }]);
    setPrompt("");
    setSubmitting(true);
    setError(undefined);
    try {
      await onCreateTask(type, text);
    } catch (err) {
      setError(err instanceof Error ? err.message : "创建任务失败");
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

        {messages.map((message, index) => (
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
                  {message.task ? <TaskCard task={message.task} onAbort={onAbortTask} /> : null}
                </div>
              </div>
            )}
          </div>
        ))}
      </div>

      <div className="border-t border-ink/10 p-3">
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
  const doneSteps = task.steps.filter((s) => s.status === "DONE" || s.status === "FAILED").length;
  const currentStepIndex = task.steps.findIndex((s) => s.status === "RUNNING" || s.status === "PENDING");
  const currentIndex = currentStepIndex >= 0 ? currentStepIndex + 1 : doneSteps;

  return (
    <div className="mt-2 rounded-md border border-ink/10 bg-mist p-3">
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-ink">
          {taskStatusText[task.status] ?? task.status}
        </span>
        {task.status === "RUNNING" && totalSteps > 0 ? (
          <span className="text-[11px] text-spruce">
            第 {currentIndex} / {totalSteps} 步
          </span>
        ) : null}
        {activeStatuses.has(task.status) ? (
          <button
            type="button"
            onClick={() => void onAbort(task.id)}
            className="text-[11px] text-coral hover:underline"
          >
            中断
          </button>
        ) : null}
      </div>
      <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-white">
        <div className="h-full rounded-full bg-spruce" style={{ width: `${task.progress}%` }} />
      </div>
      <div className="mt-2 space-y-1.5">
        {task.steps.map((step, index) => (
          <div key={step.id} className="flex items-center gap-2 text-xs">
            <span
              className={`flex h-4 w-4 shrink-0 items-center justify-center rounded-full text-[10px] ${
                step.status === "DONE"
                  ? "bg-spruce text-white"
                  : step.status === "RUNNING"
                    ? "bg-spruce/20 text-spruce"
                    : "bg-ink/10 text-ink/50"
              }`}
            >
              {index + 1}
            </span>
            <span className={step.status === "DONE" ? "text-ink/70" : "text-ink/55"}>{step.title}</span>
            <span className="ml-auto text-[11px] text-ink/40">
              {stepStatusText[step.status] ?? step.status}
            </span>
          </div>
        ))}
      </div>
      {task.errorMessage ? <p className="mt-2 text-xs text-coral">{task.errorMessage}</p> : null}
    </div>
  );
}
