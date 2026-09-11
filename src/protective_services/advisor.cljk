(ns protective-services.advisor
  "Protective Services Advisor — the proposal layer for the ISCO-08 5419
  Independent Protective Services Practice actor.

  Scope: this advisor supports a self-employed private security / patrol
  practitioner's own COORDINATION AND PAPERWORK — patrol/rounds logging,
  incident-report DRAFTING, shift/assignment scheduling, access-control
  logging, and security-concern flagging. It is not a law-enforcement
  system and has no security-operations authority of any kind.

  This advisor NEVER proposes, and has no notion of how to propose:
    - use-of-force authorization or coordination
    - arrest, detention, or any other law-enforcement/custodial authority
    - dispatch of an armed response
  Those categories are permanently out of scope for this actor's entire
  proposal vocabulary (the governor also independently and permanently
  blocks them — see `protective-services.governor` — so this is
  defense-in-depth, not the only backstop).

  Every proposal returned here has `:effect :propose` — this advisor never
  writes to the store, never notifies anyone, and never dispatches
  anything; it only *suggests* a coordination operation for the governor
  to check and, where required, for a human to review. Drafted incident
  reports are always drafts: this advisor cannot and does not produce a
  final legal incident record.")

(defprotocol Advisor
  "Advisor protocol for proposing protective-services coordination
   operations."
  (propose [advisor request context]
    "Propose a coordination operation from a request. Returns a proposal
     map with at minimum `:op`, `:effect` (always `:propose`),
     `:confidence`, and `:cites` (a vector of the source records/refs the
     proposal is grounded in — e.g. post orders, a shift roster, a prior
     log entry, a witness account. Empty/missing `:cites` is treated by
     the governor as ungrounded and hard-blocked)."))

(defn mock-advisor
  "Default deterministic advisor. Proposes one of a small, fixed set of
   real protective-services coordination operations based on
   `(:type request)`, with NO free-form generation and NO invented
   citations — an unrecognized or under-specified request always yields
   `{:op :unknown :confidence 0.0}`, which the governor permanently
   blocks."
  []
  (reify Advisor
    (propose [this request context]
      (let [req-type (:type request)
            op (case req-type
                 ;; Logging a completed patrol/rounds check — checkpoints
                 ;; visited and when, grounded in the post orders that
                 ;; define the round.
                 :log-patrol-round
                 {:op :log-patrol-round
                  :confidence 0.9
                  :post (:post request)
                  :checkpoints (:checkpoints request)
                  :round-time (:round-time request)
                  :cites (:post-orders-ref request [])}

                 ;; Drafting an incident report. ALWAYS a draft — this
                 ;; advisor never produces the final legal record, and the
                 ;; governor always escalates this op to human review
                 ;; before it can be treated as anything but a draft.
                 :draft-incident-report
                 {:op :draft-incident-report
                  :confidence 0.7
                  :draft? true
                  :post (:post request)
                  :summary (:summary request)
                  :cites (:witness-refs request [])}

                 ;; Scheduling a shift/assignment against a client-
                 ;; authorized post/engagement.
                 :schedule-shift-assignment
                 {:op :schedule-shift-assignment
                  :confidence 0.85
                  :post (:post request)
                  :shift-window (:shift-window request)
                  :cites (:roster-ref request [])}

                 ;; Logging a visitor/vehicle access-control entry
                 ;; (sign-in/sign-out at a gate or desk).
                 :log-access-control-entry
                 {:op :log-access-control-entry
                  :confidence 0.9
                  :post (:post request)
                  :visitor-id (:visitor-id request)
                  :direction (:direction request)
                  :cites (:sign-in-sheet-ref request [])}

                 ;; Flagging a security concern. This is the cardinal
                 ;; escalation trigger of this whole actor: it ALWAYS
                 ;; requires immediate human judgment (and, where the
                 ;; concern involves actual or threatened violence, real
                 ;; emergency services) — see governor/escalate-always.
                 ;; This advisor only proposes *raising the flag*; it never
                 ;; proposes, suggests, or implies any response action.
                 :flag-security-concern
                 {:op :flag-security-concern
                  :confidence 0.95
                  :post (:post request)
                  :concern-type (:concern-type request)
                  :description (:description request)
                  :cites (:observation-ref request [])}

                 {:op :unknown :confidence 0.0})]
        (assoc op :effect :propose)))))

(defn llm-advisor
  "Advisor backed by an LLM (ChatModel). Always returns `:effect :propose`;
   any LLM parse failure or ambiguous output yields `:confidence 0.0`
   (forces the governor to escalate rather than guess). Not wired to a
   real model here — this is a placeholder swap point (mirrors
   `officer-admin.advisor/llm-advisor` and `ironops.ironopsllm`'s
   mock/real split); the real-model call is intentionally not implemented
   in this actor."
  [chat-model]
  (reify Advisor
    (propose [this request context]
      {:op :unknown :effect :propose :confidence 0.0})))
