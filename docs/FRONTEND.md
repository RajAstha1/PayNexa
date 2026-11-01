# Web console: design system and scope

The console lives in `web/` (Next.js, React, TypeScript). It talks only to the BFF routes under `/api`; the BFF holds the tokens in HttpOnly cookies and forwards an allow-listed set of calls to the services.

## Visual system
- Tokens: `web/src/styles/tokens.css` (colour, type, 4px spacing, radii, shadows, motion). Components use variables, never raw colours.
- White is the default surface. Lavender is an accent (balance card, active states, chart fill). Midnight navy is used for the sidebar, the bottom bar, chart tooltips and a few anchor buttons.
- Fonts: Inter (body) and Plus Jakarta Sans (headings) through `next/font`.
- Motion: 150-250 ms transitions, one page entrance (fade and 6 px lift), chart reveal. `prefers-reduced-motion` switches all of it off.

### Deliberate deviations from the brief's palette
- Secondary text is `#6c708e`, not `#777b98`. The brief's value is 4.1:1 on white; this one is 4.8:1 and passes WCAG AA for body text. `#a2a4b8` is used only for placeholders and decoration.
- Text in the negative and pending colours uses darker variants (`--negative-text`, `--pending`) for contrast. The brief's `#d65f68` and `#d5a34b` remain for dots, borders and fills.
- Ordinary money out is shown in the normal text colour with a minus sign. Red is reserved for failure.
- No dark mode (the brief defines a light palette).

## Layout
| Width | Navigation |
|---|---|
| >= 1100 px | fixed navy sidebar, 248 px |
| 768-1099 px | icon rail, 76 px |
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
