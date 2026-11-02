// @vitest-environment jsdom
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { validateCredentials } from "@/components/auth/auth-form";
import { LedgerSheet } from "@/components/auth/ledger-sheet";
import { PasswordField } from "@/components/auth/password-field";
import { PayNexaLogo } from "@/components/brand/logo";
import { safeNext } from "@/lib/client/safe-next";

describe("safeNext (post-login redirect target)", () => {
  it("keeps same-site paths, including query strings", () => {
    expect(safeNext("/payments")).toBe("/payments");
    expect(safeNext("/payments?status=FAILED&dir=out")).toBe("/payments?status=FAILED&dir=out");
  });
  it("refuses anything that could leave the site or smuggle a header", () => {
    for (const bad of ["https://evil.example", "//evil.example", "/\\evil.example", "javascript:alert(1)", "evil", "", null, "/ok\r\nSet-Cookie: x=1", "/a\u0000b"]) {
      expect(safeNext(bad as string | null), String(bad)).toBe("/dashboard");
    }
  });
});

describe("validateCredentials", () => {
  it("accepts a plausible email and any password on sign-in", () => {
    expect(validateCredentials("jane@example.com", "x", false)).toEqual({});
  });
  it("rejects malformed emails and an empty password on sign-in", () => {
    expect(validateCredentials("nope", "", false)).toEqual({ email: "Enter a valid email address", password: "Enter your password" });
    expect(validateCredentials("a@b", "pw", false).email).toBeDefined();
  });
  it("requires twelve characters to register, counted in characters, not bytes", () => {
    expect(validateCredentials("jane@example.com", "short", true).password).toBe("Use at least 12 characters");
    expect(validateCredentials("jane@example.com", "twelve chars", true)).toEqual({});
    expect(validateCredentials("jane@example.com", "", true).password).toBe("Use at least 12 characters");
  });
});

function Harness({ error }: { error?: string }) {
  const [value, setValue] = useState("");
  const [tick, setTick] = useState(0);
  return (
    <div>
      <PasswordField id="pw" label="Password" value={value} onChange={setValue} autoComplete="new-password" minLength={12} hint="At least 12 characters." error={error} hidden={tick} />
      <button onClick={() => setTick((t) => t + 1)}>rehide</button>
    </div>
  );
}

describe("PasswordField", () => {
  it("hides by default and the toggle reports and flips its state", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const input = screen.getByLabelText("Password", { selector: "input" });
    expect(input).toHaveAttribute("type", "password");
    const toggle = screen.getByRole("button", { name: "Show password" });
    expect(toggle).toHaveAttribute("aria-pressed", "false");
    expect(toggle).toHaveAttribute("aria-controls", "pw");
    await user.click(toggle);
    expect(input).toHaveAttribute("type", "text");
    expect(screen.getByRole("button", { name: "Hide password" })).toHaveAttribute("aria-pressed", "true");
  });
  it("goes back to hidden when the parent asks (after a failed sign-in)", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    await user.click(screen.getByRole("button", { name: "Show password" }));
    await user.click(screen.getByRole("button", { name: "rehide" }));
    expect(screen.getByLabelText("Password", { selector: "input" })).toHaveAttribute("type", "password");
  });
  it("counts characters toward the minimum and links errors to the input", async () => {
    const user = userEvent.setup();
    const { rerender } = render(<Harness />);
    expect(screen.queryByText(/\/ 12/)).toBeNull();
    await user.type(screen.getByLabelText("Password", { selector: "input" }), "abcdef");
    expect(screen.getByText("6 / 12")).not.toHaveClass("met");
    await user.type(screen.getByLabelText("Password", { selector: "input" }), "ghijkl");
    expect(screen.getByText("12 / 12")).toHaveClass("met");
    rerender(<Harness error="Use at least 12 characters" />);
    const input = screen.getByLabelText("Password", { selector: "input" });
    expect(input).toHaveAttribute("aria-invalid", "true");
    expect(input).toHaveAttribute("aria-describedby", "pw-error");
  });
});

describe("LedgerSheet", () => {
  it("is labelled as an example and its transfer entries cancel out", () => {
    render(<LedgerSheet variant="transfer" />);
    expect(screen.getByText("Example")).toBeInTheDocument();
    const table = screen.getByRole("table");
    expect(within(table).getByText("\u221225.00")).toBeInTheDocument();
    expect(within(table).getByText("+25.00")).toBeInTheDocument();
    expect(within(table).getByText("0.00")).toBeInTheDocument();
    expect(screen.getByRole("list", { name: "Stages of a payment" }).querySelectorAll("li")).toHaveLength(3);
  });
  it("shows a new account starting at zero, with no invented history", () => {
    render(<LedgerSheet variant="opening" />);
    expect(screen.getByText("No entries yet")).toBeInTheDocument();
    expect(screen.getByText("Opening balance")).toBeInTheDocument();
  });
});

describe("PayNexaLogo", () => {
  it("renders the wordmark and a decorative mark", () => {
    const { container } = render(<PayNexaLogo />);
    expect(screen.getByText("PayNexa")).toBeInTheDocument();
    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
  });
});
