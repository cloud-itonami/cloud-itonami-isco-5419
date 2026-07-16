(ns protective-services.actor
  "Protective Services Actor — the langgraph StateGraph wiring and runtime
  for the ISCO-08 5419 Independent Protective Services Practice actor (a
  self-employed private security / patrol practitioner's coordination and
  paperwork assistant — NOT a law-enforcement or dispatch system).
  Implements the itonami actor pattern with Advisor/Governor separation
  and an append-only audit trail: every :commit and every :hold appends
  an immutable record to the store's ledger. See
  `protective-services.governor` for the full scope statement and the
  permanently-forbidden-operations list.

  Wired against `langgraph.graph`'s real current API (`state-graph` /
  `add-node` / `add-edge` / `add-conditional-edges` / `compile-graph` /
  `invoke`) — nodes are plain functions `state -> partial-update-map`,
  folded into state by the graph's channel reducers (default:
  last-write-wins per key)."
  (:require [langgraph.graph :as graph]
            [protective-services.store :as store]
            [protective-services.advisor :as advisor]
            [protective-services.governor :as governor]))

(defn- intake-node
  "Intake node: accept the incoming request and move to :advise."
  [state]
  {:phase :advise})

(defn- advise-node
  "Advise node: Advisor proposes a coordination operation. The advisor
   never writes to the store and never acts."
  [state advisor-instance]
  (let [proposal (advisor/propose advisor-instance (:request state) (:context state))]
    {:proposal proposal :phase :govern}))

(defn- govern-node
  "Govern node: the independent Governor evaluates the proposal. This is
   the only place a proposal can be rejected outright or routed to a
   human — the advisor has no say in either."
  [state store-instance]
  (let [verdict (governor/check (:request state) (:context state) (:proposal state) store-instance)]
    {:decision verdict :phase :decide}))

(defn- decide-route
  "Router (used as a conditional edge from :govern, not a node): `:hard?`
   (permanent block / safety-concern hard-stop) always wins over
   `:escalate?`."
  [state]
  (let [decision (:decision state)]
    (cond
      (:hard? decision)     :hold
      (:escalate? decision) :request-approval
      :else                 :commit)))

(defn- commit-node
  "Commit node: append the proposal as an immutable coordination record to
   the store's audit ledger."
  [state store-instance]
  (let [proposal (:proposal state)
        op (:op proposal)
        store' (store/add-record! store-instance op proposal)]
    {:phase :complete
     :store store'
     :records (conj (or (:records state) []) {:recorded true :op op})}))

(defn- request-approval-node
  "Request-approval node: this run ends awaiting human review (e.g.
   incident-report drafts, low-confidence proposals). No record is
   committed to the ledger yet — `approve!` (below) is the human sign-off
   entry point that can move this to :commit on a fresh run."
  [state]
  {:phase :awaiting-approval})

(defn- hold-node
  "Hold node: reject the proposal outright (hard violation — permanently
   forbidden operation, unregistered practitioner, missing spec-basis, or
   a security-concern flag, which is always a hard stop in this actor).
   The hold itself is appended to the audit ledger via
   `governor/hold-fact` (append-only: rejections are as auditable as
   commits). There is no approve! path out of :hold for a
   forbidden-operation violation; a security-concern hold means a human
   must act directly and outside this actor (including contacting real
   emergency services where warranted)."
  [state store-instance]
  (let [verdict (:decision state)
        fact (governor/hold-fact (:request state) (:context state) (:proposal state) verdict)
        store' (store/add-record! store-instance :governor-hold fact)]
    {:phase :rejected
     :store store'
     :error (str "Hard governance violation: " (:violations verdict))}))

(defn build-graph
  "Build and compile the StateGraph for the protective-services actor.
   `advisor-instance` and `store-instance` are closed over by the
   :advise/:govern/:commit/:hold nodes (the injection-boundary swap
   pattern — mock or real advisor, MemStore or another Store
   implementation)."
  [advisor-instance store-instance]
  (-> (graph/state-graph)
      (graph/add-node :intake (fn [state] (intake-node state)))
      (graph/add-node :advise (fn [state] (advise-node state advisor-instance)))
      (graph/add-node :govern (fn [state] (govern-node state store-instance)))
      (graph/add-node :commit (fn [state] (commit-node state store-instance)))
      (graph/add-node :request-approval (fn [state] (request-approval-node state)))
      (graph/add-node :hold (fn [state] (hold-node state store-instance)))
      (graph/set-entry-point :intake)
      (graph/add-edge :intake :advise)
      (graph/add-edge :advise :govern)
      (graph/add-conditional-edges :govern decide-route)
      (graph/set-finish-point :commit)
      (graph/set-finish-point :request-approval)
      (graph/set-finish-point :hold)
      (graph/compile-graph)))

(defn run-request!
  "Run a coordination request through the compiled actor graph to
   completion (or to an :awaiting-approval / :rejected stop). Returns the
   final state — `:phase` tells you which of :complete /
   :awaiting-approval / :rejected it ended in; `:store` is only present
   after a :commit or :hold (the updated, immutable store value with the
   new ledger entry — the caller is responsible for keeping it for the
   next run, since this actor never mutates in place)."
  [compiled-graph initial-request context]
  (graph/invoke compiled-graph {:request initial-request :context context :phase :intake}))

(defn approve!
  "Approve a request that was held in :request-approval phase (e.g. an
   incident-report draft or a low-confidence proposal), and actually
   commit it — appends the originally-proposed record to the store's
   ledger under the approver's context. NEVER used to override a :hold —
   a forbidden-operation or safety-concern hard-stop has no approval path
   through this actor at all; `approve!` only accepts state whose
   `:phase` is `:awaiting-approval`."
  [state approval-context store-instance]
  (if (= :awaiting-approval (:phase state))
    (let [proposal (:proposal state)
          op (:op proposal)
          store' (store/add-record! store-instance op (assoc proposal :approval approval-context))]
      (assoc state :phase :complete :approval approval-context :store store'
             :records (conj (or (:records state) []) {:recorded true :op op :approved true})))
    (throw (ex-info "approve! only accepts a state awaiting human approval; :hold has no override path"
                     {:phase (:phase state)}))))
