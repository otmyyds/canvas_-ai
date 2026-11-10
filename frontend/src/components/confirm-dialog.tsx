"use client";

import { clsx } from "clsx";
import { AlertTriangle, Loader2 } from "lucide-react";
import { Button, GhostButton } from "@/components/ui";
import { Dialog } from "@/components/dialog";

type ConfirmDialogProps = {
  open: boolean;
  title: string;
  description?: string;
  confirmText?: string;
  cancelText?: string;
  /** 危险操作（删除等）使用红色确认按钮和警示图标 */
  danger?: boolean;
  /** 请求进行中：按钮禁用并显示加载图标 */
  loading?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

/**
 * 通用确认弹窗：在 Dialog 外壳上固定警示图标和「取消/确认」按钮区。
 */
export function ConfirmDialog({
  open,
  title,
  description,
  confirmText = "确定",
  cancelText = "取消",
  danger = false,
  loading = false,
  onConfirm,
  onCancel
}: ConfirmDialogProps) {
  return (
    <Dialog
      open={open}
      title={title}
      description={description}
      closeDisabled={loading}
      onClose={onCancel}
      icon={
        <span
          className={clsx(
            "flex h-9 w-9 shrink-0 items-center justify-center rounded-full",
            danger ? "bg-coral/10 text-coral" : "bg-accent/10 text-accent"
          )}
        >
          <AlertTriangle className="h-5 w-5" />
        </span>
      }
      footer={
        <>
          <GhostButton onClick={onCancel} disabled={loading}>
            {cancelText}
          </GhostButton>
          <Button
            onClick={onConfirm}
            disabled={loading}
            className={danger ? "bg-coral hover:bg-coral/90 focus-visible:ring-coral/35" : undefined}
          >
            {loading ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
            {confirmText}
          </Button>
        </>
      }
    />
  );
}
