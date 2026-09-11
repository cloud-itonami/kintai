(ns kintai.conformance-test
  "Every verdict this actor can emit is a well-formed verdict.

  `kotoba-lang/governor` measured 376 hand-copied governors in this fleet and
  found one that had drifted into reporting a HARD violation as escalatable,
  so an approval queue would show a permanently-refused certification as
  awaiting sign-off. The drift was invisible through the actor's own graph —
  the router tests `:hard?` first.

  kintai matters more than most here, because it has THREE escalation
  triggers rather than one (a risky op, a priced statutory violation, and a
  rule nobody could judge) and carries two extra keys (`:law`,
  `:unevaluated`). Collapsing those three into the shared library's single
  `:escalating-op?` is exactly the kind of step where a hand-rolled verdict
  drifts, so it is pinned here across every disposition."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shift :as shift]
            [kintai.store :as store]
            [kintai.governor :as governor]
            [governor.core :as gov]))

(def ^:private hour 3600000)
(def ^:private day (* 24 hour))
(def ^:private t0 1767225600000)
(defn- at [d h] (+ t0 (* d day) (long (* h hour))))
(defn- date-of [ms] (quot (- ms t0) day))

(defn- shift-punches [d from to break-from break-to]
  [(shift/punch "w-1" (at d from) :in)
   (shift/punch "w-1" (at d break-from) :break-start)
   (shift/punch "w-1" (at d break-to) :break-end)
   (shift/punch "w-1" (at d to) :out)])

(defn- store-for
  "A worker in `jurisdiction`, with `hours-per-day` on each of five days."
  [jurisdiction hours-per-day]
  (let [st (store/mem-store)]
    (store/register-worker! st {:worker/id "w-1" :worker/name "Rin"
                                :worker/jurisdiction jurisdiction})
    (doseq [d (range 5)]
      (store/record-punches! st "w-1" (shift-punches d 9 (+ 9 hours-per-day 1) 12 13)))
    st))

(defn- approve
  "An :approve-attendance proposal. NOTE: in :jp this op always escalates on
  a one-week period — the 36協定 monthly and annual caps report
  :window-longer-than-period, and kintai treats a rule nobody could judge as
  a caveat that goes to the human rather than a pass. So this shape is used
  for the HARD cases and for the unjudged-escalation case, never as the
  clean one."
  [& {:keys [hours confidence]}]
  (cond-> {:op :approve-attendance :effect :propose
           :period-from (at 0 0) :period-to (at 7 0)
           :date-of date-of}
    true (assoc :confidence confidence)
    hours (assoc :hours hours)))

(defn- record [& {:keys [confidence]}]
  (cond-> {:op :record-punches :effect :propose :date-of date-of}
    true (assoc :confidence confidence)))

(def ^:private cases
  [{:name :clean
    :store #(store-for [:jp] 8) :request {:worker-id "w-1"}
    :proposal (record :confidence 0.9)}

   {:name :hard/no-worker
    :store #(store-for [:jp] 8) :request {:worker-id "nobody"}
    :proposal (approve :confidence 0.9)}

   {:name :hard/no-actuation
    :store #(store-for [:jp] 8) :request {:worker-id "w-1"}
    :proposal (assoc (approve :confidence 0.9) :effect :direct-write)}

   {:name :hard/hours-mismatch
    :store #(store-for [:jp] 8) :request {:worker-id "w-1"}
    :proposal (approve :hours 999 :confidence 0.9)}

   ;; an unchecked jurisdiction is a HOLD, not a pass — kintai's founding rule
   {:name :hard/unchecked-law
    :store #(store-for [:atlantis] 8) :request {:worker-id "w-1"}
    :proposal (approve :confidence 0.9)}

   ;; the third escalation trigger, which is kintai's own and is the reason
   ;; collapsing three triggers into the library's one needed pinning: a
   ;; one-week period cannot judge the 36協定 monthly or annual caps, and an
   ;; unjudged rule is a caveat for the human, not a pass.
   {:name :escalate/unjudged-statutory-rule
    :store #(store-for [:jp] 8) :request {:worker-id "w-1"}
    :proposal (approve :confidence 0.9)}

   {:name :escalate/low-confidence
    :store #(store-for [:jp] 8) :request {:worker-id "w-1"}
    :proposal (record :confidence 0.3)}

   ;; a proposal that does not say how confident it is has not said it is
   ;; confident — the absent key must read as 0.0, never as trustworthy.
   {:name :escalate/no-confidence-key
    :store #(store-for [:jp] 8) :request {:worker-id "w-1"}
    :proposal (dissoc (record) :confidence)}])

(defn- verdict-for [{:keys [store request proposal]}]
  (governor/check request {} proposal (store)))

(deftest every-verdict-is-well-formed
  (doseq [{:keys [name] :as c} cases]
    (testing (str name)
      (let [v (verdict-for c)]
        (is (empty? (gov/conformance-failures v))
            (str "非適合: " (pr-str (gov/conformance-failures v))))))))

(deftest the-drift-that-happened-elsewhere-cannot-happen-here
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:hard? v)]
    (testing (str name)
      (is (not (:escalate? v))
          "a statute is not something an approver can wave through")
      (is (not (:ok? v)))
      (is (seq (:violations v)) "a hold must say what it refused"))))

(deftest the-case-set-actually-covers-the-three-dispositions
  ;; evidence floor: a conformance suite whose cases all landed in one
  ;; disposition would pass while checking almost nothing.
  (let [vs (map verdict-for cases)]
    (is (>= (count (filter :ok? vs)) 1) "no clean case")
    (is (>= (count (filter :hard? vs)) 4) "HARD rules under-covered")
    (is (>= (count (filter :escalate? vs)) 3) "escalation under-covered")
    (testing "all three escalation triggers are exercised, not just one"
      (is (= #{:unjudged-statutory-rule :low-confidence}
             (into #{} (keep :escalation-reason) vs))))))

(deftest escalation-carries-a-reason
  (doseq [{:keys [name] :as c} cases
          :let [v (verdict-for c)]
          :when (:escalate? v)]
    (testing (str name)
      (is (some? (:escalation-reason v))))))

(deftest the-extra-keys-survive-the-shared-verdict
  (testing "adopting gov/verdict must not drop :law or :unevaluated —
            they are the governor's OWN statutory result, and a console
            that lost them would show an approval with no statute attached"
    (doseq [{:keys [name] :as c} cases]
      (testing (str name)
        (is (contains? (verdict-for c) :law))
        (is (contains? (verdict-for c) :unevaluated))))))

