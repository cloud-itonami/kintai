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
                disposition, commit or hold."
  (:require [langchain.db :as d]
            [langchain-store.core :as ls]))

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
  ;; Keyed by :shift/id, not a vector. Two live shifts with one id would
  ;; count twice in `kotoba.shift/coverage`, which is what decides whether
  ;; a staffing gap exists — so republishing would silently close a gap
  ;; that is still open. Found by the MemStore ≡ DatomicStore contract
  ;; test.
  (roster [_] (vec (sort-by :shift/start (vals (:roster @a)))))
  (shifts-of [s worker-id] (filterv #(= worker-id (:shift/person %)) (roster s)))
  (records-of [_ worker-id] (filter #(= worker-id (:worker-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-worker! [s w] (swap! a assoc-in [:workers (:worker/id w)] w) s)
  (record-punches! [s worker-id punches]
    (swap! a update-in [:punches worker-id] (fnil into []) punches) s)
  (publish-shift! [s shift] (swap! a assoc-in [:roster (:shift/id shift)] shift) s)
  (commit-record! [s record] (swap! a update :records conj record) s)
  (append-ledger! [s fact] (swap! a update :ledger conj fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:workers {} :punches {} :roster {}
                                    :records [] :ledger []}
                                   seed)))))

(defn worker-jurisdiction
  "The worker's jurisdiction path, or nil. Nil is a hard hold in the
  governor, never a default — there is no sensible country to assume,
  and assuming one is how a roster gets certified against the wrong
  statute."
  [store worker-id]
  (:worker/jurisdiction (worker store worker-id)))

;; ---------------------------------------------------------------------------
;; DatomicStore (langchain.db)
;;
;; The same protocol over a Datomic-API-compatible EAV store, so the
;; backend is a swap and not a rewrite (cloud-itonami-isic-6511's
;; underwriting.store is the fleet's reference adopter). Pure `.cljc`: it
;; runs offline against langchain.db's in-process DataScript, and the SAME
;; record points at a real Datomic or a kotoba-server pod by swapping
;; langchain.db's `:db-api` (see langchain.kotoba-db).
;;
;; Punches are the reason durability matters here. They are personal data
;; about a worker and, in several jurisdictions, evidence in a labour
;; dispute. The stream is seq-keyed and append-only on every backend, and
;; a correction appends alongside the terminal's original reading rather
;; than replacing it — an attendance record that can be edited in place
;; is an attendance record that cannot settle a dispute.
;; ---------------------------------------------------------------------------

(def ^:private schema
  (ls/identity-schema [:worker/id :punch/key :shift/id :record/seq :ledger/seq]))

(defn- next-seq [conn seq-attr]
  (count (d/q [:find '?e :where ['?e seq-attr '_]] (d/db conn))))

(defrecord DatomicStore [conn]
  Store
  (worker [_ worker-id]
    (ls/dec* (d/q '[:find ?v . :in $ ?id
                    :where [?e :worker/id ?id] [?e :worker/edn ?v]] (d/db conn) worker-id)))
  (punches-of [_ worker-id]
    (->> (d/q '[:find ?s ?v :in $ ?w
                :where [?e :punch/worker ?w] [?e :punch/seq ?s] [?e :punch/edn ?v]]
              (d/db conn) worker-id)
         (sort-by first)
         (mapv (comp ls/dec* second))))
  (roster [_]
    (->> (d/q '[:find [?v ...] :where [?e :shift/id _] [?e :shift/edn ?v]] (d/db conn))
         (map ls/dec*)
         (sort-by :shift/start)
         vec))
  (shifts-of [s worker-id]
    (filterv #(= worker-id (:shift/person %)) (roster s)))
  (records-of [_ worker-id]
    (filterv #(= worker-id (:worker-id %)) (ls/read-stream conn :record/seq :record/edn)))
  (ledger [_] (ls/read-stream conn :ledger/seq :ledger/fact))
  (register-worker! [s w]
    (d/transact! conn [{:worker/id (:worker/id w) :worker/edn (ls/enc w)}]) s)
  (record-punches! [s worker-id punches]
    (let [n (count (punches-of s worker-id))]
      (d/transact! conn (vec (map-indexed
                              (fn [i p]
                                {:punch/key (str worker-id "#" (+ n i))
                                 :punch/worker worker-id :punch/seq (+ n i)
                                 :punch/edn (ls/enc p)})
                              punches))))
    s)
  (publish-shift! [s sh]
    (d/transact! conn [{:shift/id (:shift/id sh) :shift/edn (ls/enc sh)}]) s)
  (commit-record! [s record]
    (ls/append-blob! conn :record/seq :record/edn (next-seq conn :record/seq) record) s)
  (append-ledger! [s fact]
    (ls/append-blob! conn :ledger/seq :ledger/fact (next-seq conn :ledger/seq) fact) s))

(defn datomic-store
  "A DatomicStore over a fresh in-process langchain.db connection."
  []
  (->DatomicStore (d/create-conn schema)))
