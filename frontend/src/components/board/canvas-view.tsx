"use client";

import { ChangeEvent, useMemo, useRef, useState } from "react";
import { ImagePlus, Link2, MousePointer2, Plus, RefreshCw } from "lucide-react";
import type { BoardCard, BoardEdge } from "@/lib/types";
import { GhostButton } from "@/components/ui";
import { api } from "@/lib/api";

type CanvasMode = "select" | "connect";

type CanvasViewProps = {
  boardId: string;
  cards: BoardCard[];
  edges: BoardEdge[];
  onCreateCard: () => void;
  onCreateImage: (file: File) => Promise<void>;
  onCreateEdge: (fromCardId: string, toCardId: string) => Promise<void>;
  onRefresh: () => void;
};

const cardTone: Record<string, string> = {
  NOTE: "border-spruce/25 bg-white",
  SUMMARY: "border-spruce/30 bg-spruce/5",
  IDEA: "border-amber/35 bg-amber/10",
  SOURCE: "border-violet/30 bg-violet/10",
  QUESTION: "border-coral/35 bg-coral/10",
  IMAGE: "border-ink/20 bg-white",
  PROMPT: "border-amber/40 bg-white"
};

const cardTypeText: Record<string, string> = {
  NOTE: "笔记",
  SUMMARY: "摘要",
  IDEA: "想法",
  SOURCE: "资料",
  QUESTION: "问题",
  IMAGE: "图片",
  PROMPT: "提示词"
};

export function CanvasView({
  boardId,
  cards,
  edges,
  onCreateCard,
  onCreateImage,
  onCreateEdge,
  onRefresh
}: CanvasViewProps) {
  const imageInputRef = useRef<HTMLInputElement>(null);
  const [mode, setMode] = useState<CanvasMode>("select");
  const [edgeStartId, setEdgeStartId] = useState<string>();
  const [message, setMessage] = useState("选择工具后在画布中操作。");
  const cardById = useMemo(() => new Map(cards.map((card) => [card.id, card])), [cards]);

  async function handleCardClick(cardId: string) {
    if (mode !== "connect") {
      return;
    }
    if (!edgeStartId) {
      setEdgeStartId(cardId);
      setMessage("已选择起点卡片，请点击另一张卡片作为终点。");
      return;
    }
    if (edgeStartId === cardId) {
      setMessage("不能连接同一张卡片，请选择另一张卡片。");
      return;
    }
    await onCreateEdge(edgeStartId, cardId);
    setEdgeStartId(undefined);
    setMode("select");
    setMessage("连线已创建。");
  }

  async function handleImageFile(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) {
      return;
    }
    if (!file.type.startsWith("image/")) {
      setMessage("请选择图片文件。");
      event.target.value = "";
      return;
    }
    await onCreateImage(file);
    setMessage("图片已添加到画布。");
    event.target.value = "";
  }

  function center(card: BoardCard) {
    return {
      x: card.x + card.width / 2,
      y: card.y + card.height / 2
    };
  }

  return (
    <section className="relative min-h-0 flex-1 overflow-auto canvas-grid">
      <div className="absolute left-4 top-4 z-20 flex flex-wrap gap-2">
        <GhostButton onClick={onCreateCard}>
          <Plus className="h-4 w-4" />
          卡片
        </GhostButton>
        <GhostButton
          className={mode === "connect" ? "border-spruce bg-spruce/10 text-spruce" : undefined}
          onClick={() => {
            setMode(mode === "connect" ? "select" : "connect");
            setEdgeStartId(undefined);
            setMessage(mode === "connect" ? "已退出连线模式。" : "连线模式：先点击起点卡片，再点击终点卡片。");
          }}
        >
          {mode === "connect" ? <MousePointer2 className="h-4 w-4" /> : <Link2 className="h-4 w-4" />}
          连线
        </GhostButton>
        <GhostButton onClick={() => imageInputRef.current?.click()}>
          <ImagePlus className="h-4 w-4" />
          图片
        </GhostButton>
        <GhostButton onClick={onRefresh}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </GhostButton>
        <input ref={imageInputRef} type="file" accept="image/*" className="hidden" onChange={handleImageFile} />
      </div>

      <div className="absolute left-4 top-16 z-20 rounded-md border border-ink/10 bg-white/95 px-3 py-2 text-xs text-ink/60 shadow-sm">
        {message}
      </div>

      <div className="relative h-full min-h-[720px] min-w-[820px]">
        <svg className="pointer-events-none absolute inset-0 z-0 h-full w-full">
          <defs>
            <marker id="edge-arrow" markerHeight="8" markerWidth="8" orient="auto" refX="7" refY="4">
              <path d="M0,0 L8,4 L0,8 Z" fill="#1e6b5c" />
            </marker>
          </defs>
          {edges.map((edge) => {
            const from = cardById.get(edge.fromCardId);
            const to = cardById.get(edge.toCardId);
            if (!from || !to) {
              return null;
            }
            const a = center(from);
            const b = center(to);
            return (
              <g key={edge.id}>
                <line
                  x1={a.x}
                  y1={a.y}
                  x2={b.x}
                  y2={b.y}
                  stroke="#1e6b5c"
                  strokeWidth="2"
                  strokeDasharray="6 6"
                  markerEnd="url(#edge-arrow)"
                />
                {edge.label ? (
                  <text x={(a.x + b.x) / 2} y={(a.y + b.y) / 2 - 8} fill="#1e6b5c" fontSize="12">
                    {edge.label}
                  </text>
                ) : null}
              </g>
            );
          })}
        </svg>

        {cards.map((card) => {
          const selectedAsStart = edgeStartId === card.id;
          return (
            <article
              key={card.id}
              onClick={() => handleCardClick(card.id)}
              className={`absolute z-10 rounded-md border p-4 shadow-sm transition ${
                cardTone[card.cardType] ?? cardTone.NOTE
              } ${mode === "connect" ? "cursor-crosshair hover:border-spruce hover:ring-2 hover:ring-spruce/15" : ""} ${
                selectedAsStart ? "border-spruce ring-2 ring-spruce/30" : ""
              }`}
              style={{
                left: card.x,
                top: card.y,
                width: card.width,
                minHeight: card.height
              }}
            >
              <div className="flex items-start justify-between gap-3">
                <h3 className="text-sm font-semibold leading-5 text-ink">{card.title}</h3>
                <span className="shrink-0 rounded bg-ink/5 px-1.5 py-1 text-[10px] font-medium text-ink/55">
                  {cardTypeText[card.cardType] ?? card.cardType}
                </span>
              </div>
              {card.cardType === "IMAGE" ? (
                <ImageCard boardId={boardId} card={card} />
              ) : (
                <p className="mt-3 line-clamp-6 whitespace-pre-wrap text-xs leading-5 text-ink/65">
                  {card.contentMarkdown || "空卡片"}
                </p>
              )}
            </article>
          );
        })}
      </div>
    </section>
  );
}

function ImageCard({ boardId, card }: { boardId: string; card: BoardCard }) {
  const fileId = extractSourceFileId(card.contentMarkdown);
  if (!fileId) {
    return <p className="mt-3 text-xs text-ink/50">图片来源缺失。</p>;
  }
  return (
    <div className="mt-3 overflow-hidden rounded-md border border-ink/10 bg-white">
      <img
        src={api.fileContentUrl(boardId, fileId)}
        alt={card.title}
        className="h-44 w-full object-cover"
        loading="lazy"
      />
    </div>
  );
}

function extractSourceFileId(markdown: string) {
  const match = markdown.match(/sourceFileId:([0-9a-fA-F-]+)/);
  return match?.[1];
}
