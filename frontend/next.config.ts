import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  outputFileTracingRoot: __dirname,
  // 生成自包含的 Node 服务目录，供 Windows/Linux 便携版直接携带 Node 运行
  output: "standalone",
  // 关闭左下角的 Next.js 开发指示器（只影响开发环境，不影响生产构建）
  devIndicators: false,
};

export default nextConfig;
