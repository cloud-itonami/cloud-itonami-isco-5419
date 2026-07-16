(ns protective-services.governor
  "ProtectiveServicesGovernor — the independent safety/scope/traceability
  layer that earns `protective-services.advisor` the right to commit a
  coordination record. Wired as its own `:govern` node in
  `protective-services.actor`'s StateGraph, downstream of `:advise` — the
  advisor has no notion of practitioner provenance, client authorization,
  or the legal/safety stakes of this occupation, so this MUST be a
  separate system able to reject a proposal outright (itonami actor
  pattern, per ADR-2607011000 / CLAUDE.md Actors section).

  SCOPE (read this before reading the checks below): this actor is a
  COORDINATION AND PAPERWORK tool for a self-employed private security /
  patrol practitioner (ISCO-08 5419) — NOT a sworn law-enforcement
  officer. It exists to help log patrol rounds, draft incident-report
  DRAFTS, schedule shift/assignments against client-authorized posts, log
  access-control entries, and flag security concerns for a human. It has
  no use-of-force authority, no arrest/detention authority, and no
  ability to dispatch any kind of response, armed or otherwise — those
  are permanently out of scope, not merely gated. This mirrors the
  repository's own `docs/business-model.md` Trust Controls (\"no
  use-of-force or restraint action without governor gate and human
  sign-off\"; \"client-authorization verification required before any
  assignment is dispatched\"; \"assignment and incident records are
  auditable, not editable\") and applies them more conservatively than
  the minimum: the categories below are never gated open, by anyone.

  `check` is a pure function of (request, context, proposal, store) ->
  verdict; it never mutates the store. The StateGraph's `:decide` node
  routes on the verdict:
    :hard? true       → :hold              (no write, no auto-approve path)
    :escalate? true    → :request-approval  (interrupt-before, human sign-off)
    otherwise          → :commit

  HARD invariants (`:hard? true`, ALWAYS `:hold`, no human override —
  same \"even human approval cannot override\" framing as
  `ironops.governor/forbidden-operation-violations`):
    1. practitioner provenance     — the requesting practitioner must be
                                      registered.
    2. no-actuation                — proposal `:effect` must be
                                      `:propose`; this actor never writes,
                                      notifies, or dispatches on its own.
    3. forbidden-operation         — use-of-force authorization,
                                      arrest/detention authority, and
                                      armed-response dispatch are
                                      PERMANENTLY excluded from this
                                      actor's proposal set. No confidence
                                      level and no human approval can open
                                      this gate; it does not have an
                                      approval path at all.
    4. safety-concern-escalation   — a `:flag-security-concern` proposal
                                      is ALWAYS treated as a hard stop:
                                      this actor never auto-files or
                                      auto-resolves a report of an actual
                                      or threatened safety concern. A
                                      human (and, where warranted, real
                                      emergency services) must handle it
                                      directly; this actor's role ends at
                                      surfacing the flag.
    5. post-authorization           — scheduling a shift/assignment
                                      requires the referenced post to be
                                      registered AND `:authorized?` (i.e.
                                      client authorization for the
                                      engagement has been verified).
    6. spec-basis                  — a proposal with no cited basis (post
                                      orders, shift roster, prior log
                                      entry, witness reference, etc.)
                                      cannot be treated as grounded
                                      coordination.

  ESCALATION invariants (`:escalate? true`, human sign-off required, but
  NOT a permanent block — the operation itself is legitimate and may
  proceed once a human has reviewed it):
    7. draft-incident-report       — incident-report drafting always
                                      requires human review before the
                                      draft can be treated as anything
                                      more than a draft; this actor never
                                      produces a final legal record.
    8. low confidence              (< `confidence-floor`)."
  (:require [protective-services.store :as store]))

(def confidence-floor 0.6)

(def known-ops
  "The complete, closed vocabulary of coordination operations this actor
   supports. Anything outside this set (including `:unknown`) is
   out-of-scope and never grounded."
  #{:log-patrol-round
    :draft-incident-report
    :schedule-shift-assignment
    :log-access-control-entry
    :flag-security-concern})

(def forbidden-ops
  "Operations permanently excluded from this actor's proposal set, with no
   human-approval override, ever. This actor supports COORDINATION
   (logging, drafting, scheduling, flagging) — NOT use-of-force
   authorization, arrest/detention authority, or armed-response dispatch.
   `:unknown` (unparseable / out-of-scope requests) is included here too:
   an unrecognized request never silently proceeds."
  #{:unknown
    :authorize-use-of-force
    :authorize-arrest-or-detention
    :dispatch-armed-response})

