import type { BoardCard, BoardEdge, SourceFile } from "@/lib/types";
import { emptyWhiteboardSpec, type WhiteboardSpec } from "@/lib/whiteboard/schema";

type SnapshotLike = {
  title?: string;
  cards: BoardCard[];
  edges: BoardEdge[];
  files: SourceFile[];
};

export function buildWhiteboardSpec(input: SnapshotLike): WhiteboardSpec {
  const assets = input.files.map((file) => ({
    id: assetIdFromFile(file.id),
    fileId: file.id,
    src: fileContentUrlPlaceholder(file.boardId, file.id),
    mimeType: file.mimeType,
    name: file.filename
  }));

  const nodes = input.cards.map((card) => {
    if (card.cardType === "IMAGE") {
      return {
        id: card.id,
        kind: "image" as const,
        x: card.x,
        y: card.y,
        width: card.width,
        height: card.height,
        fileId: extractSourceFileId(card.contentMarkdown),
        name: card.title
      };
    }

    return {
      id: card.id,
      kind: "card" as const,
      x: card.x,
      y: card.y,
      width: card.width,
      height: card.height,
      title: card.title,
      markdown: card.contentMarkdown,
      cardType: mapCardType(card.cardType)
    };
  });

  const edgeNodes = input.edges.map((edge) => ({
    id: edge.id,
    kind: "arrow" as const,
    fromId: edge.fromCardId,
    toId: edge.toCardId,
    label: edge.label
  }));

  return {
    title: input.title,
    nodes: nodes as WhiteboardSpec["nodes"],
    edges: edgeNodes as WhiteboardSpec["edges"],
    assets,
    notes: []
  };
}

export function buildBoardSnapshotPayload(spec: WhiteboardSpec) {
  return JSON.stringify(spec);
}

export function parseBoardSnapshot(snapshotJson?: string | null): WhiteboardSpec {
  if (!snapshotJson) {
    return emptyWhiteboardSpec;
  }
  try {
    const parsed = JSON.parse(snapshotJson) as WhiteboardSpec;
    return {
      ...emptyWhiteboardSpec,
      ...parsed,
      nodes: parsed.nodes ?? [],
      edges: parsed.edges ?? [],
      assets: parsed.assets ?? [],
      notes: parsed.notes ?? []
    };
  } catch {
    return emptyWhiteboardSpec;
  }
}

function mapCardType(cardType: BoardCard["cardType"]) {
  switch (cardType) {
    case "SUMMARY":
      return "summary";
    case "IDEA":
      return "idea";
    case "SOURCE":
      return "source";
    case "QUESTION":
      return "question";
    case "NOTE":
    case "IMAGE":
    case "PROMPT":
    default:
      return "summary";
  }
}

function extractSourceFileId(markdown: string) {
  const match = markdown.match(/sourceFileId:([0-9a-fA-F-]+)/);
  return match?.[1];
}

function assetIdFromFile(fileId: string) {
  return `asset:${fileId}`;
}

function fileContentUrlPlaceholder(boardId: string, fileId: string) {
  return `/api/boards/${boardId}/files/${fileId}/content`;
}

