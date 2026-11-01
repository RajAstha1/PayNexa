"use client";
import { Button, EmptyState } from "@/components/ui";

export default function PageError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="card" style={{ marginTop: "var(--s-8)" }}>
      <EmptyState icon="alert" title="This page hit a problem" action={<Button onClick={reset}>Try again</Button>}>
        Nothing was sent or changed by this error. {error.digest ? <>Reference <code>{error.digest}</code>.</> : null}
      </EmptyState>
    </div>
  );
}
