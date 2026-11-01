import type { ReactNode, SVGProps } from "react";

/** A small outline icon set (24px grid, 1.6 stroke). Drawn for PayNexa; decorative unless given a title. */
export type IconName =
  | "dashboard" | "accounts" | "send" | "payments" | "bell" | "chart" | "shield" | "search" | "chevron-down" | "chevron-right"
  | "chevron-left" | "arrow-up-right" | "arrow-down-left" | "arrow-right" | "arrow-left" | "eye" | "eye-off" | "plus" | "help"
  | "logout" | "menu" | "close" | "check" | "alert" | "info" | "copy" | "filter" | "calendar" | "refresh" | "user" | "lock"
  | "swap" | "clock" | "inbox" | "sparkle" | "sliders";

const PATHS: Record<IconName, ReactNode> = {
  dashboard: <><rect x="3.5" y="3.5" width="7" height="8" rx="2" /><rect x="13.5" y="3.5" width="7" height="5" rx="2" /><rect x="13.5" y="11.5" width="7" height="9" rx="2" /><rect x="3.5" y="14.5" width="7" height="6" rx="2" /></>,
  accounts: <><path d="M4 8.5A2.5 2.5 0 0 1 6.5 6H18a2 2 0 0 1 2 2v1" /><path d="M4 8.5V17a2.5 2.5 0 0 0 2.5 2.5H18a2 2 0 0 0 2-2V11a2 2 0 0 0-2-2H6.5A2.5 2.5 0 0 1 4 6.5" /><circle cx="16.2" cy="14.2" r="1.1" /></>,
  send: <><path d="M20.5 3.5 10.8 13.2" /><path d="M20.5 3.5 14 20.5l-3.2-7.3L3.5 10 20.5 3.5Z" /></>,
  payments: <><path d="M7 4h10a2 2 0 0 1 2 2v14l-3-1.8L13 20l-3-1.8L7 20V6a2 2 0 0 1 0-2Z" transform="translate(-1 0)" /><path d="M9 9h6M9 12.5h4" /></>,
  bell: <><path d="M6 17.5h12l-1.4-2.2V10.5a4.6 4.6 0 0 0-9.2 0v4.8L6 17.5Z" /><path d="M10 20.2a2.2 2.2 0 0 0 4 0" /></>,
  chart: <><path d="M4 19.5h16" /><path d="M6.5 16V11M12 16V6.5M17.5 16v-3.5" /></>,
  shield: <><path d="M12 3.5 5 6v5.6c0 4.2 2.8 7.4 7 8.9 4.2-1.5 7-4.7 7-8.9V6l-7-2.5Z" /><path d="m9.2 12 2 2 3.8-4" /></>,
  search: <><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4" /></>,
  "chevron-down": <path d="m6 9.5 6 6 6-6" />,
  "chevron-right": <path d="m9.5 6 6 6-6 6" />,
  "chevron-left": <path d="m14.5 6-6 6 6 6" />,
  "arrow-up-right": <><path d="M7 17 17 7" /><path d="M8.5 7H17v8.5" /></>,
  "arrow-down-left": <><path d="M17 7 7 17" /><path d="M15.5 17H7V8.5" /></>,
  "arrow-right": <><path d="M4.5 12h15" /><path d="m13.5 6 6 6-6 6" /></>,
  "arrow-left": <><path d="M19.5 12h-15" /><path d="m10.5 6-6 6 6 6" /></>,
  eye: <><path d="M2.8 12S6 5.8 12 5.8 21.2 12 21.2 12 18 18.2 12 18.2 2.8 12 2.8 12Z" /><circle cx="12" cy="12" r="2.8" /></>,
  "eye-off": <><path d="M4 4l16 16" /><path d="M9.9 6.1A8.6 8.6 0 0 1 12 5.8c6 0 9.2 6.2 9.2 6.2a15 15 0 0 1-3 3.7M6.3 7.6A15 15 0 0 0 2.8 12S6 18.2 12 18.2c1.4 0 2.6-.3 3.7-.8" /><path d="M10 10.1a2.8 2.8 0 0 0 3.9 3.9" /></>,
  plus: <path d="M12 5v14M5 12h14" />,
  help: <><circle cx="12" cy="12" r="8.5" /><path d="M9.6 9.6a2.5 2.5 0 1 1 3.4 2.3c-.7.3-1 .8-1 1.6" /><path d="M12 16.6h.01" /></>,
  logout: <><path d="M10 4.5H7A2.5 2.5 0 0 0 4.5 7v10A2.5 2.5 0 0 0 7 19.5h3" /><path d="M14.5 8 19 12l-4.5 4M19 12H9.5" /></>,
  menu: <path d="M4 7h16M4 12h16M4 17h16" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  check: <path d="m5 12.5 4.5 4.5L19 7.5" />,
  alert: <><path d="M12 4 3 19.5h18L12 4Z" /><path d="M12 10v4.2M12 16.9h.01" /></>,
  info: <><circle cx="12" cy="12" r="8.5" /><path d="M12 11v5M12 8h.01" /></>,
  copy: <><rect x="8.5" y="8.5" width="11" height="11" rx="2.2" /><path d="M15.5 8.5V6.7a2.2 2.2 0 0 0-2.2-2.2H6.7a2.2 2.2 0 0 0-2.2 2.2v6.6a2.2 2.2 0 0 0 2.2 2.2h1.8" /></>,
  filter: <path d="M4 6h16M7 12h10M10 18h4" />,
  calendar: <><rect x="4" y="5.5" width="16" height="14.5" rx="2.5" /><path d="M8 3.5v4M16 3.5v4M4 10.5h16" /></>,
  refresh: <><path d="M19.5 12a7.5 7.5 0 0 1-13 5.1" /><path d="M4.5 12a7.5 7.5 0 0 1 13-5.1" /><path d="M17.5 3.5v3.8h-3.8M6.5 20.5v-3.8h3.8" /></>,
  user: <><circle cx="12" cy="8.5" r="3.6" /><path d="M5 19.5c.6-3.4 3.4-5.2 7-5.2s6.4 1.8 7 5.2" /></>,
  lock: <><rect x="5" y="10.5" width="14" height="9.5" rx="2.5" /><path d="M8.5 10.5V8a3.5 3.5 0 0 1 7 0v2.5" /></>,
  swap: <><path d="M5 8.5h13.5M15 5l3.5 3.5L15 12" /><path d="M19 15.5H5.5M9 12l-3.5 3.5L9 19" /></>,
  clock: <><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></>,
  inbox: <><path d="M4 13.5 6.2 6.6A2 2 0 0 1 8.1 5.2h7.8a2 2 0 0 1 1.9 1.4L20 13.5" /><path d="M4 13.5v4A2 2 0 0 0 6 19.5h12a2 2 0 0 0 2-2v-4h-4.5l-1 2h-5l-1-2H4Z" /></>,
  sparkle: <path d="M12 4v5M12 15v5M4 12h5M15 12h5" />,
  sliders: <><path d="M5 7h8M17 7h2M5 17h2M11 17h8" /><circle cx="15" cy="7" r="2" /><circle cx="9" cy="17" r="2" /></>,
};

interface IconProps extends Omit<SVGProps<SVGSVGElement>, "name"> {
  name: IconName;
  size?: number;
  /** Provide when the icon is the only content of a control; otherwise it is hidden from assistive tech. */
  title?: string;
}

export function Icon({ name, size = 20, title, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
      focusable="false"
      {...rest}
    >
      {PATHS[name]}
    </svg>
  );
}