(def escalate-always
  "Operations that ALWAYS require human review/escalation, even when every
   other governor check passes and confidence is high — mirrors
   `ironops.governor/escalate-always`. Flagging a security concern is the
   cardinal escalation trigger in this actor: it alone cannot auto-proceed,
   ever (see `safety-concern-escalation` in `hard-violations` — it is
   additionally treated as a HARD stop, not merely an escalation, because
   this actor has no authority to act on a safety concern in any way and
   must not appear to)."
  #{:flag-security-concern})

;; Ops that require human sign-off before being treated as final, but are
;; not permanently blocked — the practitioner may still complete/refile
;; after review.
(def ^:private escalating-ops #{:draft-incident-report})

;; ----------------------------- checks -----------------------------

(defn- forbidden-operation-violations
  "Proposals attempting use-of-force authorization, arrest/detention
   authority, or armed-response dispatch (or any unrecognized/out-of-scope
   op) are permanently HARD violations — this actor does coordination
   only, forever, regardless of confidence or human approval."
  [{:keys [op]}]
  (when (contains? forbidden-ops op)
    [{:rule :forbidden-operation
      :detail (str "operation " op " is permanently out of scope for this actor: "
                   "use-of-force authorization, arrest/detention authority, and "
                   "armed-response dispatch are NEVER performed or coordinated by "
                   "this actor. This actor has no law-enforcement or use-of-force "
                   "authority of any kind, and no human approval can grant it one — "
                   "there is no override path for this rule.")}]))

(defn- safety-concern-escalation-violations
  "A `:flag-security-concern` proposal is ALWAYS a hard stop: this actor
   never auto-files or auto-resolves a safety concern, at any confidence
   level. A human must handle it directly (and, where the concern
   involves actual or threatened violence, contact real emergency
   services) — this actor's role ends at surfacing the flag for that
   human."
  [{:keys [op]}]
  (when (= :flag-security-concern op)
    [{:rule :safety-concern-escalation
      :detail (str "security-concern flags always require immediate human judgment; "
                   "this actor never auto-files, auto-dismisses, or auto-resolves a "
                   "safety concern, and never coordinates any response to one — "
                   "no override.")}]))

(defn- post-authorization-violations
  "Scheduling a shift/assignment requires the referenced post to be
   registered and client-authorization to be verified — mirrors this
   repo's own docs/business-model.md Trust Control: \"client-authorization
   verification required before any assignment is dispatched.\""
  [proposal st]
  (when (= :schedule-shift-assignment (:op proposal))
    (let [post-id (:post proposal)
          post-record (store/post st post-id)]
      (cond
        (nil? post-record)
        [{:rule :post-record-missing
          :detail (str post-id " has no registered post/engagement record")}]

        (not (true? (:authorized? post-record)))
        [{:rule :post-not-authorized
          :detail (str post-id "'s client authorization for this engagement has not "
                       "been verified — no assignment may be scheduled without it")}]))))

(defn- spec-basis-violations
  "A known-op proposal with no cited basis is a HARD violation — it cannot
   be distinguished from an invented coordination action."
  [proposal]
  (when (contains? known-ops (:op proposal))
    (when (empty? (:cites proposal))
      [{:rule :no-spec-basis
        :detail "no cited basis (post orders, shift roster, prior log entry, witness reference, etc.) — cannot treat this proposal as grounded"}])))

(defn- hard-violations
  [proposal practitioner-record store*]
  (into []
        (concat
         (when (nil? practitioner-record)
           [{:rule :practitioner-not-registered
             :detail "the requesting protective-services practitioner is not registered in the store"}])
         (when (not= :propose (:effect proposal))
           [{:rule :no-actuation
             :detail "effect must be :propose only — this actor never writes, notifies, or dispatches on its own authority"}])
         (forbidden-operation-violations proposal)
         (safety-concern-escalation-violations proposal)
         (post-authorization-violations proposal store*)
         (spec-basis-violations proposal))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a `store`
   implementing `protective-services.store/Store`. Returns
   `{:ok? bool :violations [...] :confidence n :hard? bool :escalate? bool}`."
  [request context proposal store]
  (let [practitioner-record (store/practitioner store (:practitioner-id request))
        hard (hard-violations proposal practitioner-record store)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        escalating-op? (or (contains? escalating-ops (:op proposal))
                            (contains? escalate-always (:op proposal)))]
    {:ok? (and (not hard?) (not low?) (not escalating-op?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (or hard? low? escalating-op?)}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD) — appended to
   the store's ledger by the actor, never fabricated after the fact.
   `:op` comes from `proposal` (not `request`) — in this actor's
   convention the request carries `:type`/`:practitioner-id`/etc., and it
   is the advisor's proposal that carries the (possibly `:unknown`) `:op`
   the governor actually judged."
  [request context proposal verdict]
  {:t :governor-hold
   :op (:op proposal)
   :practitioner (:practitioner-id request)
   :post (or (:post proposal) (:post request))
   :disposition :hold
   :basis (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
