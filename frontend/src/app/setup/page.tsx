"use client";

import { FormEvent, useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, Loader2, Lock, Mail, Shapes, User } from "lucide-react";
import { api, saveAuthSession } from "@/lib/api";
import { Button, Input, Label } from "@/components/ui";

const DEFAULT_EMAIL = "admin@doccanvas.com";
const DEFAULT_PASSWORD = "DocCanvas123";
const DEFAULT_DISPLAY_NAME = "Doc Canvas";

/**
 * 本地轻量版首次启动页面。
 * 只在数据库没有任何用户时可用；创建成功后直接登录并进入工作台。
 */
export default function SetupPage() {
  const router = useRouter();
  const [email, setEmail] = useState(DEFAULT_EMAIL);
  const [displayName, setDisplayName] = useState(DEFAULT_DISPLAY_NAME);
  const [password, setPassword] = useState(DEFAULT_PASSWORD);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string>();

  async function submit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setError(undefined);
    try {
      const result = await api.createInitialAdmin({
        email,
        displayName: displayName || email.split("@")[0],
        password
      });
      // 本地轻量版登录页默认填入刚创建的管理员；密码只保存在本机浏览器
      if (typeof window !== "undefined") {
        window.localStorage.setItem("dc_lite_admin_email", email);
        window.localStorage.setItem("dc_lite_admin_password", password);
      }
      saveAuthSession(result);
      router.replace("/app");
    } catch (err) {
      setError(err instanceof Error ? err.message : "初始化失败，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="relative flex min-h-screen items-center justify-center overflow-hidden bg-mist px-4 py-10">
      <div className="pointer-events-none absolute -left-24 -top-24 h-72 w-72 rounded-full bg-accent/10 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-32 -right-24 h-80 w-80 rounded-full bg-accent/10 blur-3xl" />

      <div className="relative w-full max-w-md rounded-2xl border border-ink/10 bg-white p-6 shadow-soft sm:p-8">
        <div className="mb-6 flex items-center gap-3">
          <div className="flex h-10 w-10 items-center justify-center rounded-xl bg-accent text-white">
            <Shapes className="h-5 w-5" />
          </div>
          <div>
            <p className="font-semibold text-ink">Doc Canvas</p>
            <p className="text-xs text-ink/50">本地轻量版首次启动</p>
          </div>
        </div>

        <h1 className="text-xl font-semibold text-ink">创建本地管理员</h1>
        <p className="mt-1 text-sm text-ink/50">
          这是本机第一次启动。创建管理员后会自动登录，之后可以随时在登录页使用该账号。
        </p>

        <form onSubmit={submit} className="mt-6 space-y-4">
          <div>
            <Label htmlFor="displayName">昵称</Label>
            <div className="relative">
              <User className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-ink/35" />
              <Input
                id="displayName"
                className="pl-9"
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                placeholder="Doc Canvas"
                required
              />
            </div>
          </div>

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
                placeholder="admin@doccanvas.com"
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
                placeholder="至少 6 位"
                autoComplete="new-password"
                required
                minLength={6}
              />
            </div>
          </div>

          {error ? (
            <p role="alert" className="rounded-lg bg-coral/10 px-3 py-2 text-sm text-coral">
              {error}
            </p>
          ) : null}

          <Button type="submit" className="w-full" disabled={submitting}>
            {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <ArrowRight className="h-4 w-4" />}
            创建并进入
          </Button>
        </form>

        <p className="mt-4 text-xs text-ink/40">
          默认账号和密码只用于本机首次体验，创建后建议在个人中心修改密码。
        </p>
      </div>
    </main>
  );
}
