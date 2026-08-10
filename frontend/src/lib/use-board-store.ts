"use client";

import { create } from "zustand";
import type { AiTask, Board, BoardCard, BoardEdge, BoardShape, SourceFile } from "@/lib/types";

type BoardState = {
  board?: Board;
  cards: BoardCard[];
  edges: BoardEdge[];
  shapes: BoardShape[];
  tasks: AiTask[];
  files: SourceFile[];
  setBoard: (board: Board) => void;
  setCards: (cards: BoardCard[]) => void;
  setEdges: (edges: BoardEdge[]) => void;
  setShapes: (shapes: BoardShape[]) => void;
  setTasks: (tasks: AiTask[]) => void;
  setFiles: (files: SourceFile[]) => void;
  upsertTask: (task: AiTask) => void;
};

export const useBoardStore = create<BoardState>((set) => ({
  cards: [],
  edges: [],
  shapes: [],
  tasks: [],
  files: [],
  setBoard: (board) => set({ board }),
  setCards: (cards) => set({ cards }),
  setEdges: (edges) => set({ edges }),
  setShapes: (shapes) => set({ shapes }),
  setTasks: (tasks) => set({ tasks }),
  setFiles: (files) => set({ files }),
  upsertTask: (task) =>
    set((state) => ({
      tasks: [task, ...state.tasks.filter((item) => item.id !== task.id)]
    }))
}));
