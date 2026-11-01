"use client";
import { createContext, useContext, useMemo, type ReactNode } from "react";
import { api } from "@/lib/client/api";
import type { NotificationItem } from "@/lib/client/types";
import { useAsync } from "@/lib/client/use-async";
import { useAuth } from "../auth-provider";

interface Value {
  items: NotificationItem[];
  unread: number;
  loaded: boolean;
  reload: () => void;
}
const Ctx = createContext<Value>({ items: [], unread: 0, loaded: false, reload: () => undefined });

export const useShellNotifications = () => useContext(Ctx);

/**
 * One poll of the notification list for the whole shell (bell, nav badge, notifications page). Notifications arrive
 * asynchronously through Kafka, so the list is refreshed on an interval rather than read once.
 */
export function NotificationsProvider({ children }: { children: ReactNode }) {
  const auth = useAuth();
  const list = useAsync((s) => api.notifications(s), [auth.status], {
    enabled: auth.status === "authenticated",
    pollMs: 8000,
    pollWhile: () => true,
    keepPrevious: true,
  });
  const value = useMemo<Value>(() => {
    const items = list.data ?? [];
    return { items, unread: items.filter((n) => !n.read).length, loaded: list.data !== null, reload: list.reload };
  }, [list.data, list.reload]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}
