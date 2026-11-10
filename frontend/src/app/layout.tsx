import type { Metadata } from "next";
import Script from "next/script";
import "./globals.css";

export const metadata: Metadata = {
  title: "文档画布",
  description: "单人 AI 文档画布"
};

/**
 * 首屏应用已保存的主题色。
 * beforeInteractive 由 Next 注入 head，在 hydration 前执行，避免先渲染默认色再跳变。
 */
const themeBootstrap = `
try {
  var theme = JSON.parse(localStorage.getItem("dc_theme") || "null");
  if (theme && theme.accent) {
    var style = document.documentElement.style;
    style.setProperty("--accent", theme.accent);
    style.setProperty("--background", theme.background);
    style.setProperty("--canvas-bg", theme.canvas);
  }
} catch (error) {}
`;

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body>
        <Script id="theme-bootstrap" strategy="beforeInteractive">
          {themeBootstrap}
        </Script>
        {children}
      </body>
    </html>
  );
}
