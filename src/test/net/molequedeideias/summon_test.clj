(ns net.molequedeideias.summon-test
  (:require [clojure.test :refer [deftest]]
            [net.molequedeideias.summon :as summon]
            [midje.sweet :refer [fact => contains just]]))

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
  (let [{::keys [parser] :as system} (summon/start simple-system)]
    (fact
      "Check if parser is started"
      parser
      => [{} {}])))
