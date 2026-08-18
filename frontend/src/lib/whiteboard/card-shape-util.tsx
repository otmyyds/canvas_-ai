"use client";

import { useEffect, useRef, useState } from "react";
import { BaseBoxShapeUtil, HTMLContainer, T, useEditor, useIsEditing } from "tldraw";
import type { RecordProps, TLBaseShape } from "tldraw";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { api } from "@/lib/api";
import type { BoardCard } from "@/lib/types";

/**
 * 自定义卡片 shape（tldraw 扩展）。
 *
 * 为什么自定义而不是用 tldraw 原生 note？
 * - note shape 不可自由缩放（resizeMode: none，宽度恒 200px）
 * - note 的 richText 不渲染 markdown（##、**、列表原样显示）
 * - 业务需要：Markdown 渲染 + 自由缩放 + 双击编辑 + 按类型配色
 *
 * 实现：
 * - 继承 BaseBoxShapeUtil（自带 w/h 自由缩放 + onResize + getGeometry）
 * - component() 用 ReactMarkdown 渲染 markdown
 * - canEdit() 让 tldraw 支持双击进入编辑态
 * - 编辑态渲染 textarea，失焦/Esc 保存，写回后端 cards 表
 */
export type CardShape = TLBaseShape<
  "card",
  {
    w: number;
    h: number;
    title: string;
    markdown: string;
    cardType: string;
  }
>;

export class CardShapeUtil extends BaseBoxShapeUtil<CardShape> {
  /** shape 类型标识（与 createShape 时 type: "card" 对应） */
  static override type = "card" as const;

  /** props 校验器（tldraw 用它验证 shape 数据合法性） */
  static override props: RecordProps<CardShape> = {
    w: T.nonZeroNumber,
    h: T.nonZeroNumber,
    title: T.string,
    markdown: T.string,
    cardType: T.string
  };

  /** 新卡片的默认属性（宽 340 / 高 220） */
  override getDefaultProps(): CardShape["props"] {
    return { w: 340, h: 220, title: "", markdown: "", cardType: "SUMMARY" };
  }

  /** 允许双击进入编辑态（配合 useIsEditing 渲染 textarea） */
  override canEdit() {
    return true;
  }

  override component(shape: CardShape) {
    return <CardComponent shape={shape} />;
  }

  override indicator(shape: CardShape) {
    return <rect width={shape.props.w} height={shape.props.h} rx={8} />;
  }
}

function CardComponent({ shape }: { shape: CardShape }) {
  const editor = useEditor();
  const isEditing = useIsEditing(shape.id);
  const { w, h, title, markdown, cardType } = shape.props;
  const [draft, setDraft] = useState(markdown);
  const [titleDraft, setTitleDraft] = useState(title);
  const [saving, setSaving] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  // 用 ref 存最新草稿，供退出编辑态时读取（避免 effect 闭包旧值）
  const draftRef = useRef({ markdown: draft, title: titleDraft });
  draftRef.current = { markdown: draft, title: titleDraft };

  // 进入编辑态时同步草稿并聚焦
  useEffect(() => {
    if (isEditing) {
      setDraft(shape.props.markdown);
      setTitleDraft(shape.props.title);
      textareaRef.current?.focus();
      textareaRef.current?.select();
    }
  }, [isEditing, shape.props.markdown, shape.props.title]);

  // 退出编辑态（点击外部 / Esc / 切工具）时提交保存
  const justEntered = useRef(false);
  useEffect(() => {
    if (isEditing) {
      justEntered.current = true;
      return;
    }
    if (!justEntered.current) {
      return;
    }
    justEntered.current = false;
    const current = draftRef.current;
    const markdownChanged = current.markdown !== shape.props.markdown;
    const titleChanged = current.title !== shape.props.title;
    if (markdownChanged || titleChanged) {
      editor.updateShape<CardShape>({
        id: shape.id,
        type: "card",
        props: { ...shape.props, markdown: current.markdown, title: current.title }
      });
      void saveToBackend(current.markdown, current.title);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isEditing]);

  async function saveToBackend(content: string, cardTitle: string) {
    const cardId = (shape.meta?.cardId as string | undefined) ?? shape.id;
    if (!cardId) {
      return;
    }
    setSaving(true);
    try {
      await api.updateCard(cardId, {
        title: cardTitle,
        contentMarkdown: content,
        cardType: cardType as BoardCard["cardType"]
      });
    } catch {
      // 保存失败不阻断编辑态
    } finally {
      setSaving(false);
    }
  }

  if (isEditing) {
    return (
      <HTMLContainer style={{ width: w, height: h }}>
        <div className="card-shape card-shape-editing">
          <input
            className="card-shape-title-input"
            value={titleDraft}
            onChange={(event) => setTitleDraft(event.target.value)}
            placeholder="卡片标题"
          />
          <textarea
            ref={textareaRef}
            className="card-shape-textarea"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                setDraft(shape.props.markdown);
                setTitleDraft(shape.props.title);
                editor.setEditingShape(null);
              }
            }}
            placeholder="支持 Markdown 语法，例如 **加粗**、## 标题、- 列表"
          />
          <div className="card-shape-edit-hint">{saving ? "保存中…" : "点击外部即保存 · Esc 取消"}</div>
        </div>
      </HTMLContainer>
    );
  }

  return (
    <HTMLContainer style={{ width: w, height: h }}>
      <div className={`card-shape card-type-${cardTypeClass(cardType)}`}>
        <div className="card-shape-title">{title}</div>
        <div className="card-shape-body">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{markdown}</ReactMarkdown>
        </div>
      </div>
    </HTMLContainer>
  );
}

/** 把后端 cardType 映射为配色类名 */
function cardTypeClass(cardType: string): string {
  switch (cardType) {
    case "SUMMARY":
      return "summary";
    case "IDEA":
      return "idea";
    case "SOURCE":
      return "source";
    case "QUESTION":
      return "question";
    case "IMAGE":
      return "image";
    case "NOTE":
    default:
      return "note";
  }
}
