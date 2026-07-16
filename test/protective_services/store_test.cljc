(ns protective-services.store-test
  (:require [clojure.test :refer [deftest is testing]]
            [protective-services.store :as store]))

(deftest test-create-store
  (testing "Create a new store"
    (let [s (store/create-store)]
      (is (not (nil? s)))
      (is (satisfies? store/Store s)))))

(deftest test-register-practitioner
  (testing "Register and retrieve a practitioner"
    (let [s (store/create-store)
          s' (store/register-practitioner! s "prac-001" {:name "Alex Rivera" :license "PSW-4471"})
          retrieved (store/practitioner s' "prac-001")]
      (is (= (:name retrieved) "Alex Rivera"))
      (is (= (:license retrieved) "PSW-4471")))))

(deftest test-register-post
  (testing "Register and retrieve a post/engagement"
    (let [s (store/create-store)
          s' (store/register-post! s "post-001" {:client "Riverside Mall" :authorized? true})
          retrieved (store/post s' "post-001")]
      (is (= (:client retrieved) "Riverside Mall"))
      (is (true? (:authorized? retrieved))))))

(deftest test-add-record
  (testing "Add and retrieve records from audit ledger"
    (let [s (store/create-store)
          s' (store/add-record! s :patrol-round {:practitioner-id "prac-001" :post "post-001"})
          records (store/records s')]
      (is (= (count records) 1))
      (is (= (:type (first records)) :patrol-round)))))

(deftest test-ledger-is-append-only
  (testing "Records accumulate, never disappear, across successive add-record! calls"
    (let [s (store/create-store)
          s' (-> s
                 (store/add-record! :patrol-round {:round 1})
                 (store/add-record! :patrol-round {:round 2})
                 (store/add-record! :flag-security-concern {:concern-type :suspicious-vehicle}))
          records (store/records s')]
      (is (= (count records) 3))
      (is (= [:patrol-round :patrol-round :flag-security-concern]
             (mapv :type records))))))

(deftest test-immutability
  (testing "Store operations return new store instances, prior value unaffected"
    (let [s (store/create-store)
          s' (store/register-practitioner! s "prac-001" {:name "Alex Rivera"})
          prac-in-s (store/practitioner s "prac-001")
          prac-in-s' (store/practitioner s' "prac-001")]
      (is (nil? prac-in-s))
      (is (not (nil? prac-in-s')))
      (is (= (:name prac-in-s') "Alex Rivera")))))

(deftest test-unknown-lookups-return-nil
  (testing "Looking up an unregistered practitioner or post returns nil, not an error"
    (let [s (store/create-store)]
      (is (nil? (store/practitioner s "nobody")))
      (is (nil? (store/post s "nowhere"))))))
