(ns kintai.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shift :as shift]
            [kintai.store :as store]
            [kintai.actor :as actor]))

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

(defn- fresh-store
  ([] (fresh-store [:jp]))
  ([j] (doto (store/mem-store)
         (store/register-worker! {:worker/id "w-1" :worker/jurisdiction j}))))

(defn- clean-week! [st]
  (doseq [d (range 5)] (store/record-punches! st "w-1" (shift-punches d 9 18 12 13)))
  st)

(defn- approve-request []
  {:worker-id "w-1" :op :approve-attendance
   :period-from (at 0 0) :period-to (at 7 0) :date-of date-of})

(defn- disposition [r] (get-in r [:state :disposition]))
(defn- proposal [r] (get-in r [:state :proposal]))

;; ---------------------------------------------------------------------------
;; Ingestion
;; ---------------------------------------------------------------------------

(deftest punches-are-recorded-without-a-lawfulness-gate
  (testing "refusing to RECORD a punch would erase the evidence of a violation;
            the gate is on approval, not on the raw record"
    (let [st (fresh-store [:atlantis])
          g  (actor/build-graph {:store st})
          r  (actor/run-request! g {:worker-id "w-1" :op :record-punches
                                    :punches (shift-punches 0 9 23 12 13)}
                                 {} "t-record")]
      (is (= :commit (disposition r)))
      (is (= 4 (count (store/punches-of st "w-1")))))))

;; ---------------------------------------------------------------------------
;; Approval
;; ---------------------------------------------------------------------------

(deftest a-lawful-week-interrupts-for-payroll-signoff-then-commits
  (let [st (clean-week! (fresh-store))
        g  (actor/build-graph {:store st})
        interrupted (actor/run-request! g (approve-request) {} "t-approve")]
    (is (= :interrupted (:status interrupted)))
    (is (= 40.0 (:hours (proposal interrupted))))
    (is (= "punches pair cleanly" (:rationale (proposal interrupted))))
    (testing "nothing is committed while the thread waits"
      (is (empty? (store/records-of st "w-1"))))
    (let [resumed (actor/approve! g "t-approve")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "w-1"))))
      (is (= [:commit] (mapv :disposition (store/ledger st)))))))

(deftest the-approved-entries-carry-the-shared-ts-shape
  (let [st (clean-week! (fresh-store))
        g  (actor/build-graph {:store st})
        r  (actor/run-request! g (approve-request) {} "t-entries")
        entries (:entries (proposal r))]
    (is (= 5 (count entries)))
    (is (every? #(= 8.0 (:ts/hours %)) entries))
    (is (= #{:ts/worker :ts/date :ts/hours} (set (keys (first entries)))))))

(deftest an-unlawful-week-is-held-and-nothing-is-written
  (let [st (fresh-store [:jp])]
    (store/record-punches! st "w-1" (shift-punches 0 9 20 12 13))   ;; 10h day
    (let [g (actor/build-graph {:store st})
          r (actor/run-request! g (approve-request) {} "t-unlawful")]
      (is (= :hold (disposition r)))
      (is (some #(= :unlawful-roster (:rule %)) (get-in r [:state :verdict :violations])))
      (is (empty? (store/records-of st "w-1"))))))

(deftest an-unchecked-jurisdiction-is-held-not-escalated
  (testing "an ordinary week, held because no statute was available to check it"
    (let [st (clean-week! (fresh-store [:atlantis]))
          g  (actor/build-graph {:store st})
          r  (actor/run-request! g (approve-request) {} "t-unchecked")]
      (is (= :hold (disposition r)))
      (is (some #(= :unchecked-law (:rule %)) (get-in r [:state :verdict :violations])))
      (testing "the ledger records that nothing was checked, not merely that it was held"
        (is (= [:none] (mapv :law-coverage (store/ledger st))))))))

(deftest a-missing-clock-out-blocks-approval-of-the-whole-period
  (let [st (fresh-store)]
    (store/record-punches! st "w-1" (shift-punches 0 9 18 12 13))
    (store/record-punches! st "w-1" [(shift/punch "w-1" (at 1 9) :in)])
    (let [g (actor/build-graph {:store st})
          r (actor/run-request! g (approve-request) {} "t-anomaly")]
      (is (= :hold (disposition r)))
      (is (some #(= :unresolved-anomaly (:rule %)) (get-in r [:state :verdict :violations])))
      (testing "the advisor said so too, rather than proposing a plausible time"
        (is (= "punches contain unresolved anomalies: 1× missing-out"
               (:rationale (proposal r))))))))

;; ---------------------------------------------------------------------------
;; Corrections
;; ---------------------------------------------------------------------------

(deftest a-correction-is-appended-and-never-overwrites
  (let [st (fresh-store)]
    (store/record-punches! st "w-1" [(shift/punch "w-1" (at 0 9) :in)])
    (let [g (actor/build-graph {:store st})
          r (actor/run-request! g {:worker-id "w-1" :op :correct-punch
                                   :correction (shift/punch "w-1" (at 0 18) :out
                                                            :source :manual
                                                            :note "terminal offline")}
                                {} "t-fix")]
      (is (= :interrupted (:status r)))
      (actor/approve! g "t-fix")
      (testing "the original reading is still there alongside the correction"
        (is (= 2 (count (store/punches-of st "w-1"))))
        (is (= [:terminal :manual] (mapv :punch/source (store/punches-of st "w-1")))))
      (testing "and the period now pairs cleanly"
        (is (empty? (:anomalies (shift/pair-punches (store/punches-of st "w-1")))))))))

;; ---------------------------------------------------------------------------
;; The unconditional invariant
;; ---------------------------------------------------------------------------

(deftest the-advisor-cannot-commit-what-the-governor-refuses
  (doseq [[label st request]
          [["unregistered worker" (fresh-store)
            (assoc (approve-request) :worker-id "ghost")]
           ["no jurisdiction"
            (doto (store/mem-store) (store/register-worker! {:worker/id "w-1"}))
            (approve-request)]]]
    (clean-week! st)
    (let [g (actor/build-graph {:store st})
          r (actor/run-request! g request {} (str "t-" (hash label)))]
      (is (= :hold (disposition r)) label)
      (is (empty? (store/records-of st (:worker-id request))) label)
      (is (= [:hold] (mapv :disposition (store/ledger st))) label))))
