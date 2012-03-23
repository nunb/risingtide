(ns risingtide.integration.core
  (:use risingtide.integration.support
        risingtide.test)
  (:use [midje.sweet])
  (:require [risingtide.stories :as story]))

(defn wait-a-sec
  []
  (Thread/sleep 1000))

(background
 (before :facts (clear-redis!))
 (before :facts (clear-digesting-cache!)))

(fact "multiple actions by an interesting user are digested"
  (on-copious
   (rob interested-in-user jim)
   (jim activates bacon)
   (jim likes bacon)
   ;; stuff that shouldn't matter
   (jon likes bacon))

  (feed-for-rob :card) => (encoded-feed
                           (story/multi-action-digest bacon jim ["listing_activated" "listing_liked"]))
  (feed-for-jim :card) => empty-feed)

(fact "multiple actions by a multiple interesting users are digested"
  (on-copious
   (rob interested-in-user jim)
   (rob interested-in-user jon)
   (jim activates bacon)
   (jim likes bacon)
   (jon likes bacon)
   ;; stuff that shouldn't matter
   (bcm shares bacon))

  (feed-for-rob :card) => (encoded-feed (story/multi-actor-multi-action-digest
                                         bacon
                                         {"listing_liked" [jim jon] "listing_activated" [jim]}))
  (feed-for-jim :card) => empty-feed)

(fact "multiple users performing the same action are digested"
  (on-copious
   (rob interested-in-user jim)
   (rob interested-in-user jon)
   (jim likes bacon)
   (jon likes bacon)
   ;; stuff that shouldn't matter
   (bcm likes bacon))

  (feed-for-rob :card) => (encoded-feed (story/multi-actor-digest
                                         bacon "listing_liked" [jim jon]))
  (feed-for-jim :card) => empty-feed)

(fact "digest stories coexist peacefully with other stories"
  (on-copious
   (jim likes ham)
   (wait-a-sec)
   (rob interested-in-user jim)
   (rob interested-in-user jon)
   (jim likes bacon)
   (wait-a-sec)
   (jon likes bacon)
   (wait-a-sec)
   (jon likes eggs)
   ;; stuff that shouldn't matter
   (bcm likes bacon))

  (feed-for-rob :card) => (encoded-feed
                           (listing-liked jim ham)
                           (story/multi-actor-digest bacon "listing_liked" [jim jon])
                           (listing-liked jon eggs))
  (feed-for-jim :card) => empty-feed)

(fact "the everything feed contains (allthethings)"
  (on-copious
   (jim likes ham)
   (wait-a-sec)
   (rob interested-in-user jim)
   (rob interested-in-user jon)
   (jim likes bacon)
   (wait-a-sec)
   (jon likes bacon)
   (wait-a-sec)
   (jon likes eggs)
   (wait-a-sec)
   (bcm likes bacon)
   (dave shares muffins)) ;; NOTE THAT THIS HAS NEVER HAPPENED >:o

  (everything-feed) => (encoded-feed
                        (listing-liked jim ham)
                        (listing-liked jon eggs)
                        (story/multi-actor-digest bacon "listing_liked" [jim jon bcm])
                        (listing-shared dave muffins))
  (feed-for-rob :card) => (encoded-feed
                           (listing-liked jim ham)
                           (story/multi-actor-digest bacon "listing_liked" [jim jon])
                           (listing-liked jon eggs)))
