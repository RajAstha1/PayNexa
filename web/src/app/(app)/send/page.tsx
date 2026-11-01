import { Suspense } from "react";
import { SendMoney } from "@/components/send/send-money";
import { Skeleton } from "@/components/ui";

export const metadata = { title: "Send money" };

export default function SendPage() {
  return (
    <Suspense fallback={<Skeleton height={420} radius={22} />}>
      <SendMoney />
    </Suspense>
  );
}
