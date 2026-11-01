import type { Metadata } from "next";
import { Suspense } from "react";
import { PaymentsView } from "@/components/payments/payments-view";
import { Skeleton } from "@/components/ui";

export const metadata: Metadata = { title: "Payments" };

export default function PaymentsPage() {
  return (
    <Suspense fallback={<Skeleton height={320} radius={22} />}>
      <PaymentsView />
    </Suspense>
  );
}
