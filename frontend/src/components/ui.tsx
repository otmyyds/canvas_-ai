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
        "inline-flex h-9 w-9 items-center justify-center rounded-md border border-ink/10 bg-white text-ink shadow-sm transition hover:border-spruce/40 hover:text-spruce disabled:cursor-not-allowed disabled:opacity-50",
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
        "inline-flex h-9 items-center justify-center gap-2 rounded-md bg-spruce px-3 text-sm font-medium text-white transition hover:bg-spruce/90 disabled:cursor-not-allowed disabled:opacity-50",
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
        "inline-flex h-9 items-center justify-center gap-2 rounded-md border border-ink/10 bg-white px-3 text-sm font-medium text-ink transition hover:border-spruce/40 hover:text-spruce disabled:cursor-not-allowed disabled:opacity-50",
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
        "h-9 w-full rounded-md border border-ink/10 bg-white px-3 text-sm outline-none transition placeholder:text-ink/40 focus:border-spruce focus:ring-2 focus:ring-spruce/15",
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
        "w-full resize-none rounded-md border border-ink/10 bg-white px-3 py-2 text-sm outline-none transition placeholder:text-ink/40 focus:border-spruce focus:ring-2 focus:ring-spruce/15",
        className
      )}
    />
  );
}
