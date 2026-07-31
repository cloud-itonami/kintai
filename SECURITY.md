# Security Policy

This repo holds a governed actor and no running service: no endpoint, no
scheduler, no credentials, no worker data. `MemStore` keeps everything in
process memory and persists nothing.

The data this actor would hold in production is personal data about workers
and, in several jurisdictions, evidence in a labour dispute. Two properties
are load-bearing:

- Punches are append-only. A correction is a separate escalated operation
  that appends alongside the original terminal reading; it never overwrites
  it. Attendance records that can be edited in place are attendance records
  that cannot settle a dispute.
- The statutory verdict in the ledger records its own coverage
  (`:law-coverage`), so a hold can be read back as "checked and failed" or
  "never checked" years later.

`kotoba-lang/worklaw` is a small cited rule set, not legal advice. Treating
its silence as compliance is precisely what the `:unchecked-law` hold exists
to prevent; do not add a configuration that bypasses it.

Report privately to root@junkawasaki.com.
