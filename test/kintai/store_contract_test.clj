(ns kintai.store-contract-test
  "MemStore ≡ DatomicStore for the kintai store.

  Every assertion runs against BOTH backends. Punches are the reason:
  they are personal data about a worker and, in several jurisdictions,
  evidence in a labour dispute. A backend that reordered them, dropped
  one, or let a correction overwrite the terminal's original reading
  would break the property the whole actor rests on."
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

(def backends {:mem store/mem-store :datomic store/datomic-store})

(defn- seeded [make]
  (doto (make)
    (store/register-worker! {:worker/id "w-1" :worker/jurisdiction [:jp]})))

(defn- each-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (seeded make)))))

(defn- each-empty-backend [f]
  (doseq [[label make] backends]
    (testing (str "backend " label) (f (make)))))

;; ---------------------------------------------------------------------------
;; Worker directory
;; ---------------------------------------------------------------------------

(deftest a-workers-jurisdiction-reads-back-as-a-path
  (each-backend
   (fn [st]
     (is (= [:jp] (store/worker-jurisdiction st "w-1")))
     (testing "an unknown worker has no jurisdiction, which is a hard hold"
       (is (nil? (store/worker-jurisdiction st "nobody")))))))

(deftest a-sub-national-jurisdiction-survives-the-round-trip
  (each-backend
   (fn [st]
     (store/register-worker! st {:worker/id "w-2" :worker/jurisdiction [:us :ca]})
     (is (= [:us :ca] (store/worker-jurisdiction st "w-2"))))))

;; ---------------------------------------------------------------------------
;; Punches — append-only, ordered, per worker
;; ---------------------------------------------------------------------------

(deftest punches-append-in-order-across-calls
  (each-backend
   (fn [st]
     (doseq [d (range 3)] (store/record-punches! st "w-1" (shift-punches d 9 18 12 13)))
     (let [ps (store/punches-of st "w-1")]
       (is (= 12 (count ps)))
       (is (= (sort (map :punch/at ps)) (map :punch/at ps)))
       (testing "and they pair identically, which is what the governor recomputes"
         (is (= 3 (count (:worked (shift/pair-punches ps)))))
         (is (= 24.0 (shift/worked-hours (:worked (shift/pair-punches ps)))))))))) 

(deftest punches-are-scoped-to-their-worker
  (each-backend
   (fn [st]
     (store/register-worker! st {:worker/id "w-2" :worker/jurisdiction [:jp]})
     (store/record-punches! st "w-1" (shift-punches 0 9 18 12 13))
     (store/record-punches! st "w-2" [(shift/punch "w-2" (at 0 9) :in)])
     (is (= 4 (count (store/punches-of st "w-1"))))
     (is (= 1 (count (store/punches-of st "w-2")))))))

(deftest a-correction-appends-and-never-overwrites
  (each-backend
   (fn [st]
     (store/record-punches! st "w-1" [(shift/punch "w-1" (at 0 9) :in)])
     (store/record-punches! st "w-1" [(shift/punch "w-1" (at 0 18) :out
                                                   :source :manual :note "terminal offline")])
     (let [ps (store/punches-of st "w-1")]
       (is (= 2 (count ps)))
       (testing "the terminal's original reading is still there"
         (is (= [:terminal :manual] (mapv :punch/source ps))))
       (is (empty? (:anomalies (shift/pair-punches ps))))))))

(deftest an-empty-store-answers-empty-not-nil
  (doseq [[label make] backends]
    (let [st (make)]
      (testing (str "backend " label)
        (is (empty? (store/punches-of st "w-1")))
        (is (empty? (store/roster st)))
        (is (empty? (store/ledger st)))
        (is (empty? (store/records-of st "w-1")))))))

;; ---------------------------------------------------------------------------
;; Roster and streams
;; ---------------------------------------------------------------------------

(deftest shifts-publish-and-are-scoped-by-person
  (each-backend
   (fn [st]
     (store/publish-shift! st (shift/shift "s-1" "w-1" :nurse (at 0 9) (at 0 18)))
     (store/publish-shift! st (shift/shift "s-2" "w-2" :nurse (at 0 9) (at 0 18)))
     (is (= 2 (count (store/roster st))))
     (is (= ["s-1"] (mapv :shift/id (store/shifts-of st "w-1")))))))

(deftest republishing-a-shift-id-replaces-it
  (each-backend
   (fn [st]
     (store/publish-shift! st (shift/shift "s-1" "w-1" :nurse (at 0 9) (at 0 18)))
     (store/publish-shift! st (shift/shift "s-1" "w-2" :nurse (at 0 9) (at 0 18)))
     (is (= 1 (count (store/roster st))))
     (is (= "w-2" (:shift/person (first (store/roster st))))))))

(deftest the-ledger-is-append-only-and-ordered
  (each-backend
   (fn [st]
     (doseq [n (range 4)] (store/append-ledger! st {:disposition :hold :n n}))
     (is (= [0 1 2 3] (mapv :n (store/ledger st)))))))

;; ---------------------------------------------------------------------------
;; The actor runs unchanged on either backend
;; ---------------------------------------------------------------------------

