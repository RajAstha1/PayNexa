// @vitest-environment jsdom
import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import { beforeAll, describe, expect, it, vi } from "vitest";
import { Chart } from "@/components/charts/chart";
import { Icon } from "@/components/icons";
import { CurrencyAmount, Dialog, Pager, Segmented, StatusIndicator } from "@/components/ui";

beforeAll(() => {
  globalThis.ResizeObserver = class { observe() {} unobserve() {} disconnect() {} } as unknown as typeof ResizeObserver;
});

describe("CurrencyAmount", () => {
  it("shows the exact amount, with a readable label for assistive tech", () => {
    render(<CurrencyAmount amount="12480.50" currency="USD" />);
    expect(screen.getByLabelText("$12,480.50")).toBeInTheDocument();
  });
  it("hides the digits entirely when masked (they are not in the DOM)", () => {
    const { container } = render(<CurrencyAmount amount="12480.50" currency="USD" masked />);
    expect(container.textContent).not.toMatch(/12|480/);
    expect(screen.getByLabelText("Balance hidden")).toBeInTheDocument();
  });
  it("handles zero-decimal currencies", () => {
    render(<CurrencyAmount amount="500" currency="JPY" />);
    expect(screen.getByLabelText(/500/)).toBeInTheDocument();
  });
});

describe("StatusIndicator", () => {
  it("says the status in words, not only colour", () => {
    render(<StatusIndicator status="PENDING_REVIEW" />);
    expect(screen.getByText("Under review")).toBeInTheDocument();
  });
});

describe("Segmented", () => {
  it("reports which option is pressed and changes on click", async () => {
    const onChange = vi.fn();
    render(<Segmented label="Period" value={30} onChange={onChange} options={[{ value: 7, label: "7D" }, { value: 30, label: "30D" }]} />);
    expect(screen.getByRole("button", { name: "30D" })).toHaveAttribute("aria-pressed", "true");
    await userEvent.click(screen.getByRole("button", { name: "7D" }));
    expect(onChange).toHaveBeenCalledWith(7);
  });
});

describe("Pager", () => {
  it("disables previous on the first page and next when there is no more", () => {
    render(<Pager page={0} hasNext={false} onPrev={() => undefined} onNext={() => undefined} />);
    expect(screen.getByRole("button", { name: /Previous/ })).toBeDisabled();
    expect(screen.getByRole("button", { name: /Next/ })).toBeDisabled();
  });
});

function Harness({ dismissable = true }: { dismissable?: boolean }) {
  const [open, setOpen] = useState(false);
  return (
    <div>
      <button onClick={() => setOpen(true)}>Open</button>
      <Dialog open={open} onClose={() => setOpen(false)} title="Confirm" dismissable={dismissable} footer={<><button>Cancel</button><button>Send</button></>}>
        <p>Body</p>
      </Dialog>
    </div>
  );
}

describe("Dialog", () => {
  it("is labelled, traps Tab inside, closes on Escape and returns focus to the opener", async () => {
    const user = userEvent.setup();
    render(<Harness />);
    const opener = screen.getByRole("button", { name: "Open" });
    await user.click(opener);
    const dialog = screen.getByRole("dialog", { name: "Confirm" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    const buttons = within(dialog).getAllByRole("button");
    // close (x), Cancel, Send
    expect(buttons.map((b) => b.textContent || b.getAttribute("aria-label"))).toEqual(["Close dialog", "Cancel", "Send"]);
    await user.tab(); await user.tab(); await user.tab(); await user.tab();
    expect(dialog.contains(document.activeElement)).toBe(true);
    await user.tab({ shift: true });
    expect(dialog.contains(document.activeElement)).toBe(true);
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(opener).toHaveFocus();
  });
  it("cannot be dismissed with Escape while a request is in flight", async () => {
    const user = userEvent.setup();
    render(<Harness dismissable={false} />);
    await user.click(screen.getByRole("button", { name: "Open" }));
    await user.keyboard("{Escape}");
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Close dialog" })).toBeNull();
  });
});

describe("Chart", () => {
  const categories = ["Mon", "Tue", "Wed"].map((label, i) => ({ label, title: `${label} 1`, rows: [{ label: "Volume", value: `$${(i + 1) * 10}.00` }] }));
  const series = [{ key: "v", label: "Volume", color: "#9384f7", values: [10, 20, 30] }];
  it("is focusable, describes itself, and walks through points with the arrow keys", async () => {
    const user = userEvent.setup();
    render(<Chart kind="area" series={series} categories={categories} formatTick={(n) => String(n)} summary="Volume over three days" />);
    const plot = screen.getByRole("img", { name: "Volume over three days" });
    act(() => plot.focus());
    expect(plot).toHaveFocus();
    // focusing selects the latest point; announce it
    expect(screen.getAllByText(/Wed 1\. Volume \$30\.00/).length).toBeGreaterThan(0);
    await user.keyboard("{ArrowLeft}");
    expect(screen.getAllByText(/Tue 1\. Volume \$20\.00/).length).toBeGreaterThan(0);
    await user.keyboard("{Home}");
    expect(screen.getAllByText(/Mon 1\. Volume \$10\.00/).length).toBeGreaterThan(0);
    await user.keyboard("{Escape}");
    expect(screen.queryByText(/Mon 1\. Volume/)).toBeNull();
  });
  it("draws one bar per value per series", () => {
    const { container } = render(<Chart kind="bars" series={[...series, { ...series[0]!, key: "w" }]} categories={categories} formatTick={String} summary="Bars" />);
    expect(container.querySelectorAll("rect.chart-bar")).toHaveLength(6);
  });
});

describe("Icon", () => {
  it("is decorative unless titled", () => {
    const { container, rerender } = render(<Icon name="bell" />);
    expect(container.querySelector("svg")).toHaveAttribute("aria-hidden", "true");
    rerender(<Icon name="bell" title="Notifications" />);
    expect(screen.getByRole("img", { name: "Notifications" })).toBeInTheDocument();
  });
});
