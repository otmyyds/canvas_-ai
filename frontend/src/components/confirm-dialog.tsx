"use client";

import { useEffect } from "react";
import { createPortal } from "react-dom";
import { clsx } from "clsx";
import { AlertTriangle, Loader2 } from "lucide-react";
import { Button, GhostButton } from "@/components/ui";

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
 * 通用确认弹窗：遮罩 + 居中面板，参考 Element Plus 的 MessageBox 结构。
 * 通过 portal 渲染到 body，避免被画布、滚动容器裁切。
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
  useEffect(() => {
    if (!open) {
      return;
    }
    // ESC 关闭，同时锁住背景滚动
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !loading) {
        onCancel();
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [loading, onCancel, open]);

  if (!open || typeof document === "undefined") {
    return null;
  }

  return createPortal(
    <div
      className="dialog-overlay fixed inset-0 z-[1000] flex items-center justify-center bg-ink/40 p-4 backdrop-blur-[2px]"
      onMouseDown={(event) => {
        // 只点在遮罩本身时关闭，点击面板内部不触发
        if (event.target === event.currentTarget && !loading) {
          onCancel();
        }
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="confirm-dialog-title"
        className="dialog-panel w-full max-w-sm overflow-hidden rounded-xl border border-ink/10 bg-white shadow-soft"
      >
        <div className="flex items-start gap-3 px-5 pt-5">
          <span
            className={clsx(
              "flex h-9 w-9 shrink-0 items-center justify-center rounded-full",
              danger ? "bg-coral/10 text-coral" : "bg-accent/10 text-accent"
            )}
          >
            <AlertTriangle className="h-5 w-5" />
          </span>
          <div className="min-w-0 flex-1">
            <h2 id="confirm-dialog-title" className="text-base font-semibold text-ink">
              {title}
            </h2>
            {description ? (
              <p className="mt-2 text-sm leading-6 text-ink/60">{description}</p>
            ) : null}
          </div>
        </div>
        <div className="mt-5 flex justify-end gap-2 border-t border-ink/5 bg-mist/40 px-5 py-3">
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
        </div>
      </div>
    </div>,
    document.body
  );
}
