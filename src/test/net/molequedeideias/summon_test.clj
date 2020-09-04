(ns net.molequedeideias.summon-test
  (:require [clojure.test :refer [deftest]]
            [net.molequedeideias.summon :as summon]
            [midje.sweet :refer [fact => contains just]]
            [com.stuartsierra.component :as component])
  (:import (java.util Date)
           (java.time Instant Duration)))

(defn serial-clock
  [inst ^Duration duration]
  (let [current (atom inst)]
    (proxy [java.time.Clock] []
      (instant []
        (swap! current #(.plus ^Instant % duration))))))

(def simple-system
  {::entity-schema   {}
   ::summon/drivers  {::driver-datascript {::summon/input  [::schema]
                                           ::summon/output [::conn]
                                           ::summon/start  (fn [{::keys [schema]}]
                                                             {::conn schema})}
                      ::driver-pathom     {::summon/input  [::conn1
                                                            ::conn2]
                                           ::summon/output [::parser]
                                           ::summon/start  (fn [{::keys [conn1 conn2]}]
                                                             {::parser [conn1 conn2]})}}
   ::summon/elements {::entity-conn-id {::summon/driver   ::driver-datascript
                                        ::summon/requires {::schema ::entity-schema}
                                        ::summon/provides {::entity-conn ::conn}}
                      ::event-conn-id  {::summon/driver   ::driver-datascript
                                        ::summon/requires {::schema ::event-schema}
                                        ::summon/provides {::event-conn ::conn}}
                      ::parser         {::summon/driver   ::driver-pathom
                                        ::summon/requires {::conn1 ::event-conn
                                                           ::conn2 ::entity-conn}
                                        ::entity-conn     ::entity-conn
                                        ::summon/provides {::parser ::parser}}}
   ::event-schema    {}})

(deftest simple-system-test
  (fact
    "Check if system is valid"
    (summon/valid? simple-system)
    => true)
  (fact
    "Check required globals"
    (summon/required-globals simple-system)
    => [::entity-schema
        ::event-schema])
  (fact
    "Check missing globals"
    (summon/explain-data (dissoc simple-system ::entity-schema))
    => [{::summon/issue ::summon/missing-global,
         ::summon/key   ::entity-schema}])
  (fact
    "Check missing drivers"
    (summon/explain-data (update simple-system ::summon/drivers dissoc ::driver-pathom))
    => [{::summon/issue       ::summon/missing-driver,
         ::summon/driver      ::driver-pathom
         ::summon/required-by ::parser}])
  (fact
    "Check missing inputs"
    (summon/explain-data (update-in simple-system [::summon/elements ::entity-conn-id] assoc ::summon/requires {}))
    => [{::summon/issue       ::summon/missing-input,
         ::summon/missing     ::schema
         ::summon/driver      ::driver-datascript
         ::summon/required-by ::entity-conn-id}])
  (fact
    "Check missing globals"
    (summon/explain-data (-> simple-system
                             (update ::summon/elements dissoc ::event-conn-id)
                             (assoc ::event-conn {})))
    => [])
  (let [clock (serial-clock
                (.toInstant #inst"2000")
                (Duration/ofDays 1))
        {::keys        [parser]
         ::summon/keys [stats]
         :as           system} (summon/start (assoc simple-system
                                               ::summon/clock clock))]
    (fact
      "Check if parser is started"
      parser
      => [{} {}])
    (fact
      (map (juxt ::summon/id
                 (comp #(Date/from %)
                       ::summon/pre-start)
                 (comp #(Date/from %)
                       ::summon/post-start))
           stats)
      => [[::event-conn-id
           #inst "2000-01-02"
           #inst "2000-01-03"]
          [::entity-conn-id
           #inst "2000-01-04"
           #inst "2000-01-05"]
          [::parser
           #inst "2000-01-06"
           #inst "2000-01-07"]])))


(def datascript-driver
  (with-meta {::summon/driver ::datascript-driver
              ::summon/input  [::schema]
              ::summon/output [::conn]}
             `{component/start ~(fn [{::keys [schema]
                                      :as    env}]
                                  (swap! (::invoke-order env)
                                         conj {::start  ::datascript-driver
                                               ::schema schema})
                                  (assoc env ::conn schema))
               component/stop  ~(fn [{::keys [conn]
                                      :as    env}]
                                  (swap! (::invoke-order env)
                                         conj {::stop ::datascript-driver
                                               ::conn conn})
                                  env)}))


(def pathom-driver
  (with-meta {::summon/driver ::pathom-driver
              ::summon/input  [::conn1
                               ::conn2]
              ::summon/output [::parser]}
             `{component/start ~(fn [{::keys [conn1 conn2]
                                      :as    env}]
                                  (swap! (::invoke-order env)
                                         conj {::start ::pathom-driver
                                               ::conn1 conn1
                                               ::conn2 conn2})
                                  {::parser [conn1 conn2]})
               component/stop  ~(fn [{::keys [parser]
                                      :as    env}]
                                  (swap! (::invoke-order env)
                                         conj {::stop   ::pathom-driver
                                               ::parser parser})
                                  env)}))


(def stuart-system
  (assoc summon/system
    ::entity-schema {:a 1}
    ::event-schema {:b 2}
    ::summon/elements {::entity-conn-id (assoc datascript-driver
                                          ::summon/requires {::schema ::entity-schema}
                                          ::summon/provides {::entity-conn ::conn})
                       ::event-conn-id  (assoc datascript-driver
                                          ::summon/driver ::driver-datascript
                                          ::summon/requires {::schema ::event-schema}
                                          ::summon/provides {::event-conn ::conn})
                       ::parser         (assoc pathom-driver
                                          ::summon/requires {::conn1       ::event-conn
                                                             ::conn2       ::entity-conn
                                                             ::entity-conn ::entity-conn}
                                          ::summon/provides {::parser ::parser})}))
(deftest stuart
  (let [invoke-order (atom [])
        app (-> stuart-system
                (assoc
                  ::invoke-order invoke-order
                  ::summon/targets [::parser])
                component/start)]
    (fact
      (::parser app)
      => [{:b 2} {:a 1}])
    (component/stop app)
    (fact
      @invoke-order
      => [{::start  ::datascript-driver
           ::schema {:b 2}}
          {::start  ::datascript-driver
           ::schema {:a 1}}
          {::start ::pathom-driver
           ::conn1 {:b 2}
           ::conn2 {:a 1}}
          {::stop   ::pathom-driver
           ::parser [{:b 2} {:a 1}]}
          {::stop ::datascript-driver
           ::conn {:a 1}}
          {::stop ::datascript-driver
           ::conn {:b 2}}])))

