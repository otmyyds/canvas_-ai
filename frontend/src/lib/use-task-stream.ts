"use client";

import { useEffect, useRef, useState } from "react";
import type { Client, IMessage, StompSubscription } from "@stomp/stompjs";
import { API_BASE } from "@/lib/api";
import type { TaskEvent } from "@/lib/types";

type ConnectionState = "idle" | "connecting" | "connected" | "closed" | "error";

export function useTaskStream(boardId: string, onEvent: (event: TaskEvent) => void) {
  const [state, setState] = useState<ConnectionState>("idle");
  const callbackRef = useRef(onEvent);

  callbackRef.current = onEvent;

  useEffect(() => {
    let client: Client | undefined;
    let subscription: StompSubscription | undefined;
    let disposed = false;

    setState("connecting");

    Promise.all([import("@stomp/stompjs"), import("sockjs-client")])
      .then(([stomp, sockjsModule]) => {
        if (disposed) {
          return;
        }
        const SockJS = sockjsModule.default;
        client = new stomp.Client({
          webSocketFactory: () => new SockJS(`${API_BASE}/ws`),
          reconnectDelay: 2500,
          heartbeatIncoming: 10000,
          heartbeatOutgoing: 10000,
          onConnect: () => {
            setState("connected");
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

    return () => {
      disposed = true;
      subscription?.unsubscribe();
      client?.deactivate();
    };
  }, [boardId]);

  return state;
}
