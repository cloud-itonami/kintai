(ns kintai.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shift :as shift]
            [kintai.store :as store]
            [kintai.governor :as governor]))

(def ^:private hour 3600000)
(def ^:private day (* 24 hour))
(def ^:private t0 1767225600000) ;; 2026-01-01T00:00:00Z

(defn- at [d h] (+ t0 (* d day) (long (* h hour))))
(defn- date-of [ms] (quot (- ms t0) day))

(defn- shift-punches
  "in/break/out for one day."
  ([d from to] [(shift/punch "w-1" (at d from) :in) (shift/punch "w-1" (at d to) :out)])
  ([d from to break-from break-to]
   [(shift/punch "w-1" (at d from) :in)
    (shift/punch "w-1" (at d break-from) :break-start)
    (shift/punch "w-1" (at d break-to) :break-end)
    (shift/punch "w-1" (at d to) :out)]))

(defn- fresh-store
  ([] (fresh-store [:jp]))
  ([jurisdiction]
   (let [st (store/mem-store)]
     (store/register-worker! st {:worker/id "w-1" :worker/name "Rin"
                                 :worker/jurisdiction jurisdiction})
     st)))

(defn- clean-week!
  "Five 8-hour days with a one-hour break — lawful everywhere shipped."
  [st]
  (doseq [d (range 5)]
    (store/record-punches! st "w-1" (shift-punches d 9 18 12 13)))
  st)

(defn- approve-proposal [& {:keys [hours confidence]}]
  (cond-> {:op :approve-attendance :effect :propose
           :period-from (at 0 0) :period-to (at 7 0)
           :date-of date-of :confidence (or confidence 0.9)}
    hours (assoc :hours hours)))

(defn- check [request proposal store] (governor/check request {} proposal store))

;; ---------------------------------------------------------------------------
;; Baseline
;; ---------------------------------------------------------------------------

(deftest a-lawful-week-escalates-for-payroll-signoff-and-nothing-else
  (let [st (clean-week! (fresh-store))
        v (check {:worker-id "w-1"} (approve-proposal :hours 40.0) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))
    (is (= :full (get-in v [:law :worklaw/coverage])))
    (is (empty? (get-in v [:law :worklaw/violations])))))