(deftest approval-behaves-identically-on-both-backends
  (let [run (fn [make]
              (let [st (seeded make)
                    _ (doseq [d (range 5)]
                        (store/record-punches! st "w-1" (shift-punches d 9 18 12 13)))
                    g (actor/build-graph {:store st})
                    r (actor/run-request! g {:worker-id "w-1" :op :approve-attendance
                                             :period-from (at 0 0) :period-to (at 7 0)
                                             :date-of date-of}
                                          {} "t-1")
                    _ (actor/approve! g "t-1")]
                {:status (:status r)
                 :hours (get-in r [:state :proposal :hours])
                 :coverage (get-in r [:state :verdict :law :worklaw/coverage])
                 :records (count (store/records-of st "w-1"))
                 :ledger (mapv :disposition (store/ledger st))}))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (is (= {:status :interrupted :hours 40.0 :coverage :full
            :records 1 :ledger [:commit]}
           (run store/datomic-store)))))

(deftest an-unlawful-week-is-held-identically-on-both-backends
  (let [run (fn [make]
              (let [st (seeded make)
                    _ (store/record-punches! st "w-1" (shift-punches 0 9 20 12 13))
                    g (actor/build-graph {:store st})
                    r (actor/run-request! g {:worker-id "w-1" :op :approve-attendance
                                             :period-from (at 0 0) :period-to (at 7 0)
                                             :date-of date-of}
                                          {} "t-1")]
                {:disposition (get-in r [:state :disposition])
                 :rules (mapv :rule (get-in r [:state :verdict :violations]))
                 :records (count (store/records-of st "w-1"))
                 :law-coverage (mapv :law-coverage (store/ledger st))}))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (is (= {:disposition :hold :rules [:unlawful-roster] :records 0 :law-coverage [:full]}
           (run store/datomic-store)))))

;; ---------------------------------------------------------------------------
;; Availability, leave and swaps
;;
;; Added in the iteration that gave kintai its leave and swap ops. These
;; store methods went in without contract coverage, which is exactly the
;; gap this file exists to close — the same suite has already found a
;; MemStore-only bug in each of the three actors.
;; ---------------------------------------------------------------------------

(deftest availability-round-trips-and-de-duplicates
  (each-backend
   (fn [st]
     (let [a (shift/availability "w-1" :nurse (at 0 0) (at 1 0))]
       (store/declare-availability! st a)
       (store/declare-availability! st a)
       (testing "a duplicated availability would double-count in available?"
         (is (= 1 (count (store/availabilities st)))))
       (is (= a (first (store/availabilities st))))))))

(deftest availabilities-for-different-windows-coexist
  (each-backend
   (fn [st]
     (store/declare-availability! st (shift/availability "w-1" :nurse (at 0 0) (at 1 0)))
     (store/declare-availability! st (shift/availability "w-1" :nurse (at 2 0) (at 3 0)))
     (is (= 2 (count (store/availabilities st)))))))

(deftest leave-round-trips-and-a-status-change-replaces-rather-than-forks
  (each-backend
   (fn [st]
     (let [l (shift/leave-request "l-1" "w-1" :annual (at 5 0) (at 6 0))]
       (store/record-leave! st l)
       (is (= [:requested] (mapv :leave/status (store/leave st))))
       (store/record-leave! st (assoc l :leave/status :approved))
       (testing "one leave record, now approved — not two records disagreeing"
         (is (= 1 (count (store/leave st))))
         (is (= [:approved] (mapv :leave/status (store/leave st))))))))) 

(deftest leave-reads-back-in-a-stable-order
  (each-backend
   (fn [st]
     (doseq [n ["l-3" "l-1" "l-2"]]
       (store/record-leave! st (shift/leave-request n "w-1" :annual (at 5 0) (at 6 0))))
     (is (= ["l-1" "l-2" "l-3"] (mapv :leave/id (store/leave st)))))))

(deftest a-swap-round-trips-with-its-acceptances
  (each-backend
   (fn [st]
     (let [sw (-> (shift/swap-proposal "sw-1" "s-1" "w-1" "w-2")
                  (shift/accept-swap "w-1")
                  (shift/accept-swap "w-2"))]
       (store/record-swap! st sw)
       (let [read-back (first (store/swaps st))]
         (testing "the acceptance SET survives the blob round-trip — a swap that
                   lost one acceptance would read as one-sided"
           (is (= #{"w-1" "w-2"} (:swap/accepted-by read-back)))
           (is (shift/swap-ready? read-back))))))))

(deftest re-recording-a-swap-id-replaces-it
  (each-backend
   (fn [st]
     (let [sw (shift/swap-proposal "sw-1" "s-1" "w-1" "w-2")]
       (store/record-swap! st sw)
       (store/record-swap! st (shift/accept-swap sw "w-1"))
       (is (= 1 (count (store/swaps st))))
       (is (= #{"w-1"} (:swap/accepted-by (first (store/swaps st)))))))))

(deftest an-empty-store-answers-empty-for-the-new-collections
  (each-empty-backend
   (fn [st]
     (is (empty? (store/availabilities st)))
     (is (empty? (store/leave st)))
     (is (empty? (store/swaps st))))))

(deftest the-swap-path-behaves-identically-on-both-backends
  (let [run (fn [make]
              (let [st (seeded make)
                    _ (store/register-worker! st {:worker/id "w-2" :worker/jurisdiction [:jp]})
                    _ (store/publish-shift! st (shift/shift "s-1" "w-1" :nurse (at 0 9) (at 0 17)))
                    _ (store/declare-availability!
                       st (shift/availability "w-2" :nurse (at 0 0) (at 1 0)))
                    g (actor/build-graph {:store st})
                    sw (-> (shift/swap-proposal "sw-1" "s-1" "w-1" "w-2")
                           (shift/accept-swap "w-1") (shift/accept-swap "w-2"))
                    r (actor/run-request! g {:worker-id "w-2" :op :accept-swap :swap sw
                                             :period-from (at 0 0) :period-to (at 7 0)
                                             :date-of date-of}
                                          {} "t-swap")]
                {:disposition (get-in r [:state :disposition])
                 :owner (:shift/person (first (store/roster st)))
                 :swaps (count (store/swaps st))}))]
    (is (= (run store/mem-store) (run store/datomic-store)))
    (is (= {:disposition :commit :owner "w-2" :swaps 1} (run store/datomic-store)))))
