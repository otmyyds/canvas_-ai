"use client";

import { useEffect, useState } from "react";
import { Loader2, Plus, Sparkles } from "lucide-react";
import { Button, GhostButton, Input, Label } from "@/components/ui";
import { Dialog } from "@/components/dialog";

type CreateBoardDialogProps = {
  open: boolean;
  creating: boolean;
  onClose: () => void;
  onCreate: (title: string) => void;
};

const DEFAULT_TITLE = "我的 AI 文档画布";

/**
 * 新建画布弹窗：标题输入 + 创建按钮，回车可直接提交。
 * 每次打开重置为默认标题，避免沿用上一次输入。
 */
export function CreateBoardDialog({ open, creating, onClose, onCreate }: CreateBoardDialogProps) {
  const [title, setTitle] = useState(DEFAULT_TITLE);

  useEffect(() => {
    if (open) {
      setTitle(DEFAULT_TITLE);
    }
  }, [open]);

  const trimmed = title.trim();

  function submit() {
    if (!trimmed || creating) {
      return;
    }
    onCreate(trimmed);
  }

  return (
    <Dialog
      open={open}
      title="新建画布"
      description="给画布起个名字，创建后可以上传资料，让 AI 生成思维导图与流程图。"
      closeDisabled={creating}
      onClose={onClose}
      icon={
        <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-accent/10 text-accent">
          <Sparkles className="h-5 w-5" />
        </span>
      }
      footer={
        <>
          <GhostButton onClick={onClose} disabled={creating}>
            取消
          </GhostButton>
          <Button onClick={submit} disabled={creating || !trimmed}>
            {creating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Plus className="h-4 w-4" />}
            创建画布
          </Button>
        </>
      }
    >
      <div className="mt-4">
        <Label htmlFor="new-board-title">画布标题</Label>
        <Input
          id="new-board-title"
          autoFocus
          value={title}
          maxLength={80}
          disabled={creating}
          placeholder="例如：Elasticsearch 学习笔记"
          onChange={(event) => setTitle(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              submit();
            }
          }}
        />
      </div>
    </Dialog>
  );
}