(deftest hard-on-unregistered-worker
  (let [v (check {:worker-id "nobody"} (approve-proposal) (clean-week! (fresh-store)))]
    (is (:hard? v))
    (is (some #(= :no-worker (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (clean-week! (fresh-store))
        v (check {:worker-id "w-1"} (assoc (approve-proposal) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

;; ---------------------------------------------------------------------------
;; Hours integrity
;; ---------------------------------------------------------------------------

(deftest hard-when-proposed-hours-do-not-match-the-punches
  (let [st (clean-week! (fresh-store))
        v (check {:worker-id "w-1"} (approve-proposal :hours 60.0) st)]
    (is (:hard? v))
    (is (some #(= :hours-mismatch (:rule %)) (:violations v)))))

(deftest a-missing-clock-out-cannot-be-approved-away
  (let [st (fresh-store)]
    (store/record-punches! st "w-1" (shift-punches 0 9 18 12 13))
    (store/record-punches! st "w-1" [(shift/punch "w-1" (at 1 9) :in)])   ;; never clocked out
    (let [v (check {:worker-id "w-1"} (approve-proposal) st)]
      (is (:hard? v))
      (is (some #(= :unresolved-anomaly (:rule %)) (:violations v))))))

;; ---------------------------------------------------------------------------
;; The invariant — unchecked law is not compliance
;; ---------------------------------------------------------------------------

(deftest hard-when-the-jurisdiction-has-no-rule-set
  (let [st (clean-week! (fresh-store [:atlantis]))
        v (check {:worker-id "w-1"} (approve-proposal :hours 40.0) st)]
    (testing "a perfectly ordinary week, held because nothing checked it"
      (is (:hard? v))
      (is (some #(= :unchecked-law (:rule %)) (:violations v)))
      (is (= :none (get-in v [:law :worklaw/coverage])))
      (is (empty? (get-in v [:law :worklaw/violations]))))
    (testing "and it is a HOLD, not an escalation — no signature substitutes for a statute"
      (is (not (:escalate? v))))))

(deftest hard-when-only-the-national-level-has-rules
  (testing "Texas has no rule set, so a US/TX worker is checked federally only"
    (let [st (clean-week! (fresh-store [:us :tx]))
          v (check {:worker-id "w-1"} (approve-proposal :hours 40.0) st)]
      (is (:hard? v))
      (is (some #(= :unchecked-law (:rule %)) (:violations v)))
      (is (= :partial (get-in v [:law :worklaw/coverage])))
      (is (= [[:us :tx]] (get-in v [:law :worklaw/unchecked]))))))

(deftest a-covered-sub-jurisdiction-is-checked-at-both-levels
  (testing "California DOES have rules, so [:us :ca] is full coverage — and its
            daily overtime, which federal law lacks, is priced not blocked"
    (let [st (fresh-store [:us :ca])]
      (doseq [d (range 5)] (store/record-punches! st "w-1" (shift-punches d 8 21 12 13)))
      (let [v (check {:worker-id "w-1"} (approve-proposal) st)]
        (is (= :full (get-in v [:law :worklaw/coverage])))
        (is (not (:hard? v)))
        (is (:escalate? v))))))

(deftest an-annual-cap-nobody-could-judge-escalates-with-the-list
  (testing "seven days cannot judge 36協定. That is inherent, so it goes to the
            human alongside the approval rather than blocking every week"
    (let [st (clean-week! (fresh-store [:jp]))
          v (check {:worker-id "w-1"} (approve-proposal :hours 40.0) st)]
      (is (not (:hard? v)))
      (is (:escalate? v))
      (is (seq (:unevaluated v)))
      (is (every? #(= :window-longer-than-period (:unevaluated/reason %)) (:unevaluated v))))))

(deftest a-request-missing-the-calendar-is-a-hard-hold
  (testing "a month-long period with no :week-of/:month-of is a caller error —
            distinguished from the inherent case above"
    (let [st (fresh-store [:jp])]
      (doseq [wk (range 4) d (range 5)]
        (store/record-punches! st "w-1" (shift-punches (+ (* wk 7) d) 9 18 12 13)))
      (let [v (check {:worker-id "w-1"}
                     {:op :approve-attendance :effect :propose
                      :period-from (at 0 0) :period-to (at 30 0)
                      :date-of date-of :confidence 0.9}
                     st)]
        (is (:hard? v))
        (is (some #(= :unevaluable-law (:rule %)) (:violations v)))))))

(deftest hard-when-the-worker-has-no-jurisdiction-at-all
  (let [st (store/mem-store)]
    (store/register-worker! st {:worker/id "w-1"})
    (doseq [d (range 5)] (store/record-punches! st "w-1" (shift-punches d 9 18 12 13)))
    (let [v (check {:worker-id "w-1"} (approve-proposal :hours 40.0) st)]
      (is (:hard? v))
      (is (some #(= :no-jurisdiction (:rule %)) (:violations v)))
      (is (nil? (:law v))))))

;; ---------------------------------------------------------------------------
;; Prohibitions vs premiums
;; ---------------------------------------------------------------------------

(deftest hard-on-a-statutory-prohibition
  (testing "a 10-hour JP day breaks the daily cap"
    (let [st (fresh-store [:jp])]
      (store/record-punches! st "w-1" (shift-punches 0 9 20 12 13))  ;; 10h worked
      (let [v (check {:worker-id "w-1"} (approve-proposal) st)]
        (is (:hard? v))
        (is (some #(= :unlawful-roster (:rule %)) (:violations v)))))))

(deftest hard-on-a-missing-statutory-break
  (let [st (fresh-store [:jp])]
    (store/record-punches! st "w-1" (shift-punches 0 9 16))   ;; 7h, no break
    (let [v (check {:worker-id "w-1"} (approve-proposal) st)]
      (is (:hard? v))
      (is (some #(= :unlawful-roster (:rule %)) (:violations v))))))

(deftest overtime-escalates-rather-than-blocking
  (testing "45 US hours is lawful and costs a premium — that is a person's call"
    (let [st (fresh-store [:us])]
      (doseq [d (range 5)] (store/record-punches! st "w-1" (shift-punches d 9 19 12 13)))
      (let [v (check {:worker-id "w-1"} (approve-proposal :hours 45.0) st)]
        (is (not (:hard? v)))
        (is (:escalate? v))
        (is (= :full (get-in v [:law :worklaw/coverage])))
        (is (seq (get-in v [:law :worklaw/violations])))))))

;; ---------------------------------------------------------------------------
;; Non-approving ops
;; ---------------------------------------------------------------------------

(deftest recording-punches-does-not-require-a-lawful-week
  (testing "the law check gates APPROVAL, not the raw record — refusing to
            record a punch would erase evidence of the very violation"
    (let [st (fresh-store [:atlantis])
          v (check {:worker-id "w-1"}
                   {:op :record-punches :effect :propose :confidence 0.9
                    :punches (shift-punches 0 9 23)}
                   st)]
      (is (:ok? v)))))

(deftest correcting-a-punch-always-escalates
  (let [st (clean-week! (fresh-store))
        v (check {:worker-id "w-1"}
                 {:op :correct-punch :effect :propose :confidence 0.9
                  :correction (shift/punch "w-1" (at 1 18) :out)}
                 st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))

(deftest escalate-on-low-confidence
  (let [st (clean-week! (fresh-store))
        v (check {:worker-id "w-1"}
                 {:op :publish-shift :effect :propose :confidence 0.2
                  :shift (shift/shift "s-1" "w-1" :nurse (at 6 9) (at 6 18))}
                 st)]
    (is (:escalate? v))
    (is (not (:hard? v)))))
