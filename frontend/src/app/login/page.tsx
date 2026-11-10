"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import {
  ArrowRight,
  Check,
  Loader2,
  Lock,
  Mail,
  Shapes,
  User
} from "lucide-react";
import { clsx } from "clsx";
import { api, saveAuthSession } from "@/lib/api";
import { Button, Input, Label } from "@/components/ui";

/** 后端启动时写入的演示账号，登录页默认填入 */
const DEFAULT_EMAIL = "doc_canvas@doc_canvas.com";
const DEFAULT_PASSWORD = "doc_canvas";

type Mode = "login" | "register";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<Mode>("login");
  const [email, setEmail] = useState(DEFAULT_EMAIL);
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState(DEFAULT_PASSWORD);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();

  /** 切换登录/注册：登录页恢复默认演示账号，注册页清空，避免误用演示邮箱注册 */
  function switchMode(next: Mode) {
    if (next === mode) {
      return;
    }
    setMode(next);
    setError(undefined);
    setDisplayName("");
    if (next === "login") {
      setEmail(DEFAULT_EMAIL);
      setPassword(DEFAULT_PASSWORD);
    } else {
      setEmail("");
      setPassword("");
    }
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(undefined);
    try {
      const result =
        mode === "login"
          ? await api.login(email, password)
          : await api.register(email, displayName || email.split("@")[0], password);
      saveAuthSession(result);
      router.push("/app");
    } catch (err) {
      setError(err instanceof Error ? err.message : "操作失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-mist px-4 py-10">
      {/* 背景光斑只用主题色，跟随主题切换 */}
      <div className="pointer-events-none absolute -left-24 -top-24 h-72 w-72 rounded-full bg-accent/10 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-32 -right-24 h-80 w-80 rounded-full bg-accent/10 blur-3xl" />

      <div className="relative grid w-full max-w-4xl overflow-hidden rounded-2xl border border-ink/10 bg-white shadow-soft md:grid-cols-[1.05fr_1fr]">
        <aside className="relative hidden flex-col justify-between overflow-hidden bg-accent p-8 text-white md:flex">
          <div className="pointer-events-none absolute -right-12 -top-12 h-44 w-44 rounded-full bg-white/10" />
          <div className="pointer-events-none absolute -bottom-16 -left-10 h-52 w-52 rounded-full bg-white/10" />

          <div className="relative flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-white/15">
              <Shapes className="h-5 w-5" />
            </div>
            <div>
              <p className="font-semibold">Doc Canvas</p>
              <p className="text-xs text-white/70">AI 文档画布</p>
            </div>
          </div>

          <div className="relative">
            <h2 className="text-2xl font-semibold leading-snug">
              让 AI 帮你把资料
              <br />
              画成清晰的结构
            </h2>
            <ul className="mt-6 space-y-3 text-sm text-white/85">
              {["上传资料，自动解析与检索", "生成思维导图与流程图", "逻辑图排版、折叠与整理"].map((item) => (
                <li key={item} className="flex items-center gap-2">
                  <span className="flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-white/15">
                    <Check className="h-3 w-3" />
                  </span>
                  {item}
                </li>
              ))}
            </ul>
          </div>

          <p className="relative text-xs text-white/60">单人私有工作区，数据保存在本地数据库</p>
        </aside>

        <section className="p-6 sm:p-8">
          <div className="mb-6">
            <div className="mb-4 flex items-center gap-2 md:hidden">
              <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-accent text-white">
                <Shapes className="h-5 w-5" />
              </div>
              <div>
                <p className="text-sm font-semibold text-ink">Doc Canvas</p>
                <p className="text-xs text-ink/50">AI 文档画布</p>
              </div>
            </div>
            <h1 className="text-xl font-semibold text-ink">
              {mode === "login" ? "登录工作台" : "创建账号"}
            </h1>
            <p className="mt-1 text-sm text-ink/50">
              {mode === "login" ? "继续整理你的资料与思路。" : "注册后即可创建自己的画布。"}
            </p>
          </div>

          <div className="mb-6 grid grid-cols-2 gap-1 rounded-lg bg-ink/5 p-1">
            {([
              { value: "login", label: "登录" },
              { value: "register", label: "注册" }
            ] as const).map((item) => (
              <button
                key={item.value}
                type="button"
                aria-pressed={mode === item.value}
                onClick={() => switchMode(item.value)}
                className={clsx(
                  "rounded-md py-2 text-sm font-medium transition",
                  mode === item.value
                    ? "bg-white text-ink shadow-sm"
                    : "text-ink/50 hover:text-ink"
                )}
              >
                {item.label}
              </button>
            ))}
          </div>

          <form onSubmit={submit} className="space-y-4">
            {mode === "register" ? (
              <div>
                <Label htmlFor="displayName">昵称</Label>
                <div className="relative">
                  <User className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink/35" />
                  <Input
                    id="displayName"
                    className="pl-9"
                    value={displayName}
                    onChange={(event) => setDisplayName(event.target.value)}
                    placeholder="你的昵称"
                    required
                  />
                </div>
              </div>
            ) : null}

            <div>
              <Label htmlFor="email">邮箱</Label>
              <div className="relative">
                <Mail className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink/35" />
                <Input
                  id="email"
                  className="pl-9"
                  type="email"
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  placeholder="you@example.com"
                  autoComplete="email"
                  required
                />
              </div>
            </div>

            <div>
              <Label htmlFor="password">密码</Label>
              <div className="relative">
                <Lock className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink/35" />
                <Input
                  id="password"
                  className="pl-9"
                  type="password"
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  placeholder={mode === "register" ? "至少 6 位" : "输入密码"}
                  autoComplete={mode === "login" ? "current-password" : "new-password"}
                  required
                  minLength={mode === "register" ? 6 : undefined}
                />
              </div>
            </div>

            {mode === "login" ? (
              <p className="text-xs text-ink/45">已预填默认演示账号，可直接登录。</p>
            ) : null}

            {error ? (
              <p role="alert" className="rounded-lg bg-coral/10 px-3 py-2 text-sm text-coral">
                {error}
              </p>
            ) : null}

            <Button type="submit" className="w-full" disabled={submitting}>
              {submitting ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <ArrowRight className="h-4 w-4" />
              )}
              {mode === "login" ? "登录" : "创建账号"}
            </Button>
          </form>
        </section>
      </div>
    </main>
  );
}
