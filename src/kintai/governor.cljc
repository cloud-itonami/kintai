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
  LEAVE / SWAP invariants (also HARD):
    7. :not-own-leave       — nobody requests leave on another's behalf.
    8. :self-approval       — nobody approves their own leave.
    9. :not-accepted-by-both / :receiver-unavailable — a swap needs both
                              named parties AND a receiver who declared
                              availability and is not on approved leave.
   10. :unlawful-swap       — the receiver's resulting schedule must be
                              lawful. This is the rule that makes
                              composing shift with worklaw worth doing:
                              two people may agree to swap, but they
                              cannot agree their way past a statute.
                              Consent between colleagues is not a source
                              of law.
   11. :unevaluable-swap    — the receiver's lawfulness could not be
                              checked at all, which is not the same as
                              lawful.
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
(def ^:private escalating-ops
  #{:approve-attendance :correct-punch
    ;; Granting leave changes who is on the board, and it is a decision
    ;; about someone's time. Never automatic.
    :approve-leave
    ;; Publishing a roster tells people when to work. `propose-roster`
    ;; already refuses to fill from anything but declared availability,
    ;; so what reaches here is admissible — but admissible is not the
    ;; same as decided, and the deciding is a person's.
    :generate-roster})

(defn- paired [store worker-id]
  (shift/pair-punches (store/punches-of store worker-id)))

