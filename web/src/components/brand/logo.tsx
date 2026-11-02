/**
 * The PayNexa mark: a geometric P drawn as one stroke (stem, then bowl) with a small square "node" at the foot of
 * the bowl, the point a payment arrives at. Square joins, no container, two colours. Original artwork.
 */
export function PayNexaMark({ size = 28, tone = "dark" }: { size?: number; tone?: "dark" | "light" }) {
  const stroke = tone === "dark" ? "#315B46" : "#ECEFE6";
  const node = "#7FA38B";
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden="true" focusable="false">
      <path d="M9 28V5h8a6.5 6.5 0 0 1 0 13H9" stroke={stroke} strokeWidth="4.5" strokeLinejoin="miter" />
      <rect x="19.5" y="23.5" width="5" height="5" fill={node} />
    </svg>
  );
}

/** `tone` is the background it sits on: "dark" tone text is ivory (for the charcoal sidebar), "light" is for ivory pages. */
export function PayNexaLogo({ size = 28, on = "light" }: { size?: number; on?: "light" | "dark" }) {
  return (
    <span className={`logo logo-on-${on}`}>
      <PayNexaMark size={size} tone={on === "dark" ? "light" : "dark"} />
      <span className="logo-word">PayNexa</span>
    </span>
  );
}
