"use client";

import { ChangeEvent, useRef, useState } from "react";
import { FileUp, Loader2, Trash2 } from "lucide-react";
import type { SourceFile } from "@/lib/types";
import { GhostButton, IconButton } from "@/components/ui";

type SourcePanelProps = {
  files: SourceFile[];
  onUpload: (file: File) => Promise<void>;
  onDelete: (fileId: string) => Promise<void>;
};

const parseStatusText: Record<string, string> = {
  PENDING: "待解析",
  PARSING: "解析中",
  READY: "已就绪",
  FAILED: "解析失败"
};

export function SourcePanel({ files, onUpload, onDelete }: SourcePanelProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string>();

  async function handleFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    setUploading(true);
    setError(undefined);
    try {
      await onUpload(file);
    } catch (err) {
      setError(err instanceof Error ? err.message : "上传失败");
    } finally {
      setUploading(false);
      event.target.value = "";
    }
  }

  async function handleDelete(fileId: string) {
    if (!window.confirm("确定删除该资料？解析的向量内容将一并清除。")) {
      return;
    }
    setError(undefined);
    try {
      await onDelete(fileId);
    } catch (err) {
      setError(err instanceof Error ? err.message : "删除失败");
    }
  }

  return (
    <div className="flex flex-col">
      <div className="border-b border-ink/10 p-4">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold text-ink">资料库</h2>
          <span className="text-xs text-ink/50">{files.length} 个文件</span>
        </div>
        <p className="mt-1 text-xs leading-5 text-ink/55">
          TXT / Markdown / PDF / DOCX 上传后自动解析，可作为 AI 任务上下文。
        </p>
        <input ref={inputRef} type="file" className="hidden" onChange={handleFile} />
        <GhostButton className="mt-3 w-full" onClick={() => inputRef.current?.click()} disabled={uploading}>
          {uploading ? <Loader2 className="h-4 w-4 animate-spin" /> : <FileUp className="h-4 w-4" />}
          上传资料
        </GhostButton>
        {error ? <p className="mt-2 text-xs text-coral">{error}</p> : null}
      </div>

      <div className="max-h-80 min-h-0 overflow-auto p-3">
        {files.length === 0 ? (
          <p className="rounded-md border border-dashed border-ink/15 p-3 text-xs leading-5 text-ink/50">
            暂无资料。上传后可作为 AI 任务上下文。
          </p>
        ) : null}
        <div className="space-y-2">
          {files.map((file) => (
            <div key={file.id} className="group flex items-start gap-2 rounded-md border border-ink/10 bg-mist p-3">
              <div className="min-w-0 flex-1">
                <p className="truncate text-sm font-medium text-ink">{file.filename}</p>
                <div className="mt-1 flex items-center gap-3 text-xs text-ink/50">
                  <span>{Math.ceil(file.size / 1024)} KB</span>
                  <span>{parseStatusText[file.parseStatus] ?? file.parseStatus}</span>
                </div>
              </div>
              <IconButton
                className="h-7 w-7 shrink-0 text-ink/40 hover:text-coral"
                onClick={() => void handleDelete(file.id)}
                title="删除资料"
              >
                <Trash2 className="h-3.5 w-3.5" />
              </IconButton>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
