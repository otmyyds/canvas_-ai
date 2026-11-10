import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  outputFileTracingRoot: __dirname,
  // 关闭左下角的 Next.js 开发指示器（只影响开发环境，不影响生产构建）
  devIndicators: false,
};

export default nextConfig;
