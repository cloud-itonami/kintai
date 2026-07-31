(ns kintai.leave-swap-test
  "Leave and shift-swap as governed ops.

  The rule worth having here is the one that makes composing
  `kotoba.shift` with `kotoba.worklaw` worth doing: two people may agree
  to swap, but they cannot agree their way past a statute."
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shift :as shift]
            [kintai.store :as store]
            [kintai.actor :as actor]
            [kintai.governor :as governor]))

(def ^:private hour 3600000)
(def ^:private day (* 24 hour))
(def ^:private t0 1767225600000)
(defn- at [d h] (+ t0 (* d day) (long (* h hour))))
(defn- date-of [ms] (quot (- ms t0) day))

(defn- fresh []
  (doto (store/mem-store)
    (store/register-worker! {:worker/id "w-1" :worker/jurisdiction [:jp]})
    (store/register-worker! {:worker/id "w-2" :worker/jurisdiction [:jp]})
    (store/register-worker! {:worker/id "mgr"  :worker/jurisdiction [:jp]})))

(defn- check [st request proposal] (governor/check request {} proposal st))
(defn- disposition [r] (get-in r [:state :disposition]))
(defn- rules-of [r] (mapv :rule (get-in r [:state :verdict :violations])))

;; ---------------------------------------------------------------------------
;; Leave
;; ---------------------------------------------------------------------------

(deftest a-worker-requests-their-own-leave-and-it-commits-as-requested
  (let [st (fresh)
        g (actor/build-graph {:store st})
        l (shift/leave-request "l-1" "w-1" :annual (at 5 0) (at 6 0))
        r (actor/run-request! g {:worker-id "w-1" :op :request-leave :leave l} {} "t-1")]
    (is (= :commit (disposition r)))
    (is (= [:requested] (mapv :leave/status (store/leave st))))
    (testing "a request is not an approval, so it does not take them off the board"
      (is (shift/available? [(shift/availability "w-1" :nurse (at 5 0) (at 6 0))]
                            [] (store/leave st) "w-1" :nurse [(at 5 0) (at 6 0)])))))

