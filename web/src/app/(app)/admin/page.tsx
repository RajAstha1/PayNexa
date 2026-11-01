"use client";

import { useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { AdminAccounts, AdminOperations, AdminReconciliation, ReviewQueue } from "@/components/admin-panels";
import { Alert, Card } from "@/components/ui";

const TABS = [
  { id: "review", label: "Review queue" },
  { id: "accounts", label: "Accounts" },
  { id: "reconciliation", label: "Reconciliation" },
  { id: "operations", label: "Operations" },
] as const;

type TabId = (typeof TABS)[number]["id"];

export default function AdminPage() {
  const auth = useAuth();
  const [tab, setTab] = useState<TabId>("review");

  if (!auth.isAdmin) {
    return (
      <Card>
        <h1>Administrators only</h1>
        <p className="muted">Your account does not have access to this area.</p>
      </Card>
    );
  }

  return (
    <div className="stack">
      <div className="page-head">
        <h1>Administration</h1>
        <p className="muted">Every action here is checked again by the services and written to the audit log.</p>
      </div>
      <Alert tone="info">Sandbox tools such as funding create synthetic money. They are disabled in the service unless sandbox funding is switched on.</Alert>
      <div className="tabs" role="tablist" aria-label="Administration sections">
        {TABS.map((t) => (
          <button key={t.id} type="button" role="tab" id={`tab-${t.id}`} aria-selected={tab === t.id} aria-controls={`panel-${t.id}`} className="tab" onClick={() => setTab(t.id)}>
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === "review" && <ReviewQueue />}
        {tab === "accounts" && <AdminAccounts />}
        {tab === "reconciliation" && <AdminReconciliation />}
        {tab === "operations" && <AdminOperations />}
      </div>
    </div>
  );
}
