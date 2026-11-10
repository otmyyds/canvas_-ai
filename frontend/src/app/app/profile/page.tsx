"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import {
  ArrowLeft,
  CalendarDays,
  Clock,
  KeyRound,
  LayoutGrid,
  Loader2,
  Mail,
  Save,
  ShieldCheck,
  User
} from "lucide-react";
import { api, authToken } from "@/lib/api";
import type { UserProfile } from "@/lib/api";
import { Button, Input, Label } from "@/components/ui";
import { ThemePicker } from "@/components/theme-picker";

type Feedback = {
  type: "success" | "error";
  text: string;
};

/** 完整日期时间：资料页信息量比首页卡片多，直接展示到分钟 */
function formatDate(value: string) {
  return new Date(value).toLocaleString("zh-CN", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function FeedbackText({ feedback }: { feedback?: Feedback }) {
  if (!feedback) {
    return null;
  }
  return (
    <p
      role="status"
      className={
        feedback.type === "success"
          ? "rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-600"
          : "rounded-lg bg-coral/10 px-3 py-2 text-sm text-coral"
      }
    >
      {feedback.text}
    </p>
  );
}

export default function ProfilePage() {
  const router = useRouter();
  const [profile, setProfile] = useState<UserProfile>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  // 基本资料表单
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [savingProfile, setSavingProfile] = useState(false);
  const [profileFeedback, setProfileFeedback] = useState<Feedback>();

  // 修改密码表单
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [savingPassword, setSavingPassword] = useState(false);
  const [passwordFeedback, setPasswordFeedback] = useState<Feedback>();

  useEffect(() => {
    if (!authToken.get()) {
      router.replace("/login");
      return;
    }
    api
      .getProfile()
      .then((data) => {
        setProfile(data);
        setDisplayName(data.displayName);
        setEmail(data.email);
      })
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, [router]);

  async function saveProfile(event: FormEvent) {
    event.preventDefault();
    const name = displayName.trim();
    const mail = email.trim();
    if (!name || !mail) {
      setProfileFeedback({ type: "error", text: "昵称和邮箱不能为空" });
      return;
    }
    setSavingProfile(true);
    setProfileFeedback(undefined);
    try {
      const updated = await api.updateProfile({ displayName: name, email: mail });
      setProfile(updated);
      setDisplayName(updated.displayName);
      setEmail(updated.email);
      setProfileFeedback({ type: "success", text: "资料已更新，新邮箱将用于下次登录" });
    } catch (err) {
      setProfileFeedback({
        type: "error",
        text: err instanceof Error ? err.message : "资料更新失败"
      });
    } finally {
      setSavingProfile(false);
    }
  }

  async function changePassword(event: FormEvent) {
    event.preventDefault();
    if (newPassword.length < 6) {
      setPasswordFeedback({ type: "error", text: "新密码至少 6 位" });
      return;
    }
    if (newPassword !== confirmPassword) {
      setPasswordFeedback({ type: "error", text: "两次输入的新密码不一致" });
      return;
    }
    setSavingPassword(true);
    setPasswordFeedback(undefined);
    try {
      await api.changePassword({ currentPassword, newPassword });
      setCurrentPassword("");
      setNewPassword("");
      setConfirmPassword("");
      setPasswordFeedback({ type: "success", text: "密码已修改，下次登录请使用新密码" });
    } catch (err) {
      setPasswordFeedback({
        type: "error",
        text: err instanceof Error ? err.message : "密码修改失败"
      });
    } finally {
      setSavingPassword(false);
    }
  }

  return (
    <main className="min-h-screen bg-mist">
      <header className="sticky top-0 z-30 border-b border-ink/10 bg-white/85 backdrop-blur">
        <div className="mx-auto flex h-16 max-w-4xl items-center justify-between px-6">
          <div className="flex items-center gap-3">
            <Link
              href="/app"
              className="inline-flex h-9 items-center gap-2 rounded-lg border border-ink/10 bg-white px-3 text-sm font-medium text-ink/65 shadow-sm transition hover:border-accent/40 hover:bg-accent/5 hover:text-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/25"
            >
              <ArrowLeft className="h-4 w-4" />
              返回工作台
            </Link>
            <span className="text-sm font-semibold text-ink">个人中心</span>
          </div>
          <ThemePicker />
        </div>
      </header>

      <section className="mx-auto max-w-4xl px-6 py-10">
        {loading ? (
          <div className="flex min-h-64 items-center justify-center rounded-2xl border border-ink/10 bg-white text-sm text-ink/55">
            <Loader2 className="mr-2 h-4 w-4 animate-spin" />
            正在加载用户资料
          </div>
        ) : error ? (
          <div className="rounded-lg border border-coral/30 bg-coral/10 px-4 py-3 text-sm text-coral">{error}</div>
        ) : profile ? (
          <>
            <div className="overflow-hidden rounded-2xl border border-ink/10 bg-white shadow-sm">
              <div className="h-24 bg-gradient-to-r from-accent to-accent/60" />
              <div className="px-6 pb-6">
                <div className="-mt-10 flex flex-wrap items-end gap-4">
                  <div className="flex h-20 w-20 items-center justify-center rounded-2xl border-4 border-white bg-accent text-2xl font-semibold text-white shadow-sm">
                    {profile.displayName.trim().charAt(0).toUpperCase() || <User className="h-7 w-7" />}
                  </div>
                  <div className="min-w-0 pb-1">
                    <h1 className="truncate text-xl font-semibold text-ink">{profile.displayName}</h1>
                    <p className="mt-1 inline-flex items-center gap-1.5 text-sm text-ink/55">
                      <Mail className="h-4 w-4" />
                      {profile.email}
                    </p>
                  </div>
                  <span className="mb-1 inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-1 text-xs font-medium text-emerald-600">
                    <ShieldCheck className="h-3.5 w-3.5" />
                    已登录
                  </span>
                </div>

                <div className="mt-5 grid gap-3 border-t border-ink/5 pt-5 sm:grid-cols-3">
                  <div className="flex items-center gap-2 text-sm text-ink/60">
                    <LayoutGrid className="h-4 w-4 text-ink/35" />
                    画布数量：<span className="font-medium text-ink">{profile.boardCount} 张</span>
                  </div>
                  <div className="flex items-center gap-2 text-sm text-ink/60">
                    <CalendarDays className="h-4 w-4 text-ink/35" />
                    注册于：<span className="font-medium text-ink">{formatDate(profile.createdAt)}</span>
                  </div>
                  <div className="flex items-center gap-2 text-sm text-ink/60">
                    <Clock className="h-4 w-4 text-ink/35" />
                    最近更新：<span className="font-medium text-ink">{formatDate(profile.updatedAt)}</span>
                  </div>
                </div>
              </div>
            </div>

            <div className="mt-5 grid gap-5 lg:grid-cols-2">
              <form
                onSubmit={saveProfile}
                className="rounded-2xl border border-ink/10 bg-white p-5 shadow-sm"
              >
                <div className="flex items-start gap-3">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-accent/10 text-accent">
                    <User className="h-5 w-5" />
                  </span>
                  <div>
                    <h2 className="text-sm font-semibold text-ink">基本资料</h2>
                    <p className="mt-1 text-xs leading-5 text-ink/50">修改昵称和登录邮箱。</p>
                  </div>
                </div>

                <div className="mt-5 space-y-4">
                  <div>
                    <Label htmlFor="displayName">昵称</Label>
                    <Input
                      id="displayName"
                      value={displayName}
                      maxLength={40}
                      required
                      onChange={(event) => setDisplayName(event.target.value)}
                    />
                  </div>
                  <div>
                    <Label htmlFor="email">邮箱</Label>
                    <Input
                      id="email"
                      type="email"
                      value={email}
                      required
                      autoComplete="email"
                      onChange={(event) => setEmail(event.target.value)}
                    />
                  </div>
                  <FeedbackText feedback={profileFeedback} />
                  <div className="flex justify-end">
                    <Button type="submit" disabled={savingProfile}>
                      {savingProfile ? (
                        <Loader2 className="h-4 w-4 animate-spin" />
                      ) : (
                        <Save className="h-4 w-4" />
                      )}
                      保存修改
                    </Button>
                  </div>
                </div>
              </form>

              <form
                onSubmit={changePassword}
                className="rounded-2xl border border-ink/10 bg-white p-5 shadow-sm"
              >
                <div className="flex items-start gap-3">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full bg-accent/10 text-accent">
                    <KeyRound className="h-5 w-5" />
                  </span>
                  <div>
                    <h2 className="text-sm font-semibold text-ink">修改密码</h2>
                    <p className="mt-1 text-xs leading-5 text-ink/50">需要先验证当前密码，新密码至少 6 位。</p>
                  </div>
                </div>

                <div className="mt-5 space-y-4">
                  <div>
                    <Label htmlFor="currentPassword">当前密码</Label>
                    <Input
                      id="currentPassword"
                      type="password"
                      value={currentPassword}
                      required
                      autoComplete="current-password"
                      placeholder="输入当前密码"
                      onChange={(event) => setCurrentPassword(event.target.value)}
                    />
                  </div>
                  <div>
                    <Label htmlFor="newPassword">新密码</Label>
                    <Input
                      id="newPassword"
                      type="password"
                      value={newPassword}
                      required
                      minLength={6}
                      autoComplete="new-password"
                      placeholder="至少 6 位"
                      onChange={(event) => setNewPassword(event.target.value)}
                    />
                  </div>
                  <div>
                    <Label htmlFor="confirmPassword">确认新密码</Label>
                    <Input
                      id="confirmPassword"
                      type="password"
                      value={confirmPassword}
                      required
                      minLength={6}
                      autoComplete="new-password"
                      placeholder="再次输入新密码"
                      onChange={(event) => setConfirmPassword(event.target.value)}
                    />
                  </div>
                  <FeedbackText feedback={passwordFeedback} />
                  <div className="flex justify-end">
                    <Button type="submit" disabled={savingPassword}>
                      {savingPassword ? (
                        <Loader2 className="h-4 w-4 animate-spin" />
                      ) : (
                        <KeyRound className="h-4 w-4" />
                      )}
                      修改密码
                    </Button>
                  </div>
                </div>
              </form>
            </div>

            <p className="mt-4 text-xs text-ink/40">资料保存在本地数据库，页面不会展示内部用户标识。</p>
          </>
        ) : null}
      </section>
    </main>
  );
}
