# Governance

Maintained by the cloud-itonami org. The actor pattern (advisor-LLM ⊣
independent governor, append-only audit ledger, ADR-2607011000) is
non-negotiable; external-send actions require human approval.

Two rules here are stronger than the fleet default and are not maintainer
discretion.

**The unchecked-law hold has no escalation path.** A period whose jurisdiction
has no rule set, or only a partial one, is HELD — not escalated. There is no
human whose sign-off substitutes for a statute nobody has encoded. A pull request
adding an approval route around `:unchecked-law`, or a "assume compliant when
unknown" flag, will be closed regardless of the use case it cites. The way to
make a jurisdiction approvable is to add its rules to `kotoba-lang/worklaw` with
citations.

**Prohibitions and premiums stay separate.** `:violation/kind :overtime-due`
escalates because lawful overtime costs money and someone should decide about it.
Everything else — a daily cap, a required break, a required rest period — blocks.
Collapsing the two in either direction is wrong: blocking overtime makes the
system unusable, and escalating a missed rest period makes it complicit.

Adding a jurisdiction to `kotoba-lang/worklaw` requires `:law/as-of` on the
jurisdiction and `:rule/citation` on every rule; tests enforce both. Rules whose
provision cannot be cited do not go in.