(deftest nobody-requests-leave-on-another-workers-behalf
  (let [st (fresh)
        l (shift/leave-request "l-1" "w-2" :annual (at 5 0) (at 6 0))
        v (check st {:worker-id "w-1"}
                 {:op :request-leave :effect :propose :leave l :confidence 0.9})]
    (is (:hard? v))
    (is (some #(= :not-own-leave (:rule %)) (:violations v)))))

(deftest nobody-approves-their-own-leave
  (let [st (fresh)
        _ (store/record-leave! st (shift/leave-request "l-1" "w-1" :annual (at 5 0) (at 6 0)))
        v (check st {:worker-id "w-1"}
                 {:op :approve-leave :effect :propose :leave-id "l-1" :confidence 0.9})]
    (is (:hard? v))
    (is (some #(= :self-approval (:rule %)) (:violations v)))))

(deftest approving-an-unknown-leave-is-held
  (let [v (check (fresh) {:worker-id "mgr"}
                 {:op :approve-leave :effect :propose :leave-id "nope" :confidence 0.9})]
    (is (:hard? v))
    (is (some #(= :unknown-leave (:rule %)) (:violations v)))))

(deftest approving-leave-always-waits-for-a-human-then-takes-them-off-the-board
  (let [st (fresh)
        _ (store/record-leave! st (shift/leave-request "l-1" "w-1" :annual (at 5 0) (at 6 0)))
        g (actor/build-graph {:store st})
        r (actor/run-request! g {:worker-id "mgr" :op :approve-leave :leave-id "l-1"} {} "t-1")]
    (is (= :interrupted (:status r)))
    (testing "still :requested while the thread waits"
      (is (= [:requested] (mapv :leave/status (store/leave st)))))
    (actor/approve! g "t-1")
    (is (= [:approved] (mapv :leave/status (store/leave st))))
    (testing "and now they are unavailable"
      (is (not (shift/available? [(shift/availability "w-1" :nurse (at 5 0) (at 6 0))]
                                 [] (store/leave st) "w-1" :nurse [(at 5 0) (at 6 0)]))))))

;; ---------------------------------------------------------------------------
;; Swap
;; ---------------------------------------------------------------------------

(defn- with-shift [st]
  (doto st
    (store/publish-shift! (shift/shift "s-1" "w-1" :nurse (at 0 9) (at 0 17)))
    (store/declare-availability! (shift/availability "w-2" :nurse (at 0 0) (at 1 0)))))

(defn- swap-proposal [& accepters]
  (reduce shift/accept-swap (shift/swap-proposal "sw-1" "s-1" "w-1" "w-2") accepters))

(defn- swap-req [sw]
  {:op :accept-swap :effect :propose :swap sw :confidence 0.9
   :period-from (at 0 0) :period-to (at 7 0) :date-of date-of})

(deftest one-sided-acceptance-is-held
  (let [st (with-shift (fresh))
        v (check st {:worker-id "w-2"} (swap-req (swap-proposal "w-1")))]
    (is (:hard? v))
    (is (some #(= :not-accepted-by-both (:rule %)) (:violations v)))))

(deftest a-mutually-accepted-swap-commits-and-moves-the-shift
  (let [st (with-shift (fresh))
        g (actor/build-graph {:store st})
        r (actor/run-request! g (merge {:worker-id "w-2"} (swap-req (swap-proposal "w-1" "w-2")))
                              {} "t-1")]
    (testing "both parties agreed and the receiver is free — no manager needed"
      (is (= :commit (disposition r))))
    (is (= "w-2" (:shift/person (first (store/roster st)))))
    (is (= 1 (count (store/swaps st))))))

(deftest a-receiver-who-declared-no-availability-is-held
  (let [st (doto (fresh)
             (store/publish-shift! (shift/shift "s-1" "w-1" :nurse (at 0 9) (at 0 17))))
        v (check st {:worker-id "w-2"} (swap-req (swap-proposal "w-1" "w-2")))]
    (is (:hard? v))
    (is (some #(= :receiver-unavailable (:rule %)) (:violations v)))))

(deftest a-receiver-on-approved-leave-is-held
  (let [st (with-shift (fresh))
        _ (store/record-leave! st (assoc (shift/leave-request "l-1" "w-2" :annual (at 0 0) (at 1 0))
                                         :leave/status :approved))
        v (check st {:worker-id "w-2"} (swap-req (swap-proposal "w-1" "w-2")))]
    (testing "a swap is exactly where someone ends up working through their own leave"
      (is (:hard? v))
      (is (some #(= :receiver-unavailable (:rule %)) (:violations v))))))

(deftest a-swap-for-a-shift-that-does-not-exist-is-held
  (let [st (fresh)
        v (check st {:worker-id "w-2"} (swap-req (swap-proposal "w-1" "w-2")))]
    (is (:hard? v))
    (is (some #(= :no-such-shift (:rule %)) (:violations v)))))

;; ---------------------------------------------------------------------------
;; The rule this composition exists for
;; ---------------------------------------------------------------------------

(deftest two-people-cannot-agree-their-way-past-a-statute
  (testing "w-2 already works 09:00–20:00 that day (11h). Taking w-1's 8h shift
            on top would put them past 労基法 32条2項's daily cap — both of them
            want it, and it is still forbidden"
    (let [st (doto (fresh)
               (store/publish-shift! (shift/shift "s-1" "w-1" :nurse (at 0 9) (at 0 17)))
               (store/publish-shift! (shift/shift "s-0" "w-2" :nurse (at 1 9) (at 1 20)))
               (store/declare-availability! (shift/availability "w-2" :nurse (at 0 0) (at 2 0))))
          v (check st {:worker-id "w-2"} (swap-req (swap-proposal "w-1" "w-2")))]
      (is (:hard? v))
      (is (some #(= :unlawful-swap (:rule %)) (:violations v)))
      (testing "and it is a HOLD — there is no approval route around a statute"
        (is (not (:escalate? v)))))))

(deftest the-actor-writes-nothing-when-a-swap-is-unlawful
  (let [st (doto (fresh)
             (store/publish-shift! (shift/shift "s-1" "w-1" :nurse (at 0 9) (at 0 17)))
             (store/publish-shift! (shift/shift "s-0" "w-2" :nurse (at 1 9) (at 1 20)))
             (store/declare-availability! (shift/availability "w-2" :nurse (at 0 0) (at 2 0))))
        g (actor/build-graph {:store st})
        r (actor/run-request! g (merge {:worker-id "w-2"} (swap-req (swap-proposal "w-1" "w-2")))
                              {} "t-1")]
    (is (= :hold (disposition r)))
    (is (some #{:unlawful-swap} (rules-of r)))
    (testing "the shift still belongs to whoever had it"
      (is (= "w-1" (:shift/person (first (filter #(= "s-1" (:shift/id %)) (store/roster st)))))))
    (is (empty? (store/swaps st)))))

(deftest a-swap-whose-lawfulness-cannot-be-checked-is-held-not-passed
  (testing "no :date-of, so the statute could not be run at all — which is not
            the same as lawful"
    (let [st (with-shift (fresh))
          v (check st {:worker-id "w-2"}
                   (dissoc (swap-req (swap-proposal "w-1" "w-2")) :date-of))]
      (is (:hard? v))
      (is (some #(= :unevaluable-swap (:rule %)) (:violations v))))))

;; ---------------------------------------------------------------------------
;; Roster generation — a proposal is not a roster
;; ---------------------------------------------------------------------------

(defn- gen-req [candidates]
  {:worker-id "mgr" :op :generate-roster
   :demand (shift/demand :nurse (at 0 9) (at 0 17) 3)
   :candidates candidates
   :period-from (at 0 0) :period-to (at 7 0) :date-of date-of})

(defn- free [st & people]
  (doseq [p people]
    (store/register-worker! st {:worker/id p :worker/jurisdiction [:jp]})
    (store/declare-availability! st (shift/availability p :nurse (at 0 0) (at 1 0))))
  st)

(deftest generating-a-roster-always-waits-for-a-human
  (testing "propose-roster already refuses to fill from anything but declared
            availability, so what reaches the governor is admissible — but
            admissible is not decided, and publishing tells people when to work"
    (let [st (free (fresh) "n-1" "n-2" "n-3")
          g (actor/build-graph {:store st})
          r (actor/run-request! g (gen-req ["n-1" "n-2" "n-3"]) {} "t-1")]
      (is (= :interrupted (:status r)))
      (is (= 3 (count (get-in r [:state :proposal :proposed]))))
      (testing "nothing is on the roster while the thread waits"
        (is (empty? (store/roster st))))
      (actor/approve! g "t-1")
      (is (= 3 (count (store/roster st)))))))

(deftest a-shortfall-does-not-become-a-shift-by-being-approved
  (let [st (free (fresh) "n-1")
        g (actor/build-graph {:store st})
        r (actor/run-request! g (gen-req ["n-1" "n-2" "n-3"]) {} "t-1")]
    (testing "only n-1 declared availability"
      (is (= 1 (count (get-in r [:state :proposal :proposed]))))
      (is (= 2 (get-in r [:state :proposal :still-short]))))
    (actor/approve! g "t-1")
    (testing "approval publishes what was proposed and invents nothing"
      (is (= 1 (count (store/roster st))))
      (is (= ["n-1"] (mapv :shift/person (store/roster st)))))))

(deftest nobody-on-approved-leave-is-proposed
  (let [st (free (fresh) "n-1" "n-2")
        _ (store/record-leave! st (assoc (shift/leave-request "l-1" "n-1" :annual (at 0 0) (at 1 0))
                                         :leave/status :approved))
        g (actor/build-graph {:store st})
        r (actor/run-request! g (gen-req ["n-1" "n-2"]) {} "t-1")]
    (is (= ["n-2"] (mapv :shift/person (get-in r [:state :proposal :proposed]))))))

(deftest availability-cannot-see-what-the-statute-sees
  (testing "n-1 works 03:00–09:00, which does NOT overlap the 09:00–17:00
            demand — so `available?` says free, and it is right about the
            clash. What it cannot see is that 6h + 8h breaks 労基法 32条2項's
            daily cap. That gap is the whole reason the governor re-runs the
            statute over the proposal rather than trusting the generator."
    (let [st (free (fresh) "n-1")
          _ (store/publish-shift! st (shift/shift "s-0" "n-1" :nurse (at 0 3) (at 0 9)))
          g (actor/build-graph {:store st})
          r (actor/run-request! g (gen-req ["n-1"]) {} "t-1")]
      (testing "the generator does propose them — no overlap, availability declared"
        (is (= ["n-1"] (mapv :shift/person (get-in r [:state :proposal :proposed])))))
      (testing "and the governor holds it anyway"
        (is (= :hold (disposition r)))
        (is (some #{:unlawful-roster-proposal} (rules-of r))))
      (testing "nothing was published — the pre-existing shift is all there is"
        (is (= 1 (count (store/roster st))))))))

(deftest an-overlapping-shift-is-excluded-by-availability-before-the-statute-runs
  (testing "two layers, and the cheap one goes first"
    (let [st (free (fresh) "n-1")
          _ (store/publish-shift! st (shift/shift "s-0" "n-1" :nurse (at 0 9) (at 0 20)))
          g (actor/build-graph {:store st})
          r (actor/run-request! g (gen-req ["n-1"]) {} "t-1")]
      (is (empty? (get-in r [:state :proposal :proposed])))
      (testing "and the gap is 2, not 3 — n-1's existing 09:00–20:00 shift
                already spans the demand window, so coverage counts them"
        (is (= 2 (get-in r [:state :proposal :still-short])))))))

(deftest generation-never-mutates-the-roster-before-approval
  (let [st (free (fresh) "n-1" "n-2" "n-3")
        g (actor/build-graph {:store st})]
    (dotimes [_ 3] (actor/run-request! g (gen-req ["n-1" "n-2" "n-3"]) {} (str "t-" (rand-int 1e9))))
    (testing "three generations, no approvals, still an empty roster"
      (is (empty? (store/roster st))))))
