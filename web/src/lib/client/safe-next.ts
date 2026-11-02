/**
 * Only same-site relative paths are honoured after sign-in, so a crafted ?next= can never bounce a user to another
 * site. Anything else falls back to the dashboard.
 */
export function safeNext(candidate: string | null): string {
  if (!candidate || !candidate.startsWith("/") || candidate.startsWith("//") || candidate.includes("\\") || /[\u0000-\u001f]/.test(candidate)) {
    return "/dashboard";
  }
  return candidate;
}
