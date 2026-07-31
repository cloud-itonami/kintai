# Contributing

Two capability libraries hold the domain logic. `kotoba-lang/shift` turns
punches into worked spans; `kotoba-lang/worklaw` holds the statutory rules.
This repo holds the governed actor. Fixes to how hours are *derived* or what a
statute *says* belong upstream; fixes to what may be *committed* belong here.

Keep approval, rostering and corrections behind the governor. Nothing may write
to the store outside the `:commit` node, and a correction appends — it never
overwrites a terminal reading.

Two invariants are load-bearing here:

1. An unpaired punch is never completed (`kotoba-lang/shift`). A missing
   clock-out has no worked time, and no approval fills it in.
2. Silence is never compliance (`kotoba-lang/worklaw`). An unchecked or
   partially checked jurisdiction is a hold.

Adding a jurisdiction is an upstream change to `kotoba-lang/worklaw` and needs
`:law/as-of` plus a `:rule/citation` on every rule. Rules whose provision cannot
be cited do not go in.

Before opening a PR:

```bash
clojure -M:lint
clojure -M:test
```

`GOVERNANCE.md` lists the rules that are not up for discussion.
