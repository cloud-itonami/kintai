# CLAUDE.md — cloud-itonami/kintai 勤怠

Attendance and statutory working-time actor. itonami pattern: advisor ⊣
independent governor ⊣ append-only ledger. Punch mechanics are
`kotoba-lang/shift`; statute is `kotoba-lang/worklaw`; this repo is the governed
shell.

## The rule this actor exists for

**An unchecked jurisdiction is a HOLD, not a pass** — and not an escalation
either. There is no human whose signature substitutes for a statute nobody has
encoded. Do not add an approval route around `:unchecked-law`, and do not add an
"assume compliant when unknown" flag. The way to make a jurisdiction approvable
is to add its rules to `kotoba-lang/worklaw`, with citations.

## Two distinctions to preserve

**Prohibitions vs premiums.** `:overtime-due` escalates because lawful overtime
costs money and someone should decide. A daily cap, a required break, a required
rest period **block**. Collapsing them either way is wrong: blocking overtime
makes the system unusable, escalating a missed rest period makes it complicit.

**Inherent vs caller-error unevaluated.** `:window-longer-than-period` is
inherent — seven days cannot judge an annual cap — so it escalates with the list
attached. `:missing-period` / `:missing-calendar` mean the request did not carry
what the check needs, and stay hard holds (`:unevaluable-law`).

## Leave and swaps

Nobody requests leave for another (`:not-own-leave`); nobody approves their own
(`:self-approval`); approving always escalates.

A mutually accepted swap with a free receiver **commits without a manager** — do
not add sign-off to it. Requiring a manager for a swap two colleagues arranged is
the paternalism the design avoids.

But: **two people cannot agree their way past a statute.** `:unlawful-swap` is a
HARD hold with no approval route, checked against the RECEIVER's resulting
schedule. Consent between colleagues is not a source of law. `:unevaluable-swap`
is held too — not checkable is not the same as lawful.

The spans that check builds omit `:worked/break-ms` rather than setting it to 0.
A roster records when a shift starts and ends, not whether anyone took lunch; see
`kotoba-lang/worklaw`'s "nil is not zero".

## Recording is never gated on lawfulness

Refusing to record a punch because the week is becoming illegal would erase the
evidence of the very violation. The gate is on **approval**. This holds at the
edge too.

## Corrections append, never overwrite

The terminal's original reading stays in the punch stream. An attendance record
that can be edited in place is one that cannot settle a dispute.

## What the model may do

Explain. Not count, and not certify. Hours are re-derived from the punches;
compliance is a computation the governor runs, not an opinion the advisor holds.

## Store and edge

`MemStore` ≡ `DatomicStore` — same protocol, same contract test; write both
sides of any store change. The HTTP surface is **one route**: `POST /api/punch`.
Approval, correction and rostering have no HTTP representation. An absent
allow-list serves **503**. An unset `KINTAI_STORE` serves **503** too — refusing beats
returning `:no-worker` and blaming the caller for a storeless deployment.

## Test

    clojure -M:test && clojure -M:lint
