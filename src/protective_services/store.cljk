(ns protective-services.store
  "Protective Services Store — the append-only audit ledger and persistent
  state for the ISCO-08 5419 Independent Protective Services Practice actor
  (a self-employed private security / patrol practitioner, NOT a sworn
  law-enforcement officer). Implements the Store protocol for practitioner
  and post/engagement identity verification and coordination-record
  management. Pure data — no I/O, no network, no side effects other than
  returning a new in-memory value.")

(defprotocol Store
  "Store protocol for the protective-services actor's state and audit
  ledger."
  (practitioner [store practitioner-id]
    "Retrieve a registered practitioner record by ID. Returns nil if not
     found.")
  (post [store post-id]
    "Retrieve a post/engagement record (a client site or assignment the
     practitioner is authorized to work) by ID. Returns nil if not found.")
  (register-practitioner! [store practitioner-id practitioner-data]
    "Register a practitioner (adds to store, returns updated store).")
  (register-post! [store post-id post-data]
    "Register a post/engagement (adds to store, returns updated store).
     `post-data` should include `:authorized?` once client authorization
     for the engagement has been verified.")
  (add-record! [store record-type record-data]
    "Append an immutable coordination record (patrol log, incident-report
     draft, shift assignment, access-control entry, or security-concern
     flag) to the audit ledger. Never mutates or removes prior entries.")
  (records [store]
    "Return all records in the audit ledger (immutable)."))

(defrecord MemStore [practitioners posts ledger]
  Store
  (practitioner [this practitioner-id]
    (get practitioners practitioner-id))
  (post [this post-id]
    (get posts post-id))
  (register-practitioner! [this practitioner-id practitioner-data]
    (MemStore. (assoc practitioners practitioner-id practitioner-data) posts ledger))
  (register-post! [this post-id post-data]
    (MemStore. practitioners (assoc posts post-id post-data) ledger))
  (add-record! [this record-type record-data]
    (let [record (assoc record-data
                         :type record-type
                         :timestamp #?(:clj (System/currentTimeMillis)
                                       :cljs (.getTime (js/Date.))))]
      (MemStore. practitioners posts (conj ledger record))))
  (records [this]
    ledger))

(defn create-store
  "Create a new, empty in-memory store for protective-services coordination
   records."
  []
  (MemStore. {} {} []))
