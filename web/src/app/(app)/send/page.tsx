import { Suspense } from "react";
import { SendMoney } from "@/components/send-money";

export const metadata = { title: "Send money" };

export default function SendPage() {
  return (
    <Suspense>
      <SendMoney />
    </Suspense>
  );
}
