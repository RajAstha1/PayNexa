"use client";
import Link from "next/link";
import { useMemo, useState } from "react";
import { Icon, type IconName } from "@/components/icons";
import { useShellNotifications } from "@/components/shell/notifications-context";
import { Alert, Button, Card, EmptyState, PageHeader, Segmented, Skeleton } from "@/components/ui";
import { api } from "@/lib/client/api";
import { relativeTime } from "@/lib/client/format";
import { NOTIFICATION_CATEGORIES, notificationInfo, type NotificationCategory } from "@/lib/client/labels";
import type { NotificationItem as Item } from "@/lib/client/types";

const ICONS: Record<NotificationCategory, IconName> = { payments: "swap", review: "clock", payouts: "arrow-up-right", issues: "alert" };

function NotificationRow({ n, busy, onRead }: { n: Item; busy: boolean; onRead: () => void }) {
  const info = notificationInfo(n.kind);
  return (
    <li className={`notif${n.read ? "" : " notif-unread"}`}>
      <span className={`notif-icon notif-${info.tone}`}><Icon name={ICONS[info.category]} size={18} /></span>
      <div className="notif-body">
        <div className="notif-title">
          <strong>{info.title}</strong>
          {!n.read && <span className="unread-dot" role="img" aria-label="Unread" />}
        </div>
        <p>{n.message}</p>
        <p className="muted small"><time dateTime={n.createdAt}>{relativeTime(n.createdAt)}</time> · <Link href={`/payments/${n.paymentId}`}>View payment</Link></p>
      </div>
      {!n.read && <Button variant="ghost" className="btn-sm" busy={busy} onClick={onRead}>Mark read</Button>}
    </li>
  );
}

export default function NotificationsPage() {
  // The shell polls the list (events arrive through Kafka a moment after a payment changes), so this page stays fresh.
  const { items, unread, loaded, reload } = useShellNotifications();
  const [category, setCategory] = useState<NotificationCategory | "all">("all");
  const [onlyUnread, setOnlyUnread] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    for (const n of items) if (!n.read) c[notificationInfo(n.kind).category] = (c[notificationInfo(n.kind).category] ?? 0) + 1;
    return c;
  }, [items]);
  const shown = useMemo(
    () => items.filter((n) => (category === "all" || notificationInfo(n.kind).category === category) && (!onlyUnread || !n.read)),
    [items, category, onlyUnread],
  );

  async function markRead(id: string) {
    setBusy(id);
    setFailed(false);
    try {
      await api.markRead(id);
    } catch {
      setFailed(true);
    } finally {
      setBusy(null);
      reload();
    }
  }
  async function markAll() {
    setBusy("all");
    setFailed(false);
    const results = await Promise.allSettled(items.filter((n) => !n.read).map((n) => api.markRead(n.id)));
    if (results.some((r) => r.status === "rejected")) setFailed(true);
    setBusy(null);
    reload();
  }

  return (
    <div className="stack">
      <PageHeader
        title="Notifications"
        subtitle="Built from payment events delivered through Kafka. Sandbox only: nothing is emailed or texted."
        actions={unread > 0 ? <Button variant="secondary" busy={busy === "all"} onClick={() => void markAll()}><Icon name="check" size={16} /> Mark all as read</Button> : undefined}
      />
      {failed && <Alert tone="bad" onClose={() => setFailed(false)}>Some notifications could not be marked as read. Try again.</Alert>}
      <Card flush>
        <div className="notif-toolbar">
          <div className="chip-row" role="group" aria-label="Category">
            <button type="button" className="chip chip-sm" aria-pressed={category === "all"} onClick={() => setCategory("all")}>All{unread > 0 ? ` · ${unread}` : ""}</button>
            {NOTIFICATION_CATEGORIES.map((c) => (
              <button key={c.id} type="button" className="chip chip-sm" aria-pressed={category === c.id} onClick={() => setCategory(c.id)}>
                {c.label}{counts[c.id] ? ` · ${counts[c.id]}` : ""}
              </button>
            ))}
          </div>
          <Segmented label="Show" value={onlyUnread ? "unread" : "all"} onChange={(v) => setOnlyUnread(v === "unread")} options={[{ value: "all", label: "All" }, { value: "unread", label: "Unread" }]} />
        </div>
        {!loaded ? (
          <div className="notif-list" role="status" aria-label="Loading notifications">{[0, 1, 2].map((i) => <div key={i} className="notif"><Skeleton width={40} height={40} radius={14} /><div className="notif-body"><Skeleton width="40%" /><Skeleton width="80%" /></div></div>)}</div>
        ) : shown.length === 0 ? (
          <EmptyState icon="bell" title={items.length === 0 ? "Nothing yet" : onlyUnread ? "You're all caught up" : "No notifications in this category"}>
            {items.length === 0 ? "Notifications appear shortly after a payment changes status." : "Adjust the filters to see more."}
          </EmptyState>
        ) : (
          <ul className="notif-list" aria-label="Notifications">
            {shown.map((n) => <NotificationRow key={n.id} n={n} busy={busy === n.id} onRead={() => void markRead(n.id)} />)}
          </ul>
        )}
      </Card>
    </div>
  );
}
