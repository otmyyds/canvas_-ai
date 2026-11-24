"use client";

import { useEffect, useRef, useState } from "react";
import type { Client, IMessage, StompSubscription } from "@stomp/stompjs";
import { API_BASE } from "@/lib/api";
import type { TaskEvent } from "@/lib/types";

/** WebSocket 连接状态 */
type ConnectionState = "idle" | "connecting" | "connected" | "closed" | "error";

/**
 * 订阅画布的 AI 任务事件流（WebSocket / STOMP + SockJS）。
 *
 * 连接流程：
 * 1. 动态 import @stomp/stompjs + sockjs-client（减小首屏体积）
 * 2. 用 SockJS 连接后端 /ws 端点
 * 3. 连接成功后订阅 /topic/boards/{boardId}/tasks
 * 4. 收到消息 → 解析为 TaskEvent → 回调 onEvent（父组件刷新任务/卡片/连线）
 *
 * 可靠性：
 * - reconnectDelay 2.5s：断线自动重连
 * - heartbeat 10s：心跳保活
 * - 组件卸载时 disconnect（清理订阅 + 关闭客户端）
 *
 * 为什么用 WebSocket 而非常规轮询？
 * 任务执行中有频繁的步骤/进度更新，WebSocket 实时推送避免轮询开销。
 * 页面刷新后通过 REST（api.listTasks）恢复任务状态，不依赖 WS 历史。
 *
 * @param boardId 画布 id（订阅对应 topic）
 * @param onEvent 收到任务事件时的回调
 * @returns 当前连接状态（驱动 UI 显示连接状态）
 */
export function useTaskStream(boardId: string, onEvent: (event: TaskEvent) => void,
    enabled = true) {
  const [state, setState] = useState<ConnectionState>("idle");
  // 用 ref 保存最新回调，避免 effect 因 onEvent 变化重复订阅
  const callbackRef = useRef(onEvent);

  callbackRef.current = onEvent;

  useEffect(() => {
    if (!enabled) {
      setState("idle");
      return;
    }
    let client: Client | undefined;
    let subscription: StompSubscription | undefined;
    let disposed = false;

    setState("connecting");

    // 动态加载 STOMP + SockJS 库
    Promise.all([import("@stomp/stompjs"), import("sockjs-client")])
      .then(([stomp, sockjsModule]) => {
        if (disposed) {
          return;
        }
        const SockJS = sockjsModule.default;
        client = new stomp.Client({
          webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
          reconnectDelay: 2500, // 断线 2.5s 后重连
          heartbeatIncoming: 10000, // 期望收到心跳间隔
          heartbeatOutgoing: 10000, // 发送心跳间隔
          onConnect: () => {
            setState("connected");
            // 订阅画布任务 topic：收到事件后解析并回调
            subscription = client?.subscribe(`/topic/boards/${boardId}/tasks`, (message: IMessage) => {
              callbackRef.current(JSON.parse(message.body) as TaskEvent);
            });
          },
          onWebSocketClose: () => setState("closed"),
          onStompError: () => setState("error")
        });
        client.activate();
      })
      .catch(() => setState("error"));

    // 清理：取消订阅 + 关闭连接
    return () => {
      disposed = true;
      subscription?.unsubscribe();
      client?.deactivate();
    };
  }, [boardId, enabled]);

  return state;
}
