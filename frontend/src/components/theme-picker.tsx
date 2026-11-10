"use client";

import { useEffect, useRef, useState } from "react";
import { Check, Palette } from "lucide-react";
import { IconButton } from "@/components/ui";
import { applyTheme, accentHex, THEMES, readStoredTheme, DEFAULT_THEME } from "@/lib/theme";
import type { ThemePalette } from "@/lib/theme";

/**
 * 主题色选择器：下拉列出全部配色，点击立即生效并持久化到 localStorage。
 * 点击面板外部关闭，和工具栏其它下拉菜单行为一致。
 */
export function ThemePicker() {
  const [open, setOpen] = useState(false);
  const [current, setCurrent] = useState<ThemePalette>(() => readStoredTheme() ?? DEFAULT_THEME);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    const close = (event: MouseEvent) => {
      if (!containerRef.current?.contains(event.target as Node)) {
        setOpen(false);
      }
    };
    window.addEventListener("mousedown", close);
    return () => window.removeEventListener("mousedown", close);
  }, [open]);

  return (
    <div ref={containerRef} className="relative">
      <IconButton
        title="切换主题色"
        aria-label="切换主题色"
        onClick={() => setOpen((value) => !value)}
      >
        <Palette className="h-4 w-4" />
      </IconButton>
      {open ? (
        <div className="menu-panel absolute right-0 top-full z-40 mt-1 w-44 rounded-lg border border-ink/10 bg-white p-1 shadow-lg">
          <p className="px-2 py-1 text-[11px] font-medium text-ink/45">主题色</p>
          {THEMES.map((theme) => (
            <button
              key={theme.id}
              type="button"
              onClick={() => {
                applyTheme(theme);
                setCurrent(theme);
                setOpen(false);
              }}
              className="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-xs text-ink transition hover:bg-ink/5"
            >
              <span
                className="h-4 w-4 shrink-0 rounded-full ring-1 ring-black/10"
                style={{ backgroundColor: accentHex(theme) }}
              />
              <span>{theme.label}</span>
              {current.id === theme.id ? <Check className="ml-auto h-3.5 w-3.5 text-accent" /> : null}
            </button>
          ))}
        </div>
      ) : null}
    </div>
  );
}
