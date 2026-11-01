"use client";

import Link from "next/link";
import { useState } from "react";
import { Button, Card, EmptyState, ErrorNotice, Loading, when } from "@/components/ui";
import { api } from "@/lib/client/api";
import { useAsync } from "@/lib/client/use-async";

export default function NotificationsPage() {
  // Notifications are produced asynchronously (payment event -> Kafka -> consumer), so they can arrive a moment after
  // the page opens. Keep the list fresh instead of showing the snapshot taken at load time.
  const list = useAsync((s) => api.notifications(s), [], { pollMs: 4000, pollWhile: () => true });
  const [marking, setMarking] = useState<string | null>(null);
  const unread = list.data?.filter((n) => !n.read) ?? [];

  async function markRead(id: string) {
    setMarking(id);
    try {
      await api.markRead(id);
      list.reload();
    } finally {
      setMarking(null);
    }
  }

  async function markAll() {
    for (const n of unread) await api.markRead(n.id).catch(() => undefined);
    list.reload();
  }

  return (
    <div className="stack">
      <div className="row-between page-head">
        <div>
          <h1>Notifications</h1>
          <p className="muted" style={{ margin: 0 }}>Built from payment events delivered through Kafka. Sandbox only: nothing is emailed or texted.</p>
        </div>
        {unread.length > 0 && <Button variant="secondary" onClick={() => void markAll()}>Mark all as read</Button>}
      </div>
      <Card>
        {list.loading && <Loading label="Loading notifications" />}
        {list.error && <ErrorNotice error={list.error} />}
        {list.data?.length === 0 && <EmptyState title="Nothing yet">Notifications appear shortly after a payment changes status.</EmptyState>}
        {list.data?.map((n) => (
          <div className="notif" key={n.id}>
            <span className={n.read ? "notif-dot read" : "notif-dot"} aria-label={n.read ? "Read" : "Unread"} role="img" />
            <div className="notif-body">
              <div style={{ fontWeight: n.read ? 400 : 600 }}>{n.message}</div>
              <div className="muted small">
                {when(n.createdAt)} · <Link href={`/payments/${n.paymentId}`}>View payment</Link>
              </div>
            </div>
            {!n.read && <Button variant="ghost" className="btn-sm" busy={marking === n.id} onClick={() => void markRead(n.id)}>Mark read</Button>}
          </div>
        ))}
      </Card>
    </div>
  );
}
