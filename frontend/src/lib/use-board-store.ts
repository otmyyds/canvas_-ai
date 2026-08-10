"use client";

import { create } from "zustand";
import type { AiTask, Board, BoardCard, BoardEdge, SourceFile } from "@/lib/types";

type BoardState = {
  board?: Board;
  cards: BoardCard[];
  edges: BoardEdge[];
  tasks: AiTask[];
  files: SourceFile[];
  setBoard: (board: Board) => void;
  setCards: (cards: BoardCard[]) => void;
  setEdges: (edges: BoardEdge[]) => void;
  setTasks: (tasks: AiTask[]) => void;
  setFiles: (files: SourceFile[]) => void;
  upsertTask: (task: AiTask) => void;
};

export const useBoardStore = create<BoardState>((set) => ({
  cards: [],
  edges: [],
  tasks: [],
  files: [],
  setBoard: (board) => set({ board }),
  setCards: (cards) => set({ cards }),
  setEdges: (edges) => set({ edges }),
  setTasks: (tasks) => set({ tasks }),
  setFiles: (files) => set({ files }),
  upsertTask: (task) =>
    set((state) => ({
      tasks: [task, ...state.tasks.filter((item) => item.id !== task.id)]
    }))
}));
