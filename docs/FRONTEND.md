# Web console: design system and scope

The console lives in `web/` (Next.js, React, TypeScript). It talks only to the BFF routes under `/api`; the BFF holds the tokens in HttpOnly cookies and forwards an allow-listed set of calls to the services.

## Visual system
- Tokens: `web/src/styles/tokens.css` (colour, type, 4px spacing, radii, shadows, motion). Components use variables, never raw colours. Token names describe a role (`--accent-*` forest green, `--ink-*` midnight charcoal, `--bg`/`--surface`/`--border` ivory), not a hue.
- Palette: warm ivory canvas `#F8F8F4`, white working surfaces, soft surface `#F0F2EB`, hairline `#E5E7DF`, text `#20251F`, brand green `#315B46`. Green is used for actions, focus, active states and chart fill; charcoal for the sidebar, bottom bar and chart tooltips.
- Type: Geist for everything, Geist Mono for ledger figures. Moderate weights (400/500/600), tight tracking on headings, no oversized headlines (page titles are 28 px, the signed-in greeting at most 36 px).
- Shape: small radii (6 px inputs, 8 px buttons, 14 px cards). Elevation is nearly flat; hierarchy comes from hairlines, type and spacing.
- Brand: the mark is a geometric P drawn as one stroke with a small square node at the foot of the bowl (`components/brand/logo.tsx`, `app/icon.svg`). Wordmark is Geist 600.
- Motion: 150-240 ms. One content fade between pages, focus and hover transitions, a short chart reveal. Nothing loops. `prefers-reduced-motion` switches all of it off.

### Deliberate deviations from the brief's palette
- Secondary text is `#666c63`, not `#777d74`. The brief's value is 3.9:1 on the ivory canvas; this one is 5.0:1 and passes WCAG AA for body text. `--text-3` (`#9aa095`) is used only for placeholders and decoration.
- Text in the negative and pending colours uses darker variants (`--negative-text`, `--pending`). The lighter red and amber remain for dots, borders and fills.
- Ordinary money out is shown in the normal text colour with a minus sign. Red is reserved for failure.
- No dark mode.

## Sign-in screens (`app/(auth)`)
- One route group shares a slim header (logo left, "New to PayNexa? Create account" right) and a footer line ("Secure sandbox environment · Synthetic funds only"). Only the content between them transitions.
- Composition at >= 1000 px: a 12-column stage. The form takes the left five columns, aligned with the logo; a ledger sheet sits in the right six, dropped 64 px lower so the tops do not line up. 640-999 px: the form, then a band with the caption beside the sheet. Below 640 px: the form only, with 44 px inputs and a 48 px full-width button.
- The sheet is labelled "Example" and shows how this platform records money (two ledger entries that cancel out; a new account starts at 0.00). It is an explanation, not data about a user.
- Only supported features appear. There is no "Forgot password" and no "Remember me" because identity-service has no reset flow and sessions always last as long as the refresh token. If either is added to the backend, add it to `components/auth/auth-form.tsx`.
- Validation: the email is checked when the field is left, the password on submit; messages fade in. A failed sign-in clears the password, hides it again and returns focus to it. The submit button shows a spinner and refuses a second submit.

## Layout
| Width | Navigation |
|---|---|
| >= 768 px | no permanent sidebar. A menu button in the header slides a charcoal panel over the page; Escape, the close button or a click outside dismisses it, and focus returns to the button |
| < 768 px | bottom bar (Home, Accounts, Send, Payments, Menu) and a drawer |

## What is real and what is left out
Everything shown comes from the services. Nothing is estimated, padded or invented.
- **Balance card** shows one currency at a time; currencies are never added together (no FX exists). "Net movement, last 7 days" comes from `GET /payments/stats` and is omitted when there is no data.
- **Counterparties** appear as masked account ids. The services return no names for other people.
- **Display name** is derived from the sign-in email (the services store no profile name).
- **Charts** use `/payments/stats` (completed, in-flight and settled payments; moves between the user's own accounts are excluded).
- **Add Funds** is shown to administrators only (sandbox funding). Card top-up is not built.

Not built, because the backend has no such feature: virtual cards, currency conversion, user management, user directory/lookup, audit-log browsing, fees.

## Tests
- `npm run lint`, `npm run typecheck`, `npm test` (unit and component tests).
- `npm run e2e` drives a real browser against a running stack (see `web/playwright.config.ts`): user journeys, filters, notifications, keyboard use, reduced motion, and a five-width responsive pass that fails on sideways scrolling.
