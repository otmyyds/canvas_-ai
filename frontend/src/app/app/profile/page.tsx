"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { ReactNode, useEffect, useState } from "react";
import {
  ArrowLeft,
  CalendarDays,
  Clock,
  LayoutGrid,
  Loader2,
  Mail,
  ShieldCheck,
  User
} from "lucide-react";
import { api, authToken } from "@/lib/api";
import type { UserProfile } from "@/lib/api";
import { ThemePicker } from "@/components/theme-picker";

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

function InfoCard({
  icon,
  label,
  value,
  mono = false
}: {
  icon: ReactNode;
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="rounded-xl border border-ink/10 bg-white p-4 shadow-sm">
      <div className="flex items-center gap-2 text-xs text-ink/45">
        {icon}
        {label}
      </div>
      <p className={mono ? "mt-2 break-all font-mono text-sm text-ink" : "mt-2 text-sm font-medium text-ink"}>
        {value}
      </p>
    </div>
  );
}

export default function ProfilePage() {
  const router = useRouter();
  const [profile, setProfile] = useState<UserProfile>();
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string>();

  useEffect(() => {
    if (!authToken.get()) {
      router.replace("/login");
      return;
    }
    api
      .getProfile()
      .then(setProfile)
      .catch((err: Error) => setError(err.message))
      .finally(() => setLoading(false));
  }, [router]);

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
              </div>
            </div>

            <div className="mt-5 grid gap-4 sm:grid-cols-2">
              <InfoCard
                icon={<User className="h-4 w-4" />}
                label="用户 ID"
                value={profile.id}
                mono
              />
              <InfoCard
                icon={<LayoutGrid className="h-4 w-4" />}
                label="画布数量"
                value={`${profile.boardCount} 张`}
              />
              <InfoCard
                icon={<CalendarDays className="h-4 w-4" />}
                label="注册时间"
                value={formatDate(profile.createdAt)}
              />
              <InfoCard
                icon={<Clock className="h-4 w-4" />}
                label="最近更新"
                value={formatDate(profile.updatedAt)}
              />
            </div>

            <p className="mt-4 text-xs text-ink/40">
              以上信息来自本地数据库 users 表，头像由昵称首字符生成。
            </p>
          </>
        ) : null}
      </section>
    </main>
  );
}
