(ns kintai.governor
  "KintaiGovernor — the independent safety/traceability layer for the
  kintai (勤怠) attendance actor (itonami actor pattern, ADR-2607011000 /
  CLAUDE.md Actors section). Modeled on cloud-itonami-isco-4313's
  payroll.governor, with the attendance-specific twist that the governor
  RE-PAIRS the stored punches via `kotoba.shift` and re-runs the
  statutory check via `kotoba.worklaw` — the advisor's hours are never
  trusted and its compliance opinion is never accepted.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. worker provenance    — the request's worker must be registered.
    2. no-actuation         — proposal :effect must be :propose.
    3. hours integrity      — proposed hours must EQUAL what re-pairing
                              the stored punches produces.
    4. unresolved anomaly   — attendance for a period whose punches
                              contain a pairing anomaly may not be
                              approved. A missing clock-out is a
                              question for a person, and approving it
                              is how it becomes a number nobody chose.
    5. unchecked law        — `kotoba.worklaw` coverage must be :full.
                              Partial or absent coverage is a HOLD, not
                              a pass. This is the rule the actor exists
                              for: 'we have no rules for this
                              jurisdiction' must never leave the system
                              looking like 'this roster is lawful'.
    6. unlawful roster      — any statutory violation that is a
                              prohibition (a cap, a required break, a
                              required rest) blocks approval outright.
  ESCALATION invariants (:escalate? true, human sign-off):
    7. overtime due         — a `:overtime-due` finding is lawful and
                              costs money, so it goes to a person rather
                              than being blocked or waved through.
    8. :op :approve-attendance — the hours become payroll input.
    9. :op :correct-punch      — editing the raw record.
   10. low confidence (< `confidence-floor`)."
  (:require [kotoba.shift :as shift]
            [kotoba.worklaw :as worklaw]
            [kintai.store :as store]))

(def confidence-floor 0.6)
(def ^:private escalating-ops #{:approve-attendance :correct-punch})

(defn- paired [store worker-id]
  (shift/pair-punches (store/punches-of store worker-id)))

(defn- period-filter [worked {:keys [period-from period-to]}]
  (if (and period-from period-to)
    (filter #(and (>= (:worked/start %) period-from) (<= (:worked/end %) period-to)) worked)
    worked))

(defn- prohibitions
  "Statutory findings that forbid, as opposed to findings that price. An
  overtime premium is lawful and expensive; a missed rest period is not
  lawful at any price."
  [result]
  (remove #(= :overtime-due (:violation/kind %)) (:worklaw/violations result)))

(defn- overtime-due? [result]
  (boolean (some #(= :overtime-due (:violation/kind %)) (:worklaw/violations result))))

(defn law-check
  "Re-run the statutory check for a proposal from the store's own punches.

  Returns nil when the worker has no jurisdiction, or when the proposal
  carries no period and day mapping to check against — a proposal that
  does not name a window is not one this can evaluate, and the caller
  turns nil into a hard hold for the ops where that matters rather than
  into a pass.

  The period is handed to `kotoba.worklaw` so its period-dependent rules
  (weekly rest) can be evaluated instead of skipped."
  [store request {:keys [period-from period-to date-of]}]
  (let [j (store/worker-jurisdiction store (:worker-id request))]
    (when (and j (fn? date-of) period-from period-to)
      (let [{:keys [worked]} (paired store (:worker-id request))]
        (worklaw/check (period-filter worked {:period-from period-from :period-to period-to})
                       j date-of
                       {:period [period-from period-to]})))))

(defn- hard-violations [request proposal store law]
  (let [{:keys [op effect hours]} proposal
        worker-id (:worker-id request)
        worker-record (store/worker store worker-id)
        {:keys [worked anomalies]} (paired store worker-id)
        in-period (period-filter worked proposal)
        recomputed (shift/worked-hours in-period)
        approving? (= :approve-attendance op)]
    (cond-> []
      (nil? worker-record)
      (conj {:rule :no-worker :detail "未登録 worker"})

      (not= :propose effect)
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（直接書込禁止）"})

      (and (number? hours) (not= hours recomputed))
      (conj {:rule :hours-mismatch
             :detail (str "hours " hours " ≠ 打刻を再ペアリングした " recomputed)})

      (and approving? (seq anomalies))
      (conj {:rule :unresolved-anomaly
             :detail (str "打刻に未解決の異常: " (shift/describe-anomalies {:anomalies anomalies})
                          "（打刻漏れは人に訊く事柄であって承認で埋める事柄ではない）")})

      (and approving? (nil? law))
      (conj {:rule :no-jurisdiction
             :detail (if (store/worker-jurisdiction store worker-id)
                       "承認対象の期間（:period-from/:period-to/:date-of）が無く、法定チェックを実行できない"
                       "worker に :worker/jurisdiction が無い。既定の国は無い")})

      (and approving? law (or (not= :full (:worklaw/coverage law))
                              (seq (:worklaw/unevaluated law))))
      (conj {:rule :unchecked-law
             :detail (str "労働法 coverage=" (name (:worklaw/coverage law))
                          "、未検査: " (pr-str (:worklaw/unchecked law))
                          "、未評価 rule: " (pr-str (:worklaw/unevaluated law))
                          "（規則が無いこと・評価しなかったことは、適法であることではない）")})

      (and approving? law (seq (prohibitions law)))
      (conj {:rule :unlawful-roster
             :detail (str (count (prohibitions law)) " 件の法定違反: "
                          (pr-str (mapv #(get-in % [:violation/rule :rule/id])
                                        (prohibitions law))))}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
  implementing `kintai.store/Store`. Pure — never mutates the store.
  Returns `{:ok? bool :violations [...] :law {...} :confidence n
  :hard? bool :escalate? bool}`; `:law` is the governor's OWN statutory
  result, not the advisor's, and is nil when the worker has no
  jurisdiction."
  [request _context proposal store]
  (let [law       (law-check store request proposal)
        hard      (hard-violations request proposal store law)
        hard?     (boolean (seq hard))
        conf      (or (:confidence proposal) 0.0)
        low?      (< conf confidence-floor)
        risky-op? (contains? escalating-ops (:op proposal))
        ot?       (boolean (and law (overtime-due? law)))]
    {:ok?        (and (not hard?) (not low?) (not risky-op?) (not ot?))
     :violations hard
     :law        law
     :confidence conf
     :hard?      hard?
     :escalate?  (and (not hard?) (or low? risky-op? ot?))}))
