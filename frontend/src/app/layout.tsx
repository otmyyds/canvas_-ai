import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "文档画布",
  description: "单人 AI 文档画布"
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="zh-CN" suppressHydrationWarning>
      <body>{children}</body>
    </html>
  );
}
