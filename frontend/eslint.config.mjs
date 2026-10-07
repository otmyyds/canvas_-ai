import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { FlatCompat } from "@eslint/eslintrc";

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

// ESLint 9 读的是 flat config，而 eslint-config-next 15 仍是老的 eslintrc 形态，
// 这里用 FlatCompat 转换。next lint 已废弃（Next 16 移除），npm run lint 直接走 eslint CLI。
const config = [
  ...compat.extends("next/core-web-vitals"),
  { ignores: [".next/**", "node_modules/**", "next-env.d.ts"] },
];

export default config;
