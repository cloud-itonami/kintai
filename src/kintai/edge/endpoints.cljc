(ns kintai.edge.endpoints
  "The HTTP surface kintai exposes — exactly one route:

      POST /api/punch   record clock events from a terminal

  and nothing else, permanently. Per `manifest/repository-rules.edn` an
  itonami actor is `:on-demand`: it answers a request and stops.

  ## Why only this route

  Of kintai's four ops, `:record-punches` is the only one that both
  auto-commits and genuinely needs a network path — a wall terminal has
  to reach the actor. The other three end in a human's judgement:

    :record-punches      auto-commits, never gated on lawfulness  → exposed
    :approve-attendance  ALWAYS escalates (payroll input)         → not exposed
    :correct-punch       ALWAYS escalates (editing the record)    → not exposed
    :publish-shift       rostering decision                       → not exposed

  ## Recording is deliberately NOT gated on lawfulness

  The governor lets `:record-punches` through even when the resulting
  week would breach a statutory cap, and the edge inherits that. It is
  the right way round: refusing to record a punch because the week is
  becoming illegal would erase the evidence of the very violation. The
  gate lives on approval, which is not on this surface at all.

  ## Two gates, and neither is optional

  1. CACAO signature + temporal window (`cacao.edge.verify`, the shared
     library — not reimplemented here; ADR-2607268000).
  2. The verified caller DID must be on the allow-list, which maps DID →
     worker id.

  **An absent allow-list serves 503, never an open endpoint.** An open
  punch endpoint is an open write path into the record that decides
  someone's pay, and in several jurisdictions into evidence in a labour
  dispute."
  (:require [kintai.actor :as actor]
            [kintai.store :as store]
            [kotoba.shift :as shift]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:cljs [cacao.edge.verify :as cacao])))

(defn parse-allowlist
  "`\"did:key:z6Mk…=w-1,did:key:z6Ml…=w-2\"` -> `{did worker-id}`, or nil
  when absent, blank or wholly malformed — 'nobody is allowed' and
  'nothing was configured' are different deployment states and get
  different status codes."
  [s]
  (when (and (string? s) (seq (.trim s)))
    (let [pairs (keep (fn [entry]
                        (let [[did worker] (map #(.trim %) (.split entry "="))]
                          (when (and did worker (seq did) (seq worker))
                            [did worker])))
                      (.split (.trim s) ","))]
      (when (seq pairs) (into {} pairs)))))

(defn worker-for [allowlist did] (get allowlist did))

(defn parse-body
  "EDN request body -> `{:punches [...]}`. Every punch must carry a
  recognised `:punch/kind`; a terminal sending something else is a
  terminal to fix, not a stream to guess at. Read with
  `clojure.edn/read-string`, which evaluates nothing."
  [s]
  (try
    (let [m (edn/read-string s)]
      (when (and (map? m) (vector? (:punches m)) (seq (:punches m))
                 (every? #(contains? shift/punch-kinds (:punch/kind %)) (:punches m)))
        m))
    (catch #?(:clj Exception :cljs :default) _ nil)))

;; ---------------------------------------------------------------------------
;; Store selection
;; ---------------------------------------------------------------------------

(defn store-mode
  "How this deployment is configured to store what it accepts, from the
  `KINTAI_STORE` env var.

    nil          nothing configured
    :ephemeral   an in-process store that does not survive the request

  Returns nil for anything else, including an unrecognised value — a typo
  in a deployment variable must not silently select a storage mode.

  Portable (takes a plain map) so the decision is testable without a
  platform; the `:cljs` entry point converts Cloudflare's `env` into one."
  [env]
  (case (some-> (get env "KINTAI_STORE") .trim)
    "ephemeral" :ephemeral
    nil))

(defn store-unconfigured-response
  "What to serve when no store mode is configured.

  Deliberately 503 and NOT an empty in-process store. An empty store makes
  every request fail the governor's registration check, so the caller is
  told `:no-worker` — blamed for a deployment that has no store at all.
  Misattributed blame is worse than a refusal: the operator goes looking
  at their own registration while the actual fault is here."
  []
  {:status 503
    :body {:ok false :error "no store configured"
           :hint "bind a durable store, or set KINTAI_STORE=ephemeral for a
                  non-persisting smoke test"}})

(defn record-punches-core!
  "`POST /api/punch`. `caller-did` is already verified.

    503  no allow-list configured
    403  caller not on the allow-list
    400  unparseable body, or a punch with an unrecognised kind
    409  the governor held it, with the violations
    200  committed; the body reports the pairing anomalies the stored
         stream now has, so a terminal that dropped a clock-out learns
         about it at the moment it matters rather than at approval time"
  [store mode allowlist caller-did raw-body]
  (cond
    (nil? allowlist)
    {:status 503 :body {:ok false :error "no allow-list configured"}}

    (nil? (worker-for allowlist caller-did))
    {:status 403 :body {:ok false :error "caller not permitted"}}

    :else
    (if-let [body (parse-body raw-body)]
      (let [worker-id (worker-for allowlist caller-did)
            g (actor/build-graph {:store store})
            r (actor/run-request! g {:worker-id worker-id :op :record-punches
                                     :punches (:punches body)}
                                  {} (str "edge-" caller-did "-" (count (:punches body))))
            disposition (get-in r [:state :disposition])]
        (if (= :commit disposition)
          (let [paired (shift/pair-punches (store/punches-of store worker-id))]
            {:status 200
             :body {:ok true :ephemeral (= :ephemeral mode) :worker worker-id
                    :recorded (count (:punches body))
                    :total (count (store/punches-of store worker-id))
                    :anomalies (mapv :anomaly/rule (:anomalies paired))}})
          {:status 409
           :body {:ok false :disposition disposition
                  :violations (mapv #(select-keys % [:rule :detail])
                                    (get-in r [:state :verdict :violations]))}}))
      {:status 400 :body {:ok false :error "invalid request body"}})))

#?(:cljs
   (defn- json-response [{:keys [status body]}]
     (js/Response. (js/JSON.stringify (clj->js body))
                   #js {:status status
                        :headers #js {"content-type" "application/json"}})))

#?(:cljs
   (defn on-request-post-punch
     "Parses the Cloudflare `context`, verifies the CACAO, and hands an
     already-verified caller to `record-punches-core!`. No policy of its
     own."
     [context]
     (let [env (aget context "env")
           mode (store-mode {"KINTAI_STORE" (aget env "KINTAI_STORE")})
           allowlist (parse-allowlist (aget env "KINTAI_CALLER_ALLOWLIST"))
           header (or (.get (aget (aget context "request") "headers") "authorization") "")
           token (if (.startsWith header "Bearer ") (subs header 7) header)]
       (-> (js/Promise.all #js [(cacao/verify token) (.text (aget context "request"))])
           (.then (fn [results]
                    (let [v (aget results 0) raw (aget results 1)]
                      (cond
                        (nil? mode)
                        (json-response (store-unconfigured-response))

                        (not (:valid v))
                        (json-response {:status 401
                                                        :body {:ok false :error "invalid or expired CACAO"}})

                        :else
                        (json-response (record-punches-core! (store/mem-store) mode
                                                      allowlist (:iss v) raw))))))
           (.catch (fn [e]
                     (json-response {:status 500
                                     :body {:ok false :error "request failed"
                                            :reason (ex-message e)}})))))))
