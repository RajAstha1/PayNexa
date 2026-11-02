import type { ReactNode } from "react";

/** Remounted on each navigation: the content fades in over 200 ms while the header and footer stay still. */
export default function AuthTemplate({ children }: { children: ReactNode }) {
  return <div className="auth-stage auth-enter">{children}</div>;
}
