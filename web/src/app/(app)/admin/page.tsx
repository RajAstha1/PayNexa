"use client";
import { useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { useAuth } from "@/components/auth-provider";
import { AdminAccounts, AdminOperations, AdminReconciliation, ReviewQueue } from "@/components/admin-panels";
import { EmptyState, Alert, Card, PageHeader, Skeleton } from "@/components/ui";

const TABS = [
  { id: "review", label: "Review queue" },
  { id: "accounts", label: "Accounts" },
  { id: "reconciliation", label: "Reconciliation" },
  { id: "operations", label: "Operations" },
] as const;
type TabId = (typeof TABS)[number]["id"];

function AdminView() {
  const auth = useAuth();
  const params = useSearchParams();
  const requested = TABS.find((t) => t.id === params.get("tab"))?.id;
  const [tab, setTab] = useState<TabId>(requested ?? "review");

  // The services enforce ROLE_ADMIN on every call; this only avoids showing tools that would be refused.
  if (!auth.isAdmin) {
    return <Card><EmptyState icon="lock" title="Administrators only">Your account does not have access to this area.</EmptyState></Card>;
  }
  return (
    <div className="stack">
      <PageHeader title="Administration" subtitle="Every action here is checked again by the services and written to the audit log." />
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

export default function AdminPage() {
  return (
    <Suspense fallback={<Skeleton height={320} radius={22} />}>
      <AdminView />
    </Suspense>
  );
}