(defn- period-filter [worked {:keys [period-from period-to]}]
  (if (and period-from period-to)
    (filter #(and (>= (:worked/start %) period-from) (<= (:worked/end %) period-to)) worked)
    worked))

(def ^:private caller-error-reasons
  "Why a statutory rule went unevaluated, split by whose problem it is.
  `:missing-period` / `:missing-calendar` mean the request did not carry
  what the check needs — a caller error, and a hard hold. Everything else
  (currently `:window-longer-than-period`) is inherent: a week cannot
  judge an annual cap and no approver can change that, so it escalates
  with the unevaluated list attached instead of blocking every weekly
  approval forever."
  #{:missing-period :missing-calendar})

(defn- caller-error-unevaluated [law]
  (filterv #(contains? caller-error-reasons (:unevaluated/reason %))
           (:worklaw/unevaluated law)))

(defn- inherent-unevaluated [law]
  (filterv #(not (contains? caller-error-reasons (:unevaluated/reason %)))
           (:worklaw/unevaluated law)))

(defn law-check
  "Re-run the statutory check for a proposal from the store's own punches.

  Returns nil when the worker has no jurisdiction, or when the proposal
  carries no period and day mapping to check against — a proposal that
  does not name a window is not one this can evaluate, and the caller
  turns nil into a hard hold for the ops where that matters rather than
  into a pass.

  The period is handed to `kotoba.worklaw` so its period-dependent rules
  (weekly rest) can be evaluated instead of skipped, and `:week-of` /
  `:month-of` are passed through when the request carries them so the
  long-window rules (36協定 の月・年上限) can be judged over a period long
  enough to hold them."
  [store request {:keys [period-from period-to date-of week-of month-of]}]
  (let [j (store/worker-jurisdiction store (:worker-id request))]
    (when (and j (fn? date-of) period-from period-to)
      (let [{:keys [worked]} (paired store (:worker-id request))]
        (worklaw/check (period-filter worked {:period-from period-from :period-to period-to})
                       j date-of
                       (cond-> {:period [period-from period-to]}
                         (fn? week-of)  (assoc :week-of week-of)
                         (fn? month-of) (assoc :month-of month-of)))))))

(defn- shift-of [store id]
  (first (filter #(= id (:shift/id %)) (store/roster store))))

(defn- swap-lawful?
  "Would applying this swap leave the RECEIVER with a lawful schedule?

  This is the rule that makes composing `kotoba.shift` with
  `kotoba.worklaw` worth doing: two people may agree to swap, but they
  cannot agree their way past a statute. A swap both parties want and
  the receiver's jurisdiction forbids is still forbidden — consent
  between colleagues is not a source of law.

  Returns `{:lawful? bool :law result}`, or `:lawful? true` with a nil
  law when the receiver's schedule cannot be evaluated (the caller turns
  that into its own hold — see :unevaluable-swap)."
  [store proposal]
  (let [sw (:swap proposal)
        s (shift-of store (:swap/shift sw))
        receiver (:swap/to sw)
        j (store/worker-jurisdiction store receiver)
        date-of (:date-of proposal)]
    (if-not (and s j (fn? date-of))
      {:lawful? true :law nil :evaluable? false}
      (let [existing (store/shifts-of store receiver)
            ;; NO :worked/break-ms. A roster records when a shift starts
            ;; and ends and nothing about whether anyone stopped for
            ;; lunch, so `kotoba.worklaw` treats break rules as
            ;; unevaluated here rather than violated. Stamping a 0 would
            ;; assert "no break was taken" from data that never said so,
            ;; and every full-day swap would read as unlawful.
            spans (mapv (fn [x] {:worked/person receiver
                                 :worked/start (:shift/start x)
                                 :worked/end (:shift/end x)
                                 :worked/ms (- (:shift/end x) (:shift/start x))})
                        (conj existing s))
            r (worklaw/check spans j date-of
                             {:period [(:period-from proposal) (:period-to proposal)]})]
        {:lawful? (empty? (worklaw/prohibitions r)) :law r :evaluable? true}))))

(defn- unlawful-proposed
  "Which proposed shifts would leave their person with an unlawful
  schedule. Same construction as `swap-lawful?`: spans built from PLANNED
  shifts carry no `:worked/break-ms`, so `kotoba.worklaw` reports break
  rules as unevaluated rather than violated — a roster records when a
  shift starts and ends, not whether anyone took lunch."
  [store proposal]
  (let [date-of (:date-of proposal)]
    (when (fn? date-of)
      (vec
       (for [sh (:proposed proposal)
             :let [person (:shift/person sh)
                   j (store/worker-jurisdiction store person)]
             :when j
             :let [spans (mapv (fn [x] {:worked/person person
                                        :worked/start (:shift/start x)
                                        :worked/end (:shift/end x)
                                        :worked/ms (- (:shift/end x) (:shift/start x))})
                               (conj (store/shifts-of store person) sh))
                   r (worklaw/check spans j date-of
                                    {:period [(:period-from proposal) (:period-to proposal)]})]
             :when (seq (worklaw/prohibitions r))]
         {:shift (:shift/id sh) :person person
          :rules (mapv #(get-in % [:violation/rule :rule/id]) (worklaw/prohibitions r))})))))

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

      (and approving? law (not= :full (:worklaw/coverage law)))
      (conj {:rule :unchecked-law
             :detail (str "労働法 coverage=" (name (:worklaw/coverage law))
                          "、未検査: " (pr-str (:worklaw/unchecked law))
                          "（規則が無いことは、適法であることではない）")})

      (and approving? law (seq (caller-error-unevaluated law)))
      (conj {:rule :unevaluable-law
             :detail (str "検査に必要な入力が request に無いため評価できない rule: "
                          (pr-str (caller-error-unevaluated law))
                          "（:period / :week-of / :month-of を渡すこと）")})

      ;; ---- leave ----
      (and (= :request-leave op) (not= (get-in proposal [:leave :leave/person]) worker-id))
      (conj {:rule :not-own-leave
             :detail "他人の休暇を代理で申請することはできない"})

      (and (= :approve-leave op)
           (= worker-id (:leave/person (first (filter #(= (:leave-id proposal) (:leave/id %))
                                                      (store/leave store))))))
      (conj {:rule :self-approval
             :detail "自分の休暇を自分で承認することはできない"})

      (and (= :approve-leave op)
           (nil? (first (filter #(= (:leave-id proposal) (:leave/id %)) (store/leave store)))))
      (conj {:rule :unknown-leave
             :detail (str "未登録の leave: " (:leave-id proposal))})

      ;; ---- swap ----
      (and (= :accept-swap op) (nil? (shift-of store (get-in proposal [:swap :swap/shift]))))
      (conj {:rule :no-such-shift
             :detail (str "roster に無い shift: " (get-in proposal [:swap :swap/shift]))})

      (and (= :accept-swap op) (not (shift/swap-ready? (:swap proposal))))
      (conj {:rule :not-accepted-by-both
             :detail "片方だけの承諾は swap ではなく、手順の増えた再割当"})

      (and (= :accept-swap op) (:swap proposal)
           (let [{:keys [applied? reason]}
                 (shift/apply-swap (store/roster store) (store/availabilities store)
                                   (store/leave store) (:swap proposal))]
             (and (not applied?) (= :receiver-unavailable reason))))
      (conj {:rule :receiver-unavailable
             :detail "受け手が availability を宣言していない／既に埋まっている／承認済み休暇中"})

      (and (= :accept-swap op) (:swap proposal)
           (not (:lawful? (swap-lawful? store proposal))))
      (conj {:rule :unlawful-swap
             :detail (str "受け手の schedule が法定違反になる: "
                          (pr-str (mapv #(get-in % [:violation/rule :rule/id])
                                        (worklaw/prohibitions (:law (swap-lawful? store proposal)))))
                          "（当事者の合意は法の源泉ではない）")})

      (and (= :accept-swap op) (:swap proposal)
           (not (:evaluable? (swap-lawful? store proposal))))
      (conj {:rule :unevaluable-swap
             :detail "受け手の jurisdiction / :date-of / period が無く、適法性を判定できない"})

      ;; ---- a generated roster may not be unlawful for anyone on it ----
      ;;
      ;; Same rule as :unlawful-swap and for the same reason: a roster
      ;; that a manager would approve is still not one a statute permits.
      (and (= :generate-roster op)
           (seq (unlawful-proposed store proposal)))
      (conj {:rule :unlawful-roster-proposal
             :detail (str "提案 shift が受け手を法定違反にする: "
                          (pr-str (unlawful-proposed store proposal)))})

      (and approving? law (seq (worklaw/prohibitions law)))
      (conj {:rule :unlawful-roster
             :detail (str (count (worklaw/prohibitions law)) " 件の法定違反: "
                          (pr-str (mapv #(get-in % [:violation/rule :rule/id])
                                        (worklaw/prohibitions law))))}))))

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
        priced?   (boolean (and law (seq (worklaw/priced law))))
        ;; A rule nobody could judge over this period is not a violation
        ;; and not a pass. It goes to the human alongside the approval,
        ;; so "the annual cap was not checked" is something they saw
        ;; rather than something the system decided for them.
        ;;
        ;; Scoped to :approve-attendance on purpose. That op's whole
        ;; point is CERTIFYING a period, so an uncheckable rule is a
        ;; caveat on the certificate. A swap between two colleagues does
        ;; not turn on whether the requester's annual cap was judgeable
        ;; from a week of punches — for that op the statutory question is
        ;; answered separately, against the RECEIVER's resulting
        ;; schedule, by `swap-lawful?`. Letting this escalate every op
        ;; would send every JP swap to a manager for a reason that has
        ;; nothing to do with the swap.
        unjudged  (when (and law (= :approve-attendance (:op proposal)))
                    (inherent-unevaluated law))]
    {:ok?        (and (not hard?) (not low?) (not risky-op?) (not priced?) (empty? unjudged))
     :violations hard
     :law        law
     :unevaluated unjudged
     :confidence conf
     :hard?      hard?
     :escalate?  (and (not hard?) (or low? risky-op? priced? (seq unjudged)))}))
