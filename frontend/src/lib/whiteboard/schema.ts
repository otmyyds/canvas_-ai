import { z } from "zod";

export const whiteboardNodeKindSchema = z.enum(["shape", "text", "image", "card"]);
export const whiteboardShapeKindSchema = z.enum(["rect", "rounded", "ellipse", "diamond", "hexagon", "star"]);
export const whiteboardCardTypeSchema = z.enum(["summary", "idea", "source", "question"]);
export const whiteboardEdgeKindSchema = z.enum(["line", "arrow"]);

const whiteboardStyleSchema = z.record(z.string(), z.string()).optional();

const baseNodeSchema = z.object({
  id: z.string().min(1),
  x: z.number(),
  y: z.number(),
  width: z.number().positive(),
  height: z.number().positive(),
  label: z.string().optional()
});

export const whiteboardNodeSchema = z.discriminatedUnion("kind", [
  baseNodeSchema.extend({
    kind: z.literal("shape"),
    shape: whiteboardShapeKindSchema,
    text: z.string().optional(),
    style: whiteboardStyleSchema
  }),
  baseNodeSchema.extend({
    kind: z.literal("text"),
    text: z.string(),
    style: whiteboardStyleSchema
  }),
  baseNodeSchema.extend({
    kind: z.literal("image"),
    fileId: z.string().optional(),
    sourceTaskId: z.string().optional(),
    style: whiteboardStyleSchema
  }),
  baseNodeSchema.extend({
    kind: z.literal("card"),
    title: z.string(),
    markdown: z.string(),
    cardType: whiteboardCardTypeSchema,
    style: whiteboardStyleSchema
  })
]);

export const whiteboardEdgeSchema = z.object({
  id: z.string().min(1),
  kind: whiteboardEdgeKindSchema.default("arrow"),
  fromId: z.string().min(1),
  toId: z.string().min(1),
  label: z.string().optional()
});

export const whiteboardAssetSchema = z.object({
  id: z.string().min(1),
  fileId: z.string().min(1),
  src: z.string().min(1),
  mimeType: z.string().optional(),
  width: z.number().positive().optional(),
  height: z.number().positive().optional(),
  name: z.string().optional()
});

export const whiteboardSpecSchema = z.object({
  title: z.string().optional(),
  theme: z.string().optional(),
  viewport: z
    .object({
      x: z.number(),
      y: z.number(),
      zoom: z.number().positive()
    })
    .optional(),
  nodes: z.array(whiteboardNodeSchema).default([]),
  edges: z.array(whiteboardEdgeSchema).default([]),
  assets: z.array(whiteboardAssetSchema).default([]),
  notes: z.array(z.string()).default([])
});

export type WhiteboardSpec = z.infer<typeof whiteboardSpecSchema>;
export type WhiteboardNode = z.infer<typeof whiteboardNodeSchema>;
export type WhiteboardEdge = z.infer<typeof whiteboardEdgeSchema>;
export type WhiteboardAsset = z.infer<typeof whiteboardAssetSchema>;

export const emptyWhiteboardSpec: WhiteboardSpec = {
  nodes: [],
  edges: [],
  assets: [],
  notes: []
};

