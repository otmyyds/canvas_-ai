import { clsx } from "clsx";
import type { ButtonHTMLAttributes, InputHTMLAttributes, LabelHTMLAttributes, TextareaHTMLAttributes } from "react";

export function Label({ className, ...props }: LabelHTMLAttributes<HTMLLabelElement>) {
  return (
    <label
      {...props}
      className={clsx("mb-1 block text-sm font-medium text-ink/70", className)}
    />
  );
}

export function IconButton({ className, ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...props}
      className={clsx(
        "inline-flex h-9 w-9 items-center justify-center rounded-lg border border-ink/10 bg-white text-ink shadow-sm transition hover:border-accent/40 hover:bg-accent/5 hover:text-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/25 active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
    />
  );
}

export function Button({ className, ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...props}
      className={clsx(
        "inline-flex h-9 items-center justify-center gap-2 rounded-lg bg-accent px-3 text-sm font-medium text-white shadow-sm transition hover:bg-accent/90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/35 active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
    />
  );
}

export function GhostButton({ className, ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      {...props}
      className={clsx(
        "inline-flex h-9 items-center justify-center gap-2 rounded-lg border border-ink/10 bg-white px-3 text-sm font-medium text-ink shadow-sm transition hover:border-accent/40 hover:bg-accent/5 hover:text-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/25 active:scale-[0.97] disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
    />
  );
}

/**
 * 工具栏按钮：无边框的紧凑样式，靠 active 状态表达当前工具，
 * 多个按钮放进同一个圆角容器里，视觉上成为一个工具组。
 */
export function ToolButton({
  className,
  active = false,
  ...props
}: ButtonHTMLAttributes<HTMLButtonElement> & { active?: boolean }) {
  return (
    <button
      {...props}
      className={clsx(
        "inline-flex h-8 items-center justify-center gap-1.5 rounded-lg px-2.5 text-xs font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/25",
        active ? "bg-accent/10 text-accent" : "text-ink/65 hover:bg-ink/5 hover:text-ink",
        "disabled:cursor-not-allowed disabled:opacity-40",
        className
      )}
    />
  );
}

export function Input({ className, ...props }: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input
      {...props}
      className={clsx(
        "h-9 w-full rounded-lg border border-ink/10 bg-white px-3 text-sm shadow-sm outline-none transition placeholder:text-ink/40 focus:border-accent focus:ring-2 focus:ring-accent/15",
        className
      )}
    />
  );
}

export function Textarea({ className, ...props }: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      {...props}
      className={clsx(
        "w-full resize-none rounded-lg border border-ink/10 bg-white px-3 py-2 text-sm shadow-sm outline-none transition placeholder:text-ink/40 focus:border-accent focus:ring-2 focus:ring-accent/15",
        className
      )}
    />
  );
}
