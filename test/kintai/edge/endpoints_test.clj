(ns kintai.edge.endpoints-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.shift :as shift]
            [kintai.store :as store]
            [kintai.edge.endpoints :as edge]))

(def ^:private hour 3600000)
(def ^:private day (* 24 hour))
(def ^:private t0 1767225600000)
(defn- at [d h] (+ t0 (* d day) (long (* h hour))))

(def ^:private allowlist {"did:key:zAlice" "w-1" "did:key:zBob" "w-2"})

(defn- seeded []
  (doto (store/mem-store)
    (store/register-worker! {:worker/id "w-1" :worker/jurisdiction [:jp]})
    (store/register-worker! {:worker/id "w-2" :worker/jurisdiction [:jp]})))

(defn- body [ps] (pr-str {:punches (vec ps)}))

(def ^:private clean-day
  [(shift/punch "w-1" (at 0 9) :in)
   (shift/punch "w-1" (at 0 12) :break-start)
   (shift/punch "w-1" (at 0 13) :break-end)
   (shift/punch "w-1" (at 0 18) :out)])

(deftest an-absent-allowlist-serves-503-not-an-open-endpoint
  (testing "an open punch endpoint is an open write path into the record
            that decides someone's pay"
    (is (= 503 (:status (edge/record-punches-core! (seeded) :ephemeral nil "did:key:zAlice" (body clean-day)))))))

(deftest parse-allowlist-distinguishes-empty-from-absent
  (is (nil? (edge/parse-allowlist nil)))
  (is (nil? (edge/parse-allowlist "  ")))
  (is (nil? (edge/parse-allowlist "no-equals-sign")))
  (is (= {"did:key:zAlice" "w-1"} (edge/parse-allowlist "did:key:zAlice=w-1"))))

(deftest an-unlisted-caller-is-refused
  (is (= 403 (:status (edge/record-punches-core! (seeded) :ephemeral allowlist "did:key:zMallory" (body clean-day))))))

(deftest the-worker-comes-from-the-did-not-the-body
  (let [st (seeded)
        r (edge/record-punches-core! st :ephemeral allowlist "did:key:zBob"
                                     (pr-str {:worker "w-1" :punches clean-day}))]
    (is (= 200 (:status r)))
    (is (= "w-2" (get-in r [:body :worker])))
    (testing "nothing landed under w-1 despite the body naming it"
      (is (empty? (store/punches-of st "w-1"))))))

(deftest a-punch-with-an-unrecognised-kind-is-400
  (testing "a terminal sending something else is a terminal to fix, not a
            stream to guess at"
    (doseq [bad ["" "((" "{:punches []}"
                 "{:punches [{:punch/person \"w-1\" :punch/at 1 :punch/kind :vibes}]}"]]
      (is (= 400 (:status (edge/record-punches-core! (seeded) :ephemeral allowlist "did:key:zAlice" bad)))
          (str "should reject " (pr-str bad))))))

(deftest a-clean-day-commits-and-reports-no-anomalies
  (let [st (seeded)
        r (edge/record-punches-core! st :ephemeral allowlist "did:key:zAlice" (body clean-day))]
    (is (= 200 (:status r)))
    (is (= 4 (get-in r [:body :recorded])))
    (is (empty? (get-in r [:body :anomalies])))))

(deftest a-dropped-clock-out-is-reported-at-ingest-not-at-approval
  (testing "a terminal that dropped a punch learns about it when it matters"
    (let [st (seeded)
          r (edge/record-punches-core! st :ephemeral allowlist "did:key:zAlice"
                                       (body [(shift/punch "w-1" (at 0 9) :in)]))]
      (is (= 200 (:status r)))
      (is (= [:missing-out] (get-in r [:body :anomalies]))))))

(deftest recording-is-never-gated-on-lawfulness
  (testing "refusing to record because the week is becoming illegal would
            erase the evidence of the very violation"
    (let [st (seeded)
          fourteen-hours [(shift/punch "w-1" (at 0 8) :in) (shift/punch "w-1" (at 0 22) :out)]
          r (edge/record-punches-core! st :ephemeral allowlist "did:key:zAlice" (body fourteen-hours))]
      (is (= 200 (:status r)))
      (is (= 2 (count (store/punches-of st "w-1")))))))

(deftest the-escalating-ops-have-no-http-representation
  (let [publics (set (keys (ns-publics 'kintai.edge.endpoints)))]
    (is (contains? publics 'record-punches-core!))
    (doseq [absent '[approve-attendance-core! correct-punch-core! publish-shift-core!]]
      (is (not (contains? publics absent)) (str absent " must not exist")))))

(defn- successful-response []
  (edge/record-punches-core! (seeded) :ephemeral allowlist "did:key:zAlice" (body clean-day)))

;; ---------------------------------------------------------------------------
;; Store configuration — a deployment with no store must not blame the caller
;; ---------------------------------------------------------------------------

(deftest store-mode-reads-only-values-it-recognises
  (is (nil? (edge/store-mode {})))
  (is (nil? (edge/store-mode {"KINTAI_STORE" ""})))
  (is (= :ephemeral (edge/store-mode {"KINTAI_STORE" "ephemeral"})))
  (is (= :ephemeral (edge/store-mode {"KINTAI_STORE" "  ephemeral  "})))
  (testing "a typo in a deployment variable must not silently select a mode"
    (is (nil? (edge/store-mode {"KINTAI_STORE" "ephemral"})))
    (is (nil? (edge/store-mode {"KINTAI_STORE" "durable"})))))

(deftest an-unconfigured-store-serves-503-and-says-whose-fault-it-is
  (let [r (edge/store-unconfigured-response)]
    (is (= 503 (:status r)))
    (testing "not a 409 :no-worker — an empty in-process store would fail the
              governor's registration check and blame the CALLER for a
              deployment that has no store at all"
      (is (= "no store configured" (get-in r [:body :error])))
      (is (re-find #"ephemeral" (get-in r [:body :hint]))))))

(deftest an-ephemeral-store-says-so-in-every-success-response
  (testing "nobody should mistake a smoke-test deployment for persistence"
    (is (true? (get-in (successful-response) [:body :ephemeral]))))) 
