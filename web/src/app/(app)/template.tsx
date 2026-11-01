import type { ReactNode } from "react";

/** A template (unlike a layout) is remounted on every navigation, which replays the short entrance animation. */
export default function Template({ children }: { children: ReactNode }) {
  return <div className="page-enter">{children}</div>;
}
