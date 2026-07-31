# kintai 勤怠

**Attendance with a governor that will not certify a week it could not check.**
The cloud-itonami fleet's answer to the workforce-management category (UKG /
Dayforce / ADP / Workday / SAP SuccessFactors / Rippling / Deputy / QuickBooks
Time), built on the itonami actor pattern (advisor-LLM ⊣ independent governor,
append-only audit ledger, ADR-2607011000).

```text
terminal ──▶ :record-punches ──┐
                               ├──▶ KintaiAdvisor ──▶ KintaiGovernor ──▶ commit | approve | HOLD
roster   ──▶ :publish-shift  ──┤              (re-pairs punches, re-runs the statute)
period   ──▶ :approve-attendance                       │
                                                       └──▶ append-only ledger
```

Two capability libraries, kept apart on purpose:
[`kotoba-lang/shift`](https://github.com/kotoba-lang/shift) knows how punches
become worked spans; [`kotoba-lang/worklaw`](https://github.com/kotoba-lang/worklaw)
knows what a statute permits. Separating them is what lets the same statutory
rules apply to timesheets that never came from a punch clock.

## The rule this actor exists for

**An unchecked jurisdiction is a HOLD, not a pass.**

```clojure
;; a perfectly ordinary 40-hour week, worker in [:atlantis]
(actor/run-request! g approve-request {} "t-1")
;; => disposition :hold, violation :unchecked-law, ledger :law-coverage :none
```

Not an escalation — a hold. Everywhere else in this fleet a hard hold means "a
human has to look at this". Here it means **there is no human whose signature
substitutes for a statute nobody has encoded**. The same applies when
`kotoba.worklaw` covers the country but not the sub-jurisdiction (`[:us :ca]`
→ `:partial`), and when a period-dependent rule could not be evaluated at all.

## What the governor refuses

| | HARD hold |
|---|---|
| `:no-worker` / `:no-actuation` | unregistered worker; `:effect` other than `:propose` |
| `:hours-mismatch` | the hours in the proposal ≠ re-pairing the stored punches |
| `:unresolved-anomaly` | approving a period whose punches contain a pairing anomaly |
| `:no-jurisdiction` | the worker has no `:worker/jurisdiction`, or the request names no period |
| `:unchecked-law` | statutory coverage is not `:full`, or a rule went unevaluated |
| `:unlawful-roster` | a statutory **prohibition** — a cap, a required break, a required rest |

| | escalate — human sign-off |
|---|---|
| overtime due | lawful and expensive. That is a person's call, not a block and not a wave-through |
| `:approve-attendance` | the hours become payroll input |
| `:correct-punch` | editing the raw record |
| low confidence | below 0.6 |

`:unresolved-anomaly` is the one people push back on. A missing clock-out is a
question for a person; approving the period around it is how it quietly becomes
a number nobody chose. So the whole period is held until the punch is corrected —
and the correction is its own escalated operation.

**Recording punches is never gated on lawfulness.** Refusing to record a punch
because the resulting week would be illegal would erase the evidence of the very
violation. The gate is on approval; the raw record is always accepted.

## What the model is allowed to do

Explain. Not count, and not certify.

The advisor may summarise a period and describe an anomaly. It may not state
total hours — those are re-derived from the punches — and it may not state that a
period is lawful. Compliance is not an opinion the advisor holds; it is a
computation the governor runs, and an advisor claiming a clean week for a
jurisdiction with no rule set changes nothing about the hold that follows.

## Leave and swaps

Six ops, not four. `kotoba-lang/shift` grew leave accrual, swaps and roster
generation; these are the ones kintai now governs.

```clojure
(actor/run-request! g {:worker-id "w-1" :op :request-leave
                       :leave (shift/leave-request "l-1" "w-1" :annual from to)} {} "t-1")
(actor/run-request! g {:worker-id "mgr" :op :approve-leave :leave-id "l-1"} {} "t-2") ;; interrupts
(actor/approve! g "t-2")

(actor/run-request! g {:worker-id "w-1" :op :propose-swap :swap proposal} {} "t-3")
(actor/run-request! g {:worker-id "w-2" :op :accept-swap
                       :swap (-> proposal (shift/accept-swap "w-1") (shift/accept-swap "w-2"))
                       :period-from from :period-to to :date-of date-of} {} "t-4")
```

Nobody requests leave on another's behalf (`:not-own-leave`) and nobody approves
their own (`:self-approval`). Approving **always** interrupts, and the status
transition to `:approved` happens only after a human resumes the thread — which
is also the moment the worker comes off the board.

A mutually accepted swap with a free receiver **commits without a manager**.
That is deliberate: requiring sign-off for a swap two colleagues arranged is the
paternalism the design avoids. What it cannot do is override the receiver's
availability or their approved leave.

### Two people cannot agree their way past a statute

This is the rule that makes composing `shift` with `worklaw` worth doing. Before
a swap applies, the governor re-runs the statute against the **receiver's
resulting schedule**:

```clojure
;; w-2 already works 09:00–20:00 that day. Taking w-1's 8h shift on top
;; breaks 労基法 32条2項. Both of them want it.
;; => :hold, :unlawful-swap — and NOT an escalation.
```

Consent between colleagues is not a source of law, so there is no approval route
around it. And a swap whose lawfulness could not be checked at all
(`:unevaluable-swap`) is held too — that is not the same as lawful.

**Planned shifts carry no break data.** The spans the swap check builds omit
`:worked/break-ms` entirely rather than setting it to 0, so `worklaw` reports
break rules as `:missing-break-data` instead of violated. A roster says when a
shift starts and ends and nothing about lunch.

### Roster generation proposes; approval publishes

`:generate-roster` **always escalates**. `kotoba.shift/propose-roster` already
refuses to fill from anything but declared availability, so what reaches the
governor is admissible — but admissible is not decided, and publishing a roster
tells people when to work.

```clojure
(actor/run-request! g {:worker-id "mgr" :op :generate-roster
                       :demand (shift/demand :nurse from to 3)
                       :candidates ["n-1" "n-2" "n-3"]
                       :period-from from :period-to to :date-of date-of} {} "t-1")
;; => :interrupted, {:proposed [...] :still-short 1}
(actor/approve! g "t-1")   ;; publishes exactly what was proposed
```

A shortfall does not become a shift by being approved. And the statute runs over
the proposal for the same reason it runs over a swap:

> `n-1` works 03:00–09:00, which does **not** overlap a 09:00–17:00 demand — so
> `available?` says free, and it is right about the clash. What it cannot see is
> that 6h + 8h breaks 労基法 32条2項. `:unlawful-roster-proposal`, held.

Two layers, and the cheap one goes first: an *overlapping* shift is excluded by
availability before the statute is ever consulted.

## Operations

```clojure
(require '[kotoba.shift :as shift] '[kintai.store :as store] '[kintai.actor :as actor])

(def st (store/mem-store))
(store/register-worker! st {:worker/id "w-1" :worker/jurisdiction [:jp]})

(def g (actor/build-graph {:store st}))

(actor/run-request! g {:worker-id "w-1" :op :record-punches
                       :punches [(shift/punch "w-1" t :in) ...]} {} "t-1")

(actor/run-request! g {:worker-id "w-1" :op :approve-attendance
                       :period-from from :period-to to
                       :date-of (fn [ms] ...)}      ;; the caller owns the timezone
                    {} "t-2")                       ;; interrupts
(actor/approve! g "t-2")

(actor/run-request! g {:worker-id "w-1" :op :correct-punch
                       :correction (shift/punch "w-1" t :out :source :manual
                                                :note "terminal offline")} {} "t-3")
```

`:date-of` is not a convenience. Which side of midnight a night shift falls on
decides whether a daily cap was broken, so the timezone is the caller's explicit
decision rather than a default this actor picks.

**Corrections are appended, never overwritten.** The original terminal reading
stays in the punch stream, so "who changed this and when" remains answerable.

## Maturity

| | |
|---|---|
| Role | actor (advisor ⊣ governor ⊣ ledger) |
| Capability libraries | `kotoba-lang/shift`, `kotoba-lang/worklaw` (sibling paths) |
| Tests | 75 tests, 236 assertions, all green |
| Jurisdictions | whatever `kotoba-lang/worklaw` ships — `[:jp]` (incl. 36協定) `[:us]` `[:us :ca]` `[:eu]` `[:eu :fr]` `[:eu :de]` |
| Store | `MemStore` + `DatomicStore` (langchain.db), proved interchangeable by a contract test |
| Deployment | Cloudflare Pages Functions — `POST /api/punch`, CACAO + allow-list gated |
| Not covered | payroll calculation (that is `kotoba-lang/labor`); leave accrual reporting, shift-swap marketplaces, absence balances surfaced to workers |

## Store backends

`MemStore` and `DatomicStore` (`langchain.db`) implement the same protocol and
pass the same contract test. Punches are why: they are personal data about a
worker and, in several jurisdictions, evidence in a labour dispute, so a backend
that reordered them, dropped one, or let a correction overwrite the terminal's
original reading would break the property the whole actor rests on.

The contract test caught a real divergence — `MemStore` was `conj`ing shifts, so
republishing a shift id left two live shifts and `kotoba.shift/coverage` counted
the same person twice, silently closing a staffing gap that was still open.

## HTTP surface

One route, permanently: `POST /api/punch`. A wall terminal has to reach the
actor; the other three ops end in a human's judgement, so `:approve-attendance`,
`:correct-punch` and `:publish-shift` have no HTTP representation at all.

**Recording is deliberately not gated on lawfulness**, and the edge inherits
that: refusing to record a punch because the week is becoming illegal would
erase the evidence of the very violation. A successful ingest returns the
pairing anomalies the stored stream now has, so a terminal that dropped a
clock-out learns about it at the moment it matters rather than at approval time.

Two gates: CACAO signature and temporal window (`cacao.edge.verify`, shared, not
reimplemented), then an allow-list mapping **DID → worker id**. **An absent
allow-list serves 503, never an open endpoint** — an open punch endpoint is an
open write path into the record that decides someone's pay.

A second refusal sits in front of it: with `KINTAI_STORE` unset the endpoint serves
**503 "no store configured"** without verifying anything. An empty in-process
store fails the governor's registration check, so the caller would get
`409 :no-worker` and go looking at their own registration while the actual fault
is a deployment with no store. `KINTAI_STORE=ephemeral` enables a non-persisting
smoke test, and every success response then carries `"ephemeral": true`. A
durable backend is not wired yet.

The deploy artifact is **built and exercised**, not merely configured:

```bash
npm install && npx shadow-cljs release edge-api   # -> functions/edge/
```

The `:esm` release runs `:advanced` optimization, which `cljs.main -c` does not,
so it is the first thing that could rename `KINTAI_STORE`,
`KINTAI_CALLER_ALLOWLIST` or `authorization` out from under the handler. They
survive (`:infer-externs :auto`), the module loads under Node, and invoking
`punchOnRequestPost` against a mock Cloudflare context returns 503 unset, 503 on a typo'd
store mode, and 401 for a bad CACAO. Running it for real is also what surfaced a
multi-line string literal leaking source indentation into the JSON `hint`.

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later.
