import type { IconName } from "../icons";

export interface NavItem {
  href: string;
  label: string;
  icon: IconName;
  adminOnly?: boolean;
}

export const NAV_ITEMS: NavItem[] = [
  { href: "/dashboard", label: "Dashboard", icon: "dashboard" },
  { href: "/accounts", label: "Accounts", icon: "accounts" },
  { href: "/send", label: "Send Money", icon: "send" },
  { href: "/payments", label: "Payments", icon: "payments" },
  { href: "/notifications", label: "Notifications", icon: "bell" },
  { href: "/analytics", label: "Analytics", icon: "chart" },
  { href: "/admin", label: "Admin", icon: "shield", adminOnly: true },
];

export function visibleNav(isAdmin: boolean): NavItem[] {
  return NAV_ITEMS.filter((i) => !i.adminOnly || isAdmin);
}

export function isActive(pathname: string, href: string): boolean {
  return pathname === href || pathname.startsWith(href + "/");
}
