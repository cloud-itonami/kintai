(ns kintai.advisor
  "KintaiAdvisor — proposes an attendance operation: record punches,
  approve a period's attendance, publish a roster, correct a punch,
  request or approve leave, propose or accept a shift swap.
  Swappable: `mock-advisor` (deterministic, default) or `llm-advisor`.
  Either way the advisor ONLY produces a PROPOSAL; `kintai.governor`
  independently re-pairs the punches via `kotoba.shift` and re-runs the
  statutory check via `kotoba.worklaw`. Modeled on
  cloud-itonami-isco-4313's payroll.advisor.

  What the model is allowed to do here is narrower than it looks. It may
  summarise, explain an anomaly, and recommend. It may not state hours —
  those are re-derived — and it may not state that a period is lawful.
  Compliance is not an opinion the advisor holds; it is a computation the
  governor runs, and an advisor claiming a clean week for a jurisdiction
  with no rule set changes nothing about the hold that follows.

  A proposal is a map:
    {:op :record-punches|:approve-attendance|:publish-shift|:correct-punch
     :effect :propose
     :period-from n :period-to n :date-of fn
     :hours n            ; re-derived by the governor
     :punches [...]      ; :record-punches
     :shift {...}        ; :publish-shift
     :correction {...}   ; :correct-punch
     :leave {...}        ; :request-leave
     :leave-id str       ; :approve-leave
     :swap {...}         ; :propose-swap / :accept-swap
     :confidence 0.0-1.0
     :rationale str}"
  (:require [kotoba.shift :as shift]
            [kintai.store :as store]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- period-worked [store worker-id {:keys [period-from period-to]}]
  (let [{:keys [worked anomalies]} (shift/pair-punches (store/punches-of store worker-id))
        in-period (if (and period-from period-to)
                    (filter #(and (>= (:worked/start %) period-from)
                                  (<= (:worked/end %) period-to))
                            worked)
                    worked)]
    {:worked in-period :anomalies anomalies}))

(defn- infer
  [store {:keys [op worker-id] :as request}]
  (let [base {:op op
              :effect :propose
              :confidence 0.9
              :period-from (:period-from request)
              :period-to (:period-to request)
              :date-of (:date-of request)
              :rationale (str "proposed " (name op) " for worker " worker-id)}]
    (case op
      :approve-attendance
      (let [{:keys [worked anomalies]} (period-worked store worker-id request)]
        (assoc base
               :hours (shift/worked-hours worked)
               :entries (shift/->timesheet-entries worked (:date-of request))
               :rationale (let [a (shift/describe-anomalies {:anomalies anomalies})]
                            (if (seq a)
                              (str "punches contain unresolved anomalies: " a)
                              "punches pair cleanly"))))

      :record-punches
      (assoc base :punches (:punches request))

      :publish-shift
      (assoc base :shift (:shift request))

      :correct-punch
      (assoc base :correction (:correction request) :confidence 0.5)

      :request-leave
      (assoc base :leave (:leave request))

      :approve-leave
      (assoc base :leave-id (:leave-id request))

      :propose-swap
      (assoc base :swap (:swap request))

      :accept-swap
      ;; The advisor carries the proposal through with the acceptance
      ;; already applied by the caller. It does NOT decide whether the
      ;; swap is admissible — `kotoba.shift/apply-swap` and the governor's
      ;; statutory re-check both run independently of anything said here.
      (assoc base :swap (:swap request))

      base)))

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are an attendance advisor. Given a period's paired shifts and any
   pairing anomalies, explain what happened and recommend an action.
   Do NOT state total hours — they are recomputed from the punches. Do
   NOT state that a period is lawful or compliant — that is checked
   separately against the worker's jurisdiction. Never propose a value
   for a missing clock-out.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  "Wraps a `langchain.model/ChatModel`; decoupled from any concrete model
  beyond the protocol. Whatever the model says about hours or lawfulness
  is discarded — the span totals are re-derived here and the statutory
  verdict belongs to the governor."
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ store request]
      (let [worker-id (:worker-id request)
            {:keys [worked anomalies]} (period-worked store worker-id request)
            msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "shifts: " (pr-str worked)
                                             "\nanomalies: " (pr-str anomalies))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (merge (parse-proposal (:content resp))
               {:op          (:op request)
                :period-from (:period-from request)
                :period-to   (:period-to request)
                :date-of     (:date-of request)
                :hours       (shift/worked-hours worked)})))))
