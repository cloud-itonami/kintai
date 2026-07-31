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
| Tests | 45 tests, 147 assertions, all green |
| Jurisdictions | whatever `kotoba-lang/worklaw` ships — `[:jp]` (incl. 36協定) `[:us]` `[:us :ca]` `[:eu]` `[:eu :fr]` `[:eu :de]` |
| Store | `MemStore` + `DatomicStore` (langchain.db), proved interchangeable by a contract test |
| Deployment | Cloudflare Pages Functions — `POST /api/punch`, CACAO + allow-list gated |
| Not covered | payroll calculation (that is `kotoba-lang/labor`); leave accrual, swaps and roster generation exist in `kotoba-lang/shift` but are not yet governed ops here |

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

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

AGPL-3.0-or-later.
