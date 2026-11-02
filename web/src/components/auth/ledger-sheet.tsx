import type { ReactNode } from "react";

/**
 * A small explanatory sheet shown beside the sign-in forms. It is an illustration, not account data: it is labelled
 * "Example" and shows how this platform records money (every transfer is two ledger entries that cancel out, and a
 * new account starts at zero). Nothing on it is fetched or invented about a user.
 */
type Variant = "transfer" | "opening";

const STEPS: Record<Variant, string[]> = {
  transfer: ["Submitted", "Funds checked", "Completed"],
  opening: ["Opened", "Funded (sandbox)", "Ready to send"],
};

export function LedgerSheet({ variant }: { variant: Variant }) {
  const transfer = variant === "transfer";
  return (
    <figure className="ledger">
      <div className="ledger-head">
        <span className="ledger-tag">Example</span>
        <span>{transfer ? "How one transfer is recorded" : "How a new account starts"}</span>
      </div>
      <table className="ledger-table">
        <caption className="visually-hidden">
          {transfer ? "Example ledger entries for a transfer of 25.00 USD" : "Example statement for a newly opened USD account"}
        </caption>
        <thead>
          <tr>
            <th scope="col">{transfer ? "Account" : "Line"}</th>
            <th scope="col">Entry</th>
            <th scope="col" className="num-col">USD</th>
          </tr>
        </thead>
        <tbody>
          {transfer ? (
            <>
              <Row a="From account" b="Debit" c={"\u221225.00"} />
              <Row a="To account" b="Credit" c="+25.00" />
            </>
          ) : (
            <>
              <Row a="Opening balance" b="Opened" c="0.00" />
              <Row a={<span className="faint">No entries yet</span>} b="" c="" />
            </>
          )}
        </tbody>
        <tfoot>
          <tr>
            <th scope="row" colSpan={2}>{transfer ? "Net effect on the books" : "Balance"}</th>
            <td className="num-col">0.00</td>
          </tr>
        </tfoot>
      </table>
      <ol className="ledger-flow" aria-label={transfer ? "Stages of a payment" : "Stages of a new account"}>
        {STEPS[variant].map((label, i, all) => (
          <li key={label} className={i === all.length - 1 ? "last" : undefined}>
            <span className="ledger-node" aria-hidden="true" />
            {label}
          </li>
        ))}
      </ol>
    </figure>
  );
}

function Row({ a, b, c }: { a: ReactNode; b: string; c: string }) {
  return (
    <tr>
      <th scope="row">{a}</th>
      <td>{b}</td>
      <td className="num-col">{c}</td>
    </tr>
  );
}

export function LedgerCaption({ variant }: { variant: Variant }) {
  return variant === "transfer" ? (
    <p className="ledger-caption">
      Every transfer is written twice, once out and once in, so the books always balance. A payment above the review
      limit waits for a second person to approve it before any money moves.
    </p>
  ) : (
    <p className="ledger-caption">
      Accounts start empty and hold one currency each. In this sandbox, an administrator can add synthetic funds.
    </p>
  );
}
