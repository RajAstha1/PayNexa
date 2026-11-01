import { useId } from "react";

/**
 * The PayNexa mark: a rounded tile holding an "N" drawn as one continuous path that starts and ends in a node,
 * a nod to value moving between two endpoints. Original artwork.
 */
export function PayNexaMark({ size = 36 }: { size?: number }) {
  const gradient = useId();
  return (
    <svg width={size} height={size} viewBox="0 0 40 40" fill="none" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={gradient} x1="4" y1="2" x2="36" y2="38" gradientUnits="userSpaceOnUse">
          <stop stopColor="#B3A8FF" />
          <stop offset="1" stopColor="#7B6BEA" />
        </linearGradient>
      </defs>
      <rect width="40" height="40" rx="12" fill={`url(#${gradient})`} />
      <path d="M12.5 28.5V12.8c0-.6.7-.9 1.1-.4l12.8 15.2c.4.5 1.1.2 1.1-.4V11.5" stroke="#171A35" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="27.5" cy="11.5" r="2.6" fill="#fff" />
    </svg>
  );
}

export function PayNexaLogo({ size = 36, tone = "light" }: { size?: number; tone?: "light" | "dark" }) {
  return (
    <span className={`logo logo-${tone}`}>
      <PayNexaMark size={size} />
      <span className="logo-word">PayNexa</span>
    </span>
  );
}
