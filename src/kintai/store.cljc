(ns kintai.store
  "SSoT for the kintai (勤怠) attendance actor. Store is a protocol
  injected into the `kintai.actor` StateGraph — `MemStore` is the
  default, deterministic, zero-dep backend (itonami actor pattern,
  ADR-2607011000 / CLAUDE.md Actors section). Modeled on
  cloud-itonami-isco-4313's payroll.store; the attendance records use
  `kotoba.shift`'s shapes verbatim and the statutory checks come from
  `kotoba.worklaw` — this actor CONSUMES both, it reinvents neither
  punch pairing nor labour law.

  Domain:

    worker    — a registered worker. `:worker/jurisdiction` is a path
                like [:jp] or [:us :ca] and is REQUIRED: without it
                there is nothing to check attendance against, and this
                actor refuses to treat 'we do not know the law here' as
                'the law is satisfied'.
    punch     — a `kotoba.shift/punch`, append-only. The raw record of
                what the terminal saw. Corrections do not overwrite;
                they are their own escalated operation.
    shift     — a `kotoba.shift/shift`, the planned roster.
    record    — a committed operating record (approved attendance,
                published roster, punch correction) — written ONLY via
                commit-record!.
    ledger    — append-only audit trail of every proposal/verdict/
                disposition, commit or hold.")

(defprotocol Store
  (worker [s worker-id])
  (punches-of [s worker-id])
  (roster [s])
  (shifts-of [s worker-id])
  (records-of [s worker-id])
  (ledger [s])
  (register-worker! [s w])
  (record-punches! [s worker-id punches])
  (publish-shift! [s shift])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (worker [_ worker-id] (get-in @a [:workers worker-id]))
  (punches-of [_ worker-id] (get-in @a [:punches worker-id] []))
  (roster [_] (:roster @a))
  (shifts-of [_ worker-id] (filter #(= worker-id (:shift/person %)) (:roster @a)))
  (records-of [_ worker-id] (filter #(= worker-id (:worker-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-worker! [s w] (swap! a assoc-in [:workers (:worker/id w)] w) s)
  (record-punches! [s worker-id punches]
    (swap! a update-in [:punches worker-id] (fnil into []) punches) s)
  (publish-shift! [s shift] (swap! a update :roster conj shift) s)
  (commit-record! [s record] (swap! a update :records conj record) s)
  (append-ledger! [s fact] (swap! a update :ledger conj fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:workers {} :punches {} :roster []
                                    :records [] :ledger []}
                                   seed)))))

(defn worker-jurisdiction
  "The worker's jurisdiction path, or nil. Nil is a hard hold in the
  governor, never a default — there is no sensible country to assume,
  and assuming one is how a roster gets certified against the wrong
  statute."
  [store worker-id]
  (:worker/jurisdiction (worker store worker-id)))
