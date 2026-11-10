"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { Loader2 } from "lucide-react";
import { api, saveAuthSession } from "@/lib/api";
import { Button, GhostButton, Input, Label } from "@/components/ui";

export default function LoginPage() {
  const router = useRouter();
  const [mode, setMode] = useState<"login" | "register">("login");
  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();

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
    <main className="flex min-h-screen items-center justify-center bg-mist">
      <div className="w-full max-w-sm rounded-xl border border-ink/10 bg-white p-8 shadow-sm">
        <div className="mb-6 text-center">
          <h1 className="text-xl font-semibold text-ink">Doc Canvas</h1>
          <p className="mt-1 text-sm text-ink/50">单人 AI 文档画布</p>
        </div>

        <form onSubmit={submit} className="space-y-4">
          {mode === "register" ? (
            <div>
              <Label htmlFor="displayName">昵称</Label>
              <Input
                id="displayName"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="你的昵称"
                required
              />
            </div>
          ) : null}
          <div>
            <Label htmlFor="email">邮箱</Label>
            <Input
              id="email"
              type="email"
              value={email}
              onChange={(event) => setEmail(event.target.value)}
              placeholder="you@example.com"
              required
            />
          </div>
          <div>
            <Label htmlFor="password">密码</Label>
            <Input
              id="password"
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              placeholder={mode === "register" ? "至少 6 位" : "输入密码"}
              required
              minLength={mode === "register" ? 6 : undefined}
            />
          </div>

          {error ? <p className="text-sm text-coral">{error}</p> : null}

          <Button className="w-full" disabled={submitting}>
            {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
            {mode === "login" ? "登录" : "注册"}
          </Button>
        </form>

        <div className="mt-4 text-center text-sm text-ink/55">
          {mode === "login" ? (
            <span>
              还没有账号？{" "}
              <GhostButton className="text-accent" onClick={() => setMode("register")}>
                去注册
              </GhostButton>
            </span>
          ) : (
            <span>
              已有账号？{" "}
              <GhostButton className="text-accent" onClick={() => setMode("login")}>
                去登录
              </GhostButton>
            </span>
          )}
        </div>
      </div>
    </main>
  );
}
