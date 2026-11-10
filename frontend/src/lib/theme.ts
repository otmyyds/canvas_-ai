"use client";

/**
 * 界面主题色。
 *
 * accent 必须是 "R G B" 三元组：Tailwind 用 rgb(var(--accent) / <alpha-value>) 生成类名，
 * 切换主题只需改 CSS 变量，不需要重新构建样式。
 * background 是页面底色，canvas 是画布底色，两者跟随主题一起变，避免只换按钮颜色显得割裂。
 */

export type ThemePalette = {
  id: string;
  label: string;
  /** RGB 三元组，例如 "37 99 235" */
  accent: string;
  /** 页面背景色 */
  background: string;
  /** 画布背景色 */
  canvas: string;
};

const STORAGE_KEY = "dc_theme";

export const THEMES: ThemePalette[] = [
  { id: "ocean", label: "海蓝", accent: "37 99 235", background: "#f6f8fc", canvas: "#edf2fb" },
  { id: "violet", label: "靛紫", accent: "109 91 208", background: "#f8f7fc", canvas: "#f1eefb" },
  { id: "rose", label: "玫瑰", accent: "190 61 99", background: "#fcf7f8", canvas: "#f8eef1" },
  { id: "amber", label: "琥珀", accent: "180 83 9", background: "#fbf8f3", canvas: "#f7f0e4" },
  { id: "graphite", label: "石墨", accent: "59 74 86", background: "#f6f8f9", canvas: "#edf1f4" },
  { id: "forest", label: "松绿", accent: "30 107 92", background: "#f7faf8", canvas: "#eef4f1" }
];

export const DEFAULT_THEME = THEMES[0];

/** 读取已保存的主题；数据损坏或 id 已删除时退回默认主题 */
export function readStoredTheme(): ThemePalette | null {
  if (typeof window === "undefined") {
    return null;
  }
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) {
      return null;
    }
    const stored = JSON.parse(raw) as Partial<ThemePalette> | null;
    if (!stored?.id || !stored.accent) {
      return null;
    }
    return THEMES.find((theme) => theme.id === stored.id) ?? null;
  } catch {
    return null;
  }
}

/** 应用主题并持久化；首屏由 layout 里的内联脚本提前写入，避免刷新时闪一下默认色 */
export function applyTheme(palette: ThemePalette): void {
  if (typeof document === "undefined") {
    return;
  }
  const root = document.documentElement;
  root.style.setProperty("--accent", palette.accent);
  root.style.setProperty("--background", palette.background);
  root.style.setProperty("--canvas-bg", palette.canvas);
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(palette));
  } catch {
    // 隐私模式下写入可能失败，主题在当前会话内仍然生效
  }
}

/** 主题色的十六进制展示值，用于色板圆点 */
export function accentHex(palette: ThemePalette): string {
  const [r, g, b] = palette.accent.split(" ").map(Number);
  return `rgb(${r} ${g} ${b})`;
}
