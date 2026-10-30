# Requirements specification

Status values used across the docs: **Implemented and tested**, **Implemented, not fully verified**,
**Partially implemented**, **Not implemented**. The live matrix is `docs/FEATURE_MATRIX.md`.

## 1. Scope and non-goals

PayNexa is a **sandbox** payment platform with synthetic data. It moves money between internal accounts and models
(but does not perform) external payouts through a simulated rail. It never touches real funds or banking systems.
Connecting a real rail requires a separate integration, security and compliance review (see `docs/PRODUCTION_GAP.md`).

## 2. Actors

| Actor | Can do |
|---|---|
| USER | register, log in, open own accounts, send internal transfers from own accounts, request payouts from own accounts, read own accounts/payments/activity |
| ADMIN | everything operational: sandbox funding, freeze/unfreeze accounts, approve/reject held payments, reverse payments, run reconciliation, requeue dead outbox events and dead letters |
| Service | machine identity used for service-to-service calls (only where such calls exist) |

## 3. Functional requirements

### Identity
- IR1 Register with email + password; password policy enforced; passwords stored only as salted adaptive hashes.
- IR2 Login returns a short-lived signed access token and a rotating refresh token.
- IR3 Refresh token reuse is detected and revokes the whole token family. Logout revokes the family.
- IR4 Repeated failed logins lock the account temporarily; unknown users take comparable time (no user enumeration by timing).
- IR5 Access tokens are asymmetric (RS256): only identity-service holds the private key.

### Accounts
- AR1 A user can open one account per currency. Accounts have status ACTIVE or FROZEN.
- AR2 Balances are stored in integer minor units. Customer accounts can never go negative (database-enforced).
- AR3 System accounts (funding source, payout clearing, external settled) exist per currency and are not user-visible.

### Payments (internal transfers)
- PR1 A transfer moves funds from a payer account owned by the caller to an existing, active payee account in the same currency.
- PR2 It requires an `Idempotency-Key`. Same key + same request returns the original result with no new effect.
  Same key + different request is rejected. Scope = (authenticated user, key). Retention = 7 days (configurable).
- PR3 Funds sufficiency, account status, currency and amount are validated; concurrent transfers cannot overspend a balance.
- PR4 A completed transfer is exactly one balanced journal entry (debit payer, credit payee) committed atomically with the payment state change and the outbox event.
- PR5 Explicit state machine with validated transitions (see `ARCHITECTURE.md`). Invalid transitions are rejected and never partially applied.
- PR6 Posted journal entries are immutable. Corrections are compensating entries (`REVERSAL`) that reference the original.

### Payouts (external rail, simulated)
- XR1 A payout debits the customer and credits a payout-clearing account immediately and atomically (money is "in flight", not "settled").
- XR2 Only a rail confirmation moves clearing funds to the external-settled account and marks the payout SETTLED.
- XR3 A rail rejection returns funds with a compensating entry and marks the payout REVERSED.
- XR4 Rail calls happen outside database transactions, with timeout, bounded retries with backoff and a circuit breaker, and use the payment id as the rail-side idempotency key.
- XR5 A payout the rail never confirms is surfaced by reconciliation; it is never silently assumed settled.

### Risk
- KR1 Configurable rules (max transaction, rolling-24h outflow limit, velocity, review threshold, first-time-payee threshold) per currency.
- KR2 Decisions are ALLOW, REVIEW (held for an admin) or BLOCK, and every decision is stored with the rules that fired.
- KR3 Rules run while the payer account is locked, so limits cannot be raced.
- KR4 This is rule-based control, not a trained fraud model. No accuracy claims are made.

### Events and read models
- ER1 State changes publish events to Kafka through a transactional outbox, after commit, at-least-once.
- ER2 Consumers are idempotent and tolerate out-of-order delivery (notification-service inbox).
- ER3 Consumer failures use bounded retries with backoff, then a dead-letter topic; dead letters are persisted and can be replayed by an admin.

### Reconciliation and audit
- RR1 Automated checks: every entry balanced; per-account stored balance equals ledger sum; per-currency ledger sums to zero;
  completed payments have exactly the expected entries; stuck payouts; stale or dead outbox events.
- RR2 Findings are stored, exposed to admins and exported as metrics. Reconciliation reports; it does not auto-repair money.
- RR3 Security-relevant and administrative actions are recorded in an append-only audit log with actor and correlation id.

## 4. Non-functional requirements

- NR1 Java 21, a supported Spring Boot release (4.1.x), PostgreSQL 17, Kafka.
- NR2 All schema changes are versioned Flyway migrations, tested against a real PostgreSQL.
- NR3 Rate limiting on authentication and payment endpoints (per-instance; see limitations).
- NR4 Structured JSON logs with correlation ids; no secrets, tokens, passwords or full request bodies in logs.
- NR5 Metrics (Prometheus format), liveness and readiness probes.
- NR6 Tests run without Docker on a developer machine and in CI.
- NR7 Database roles separate migration (DDL) rights from runtime (DML) rights; ledger tables are append-only for the runtime role.

## 5. Explicitly not provided

Real payment rails, KYC/AML screening, multi-region operation, card data (PCI scope), regulatory compliance, distributed (cross-instance) rate limiting,
access-token revocation before expiry, tracing backends. See `docs/PRODUCTION_GAP.md`.
