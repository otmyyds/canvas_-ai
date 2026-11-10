"use client";

import { ReactNode, useEffect } from "react";
import { createPortal } from "react-dom";

type DialogProps = {
  open: boolean;
  title: string;
  description?: string;
  /** 标题左侧的图标区域，由调用方决定颜色和图标 */
  icon?: ReactNode;
  /** 标题和说明下方的自定义内容（例如表单） */
  children?: ReactNode;
  /** 底部按钮区 */
  footer?: ReactNode;
  onClose: () => void;
  /** 请求进行中时禁止 ESC 和点遮罩关闭 */
  closeDisabled?: boolean;
};

/**
 * 通用弹窗外壳：遮罩 + 居中面板 + 标题区 + 底部按钮区。
 * 通过 portal 渲染到 body，避免被画布、滚动容器裁切。
 */
export function Dialog({
  open,
  title,
  description,
  icon,
  children,
  footer,
  onClose,
  closeDisabled = false
}: DialogProps) {
  useEffect(() => {
    if (!open) {
      return;
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape" && !closeDisabled) {
        onClose();
      }
    }
    document.addEventListener("keydown", handleKeyDown);
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", handleKeyDown);
      document.body.style.overflow = previousOverflow;
    };
  }, [closeDisabled, onClose, open]);

  if (!open || typeof document === "undefined") {
    return null;
  }

  return createPortal(
    <div
      className="dialog-overlay fixed inset-0 z-[1000] flex items-center justify-center bg-ink/40 p-4 backdrop-blur-[2px]"
      onMouseDown={(event) => {
        // 只点在遮罩本身时关闭，点击面板内部不触发
        if (event.target === event.currentTarget && !closeDisabled) {
          onClose();
        }
      }}
    >
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialog-title"
        className="dialog-panel w-full max-w-sm overflow-hidden rounded-xl border border-ink/10 bg-white shadow-soft"
      >
        <div className="flex items-start gap-3 px-5 pt-5">
          {icon}
          <div className="min-w-0 flex-1">
            <h2 id="dialog-title" className="text-base font-semibold text-ink">
              {title}
            </h2>
            {description ? (
              <p className="mt-2 text-sm leading-6 text-ink/60">{description}</p>
            ) : null}
            {children}
          </div>
        </div>
        {footer ? (
          <div className="mt-5 flex justify-end gap-2 border-t border-ink/5 bg-mist/40 px-5 py-3">
            {footer}
          </div>
        ) : null}
      </div>
    </div>,
    document.body
  );
}
