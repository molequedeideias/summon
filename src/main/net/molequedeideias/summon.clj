(ns net.molequedeideias.summon
  (:require [br.com.souenzzo.eql-as.alpha :as eql-as]
            [ubergraph.alg :as uber.alg]
            [ubergraph.core :as uber]
            [clojure.spec.alpha :as s]
            [edn-query-language.core :as eql])
  (:import (java.time Instant Duration Clock)))

(set! *warn-on-reflection* true)

(s/def ::driver qualified-keyword?)
(s/def ::drivers (s/map-of ::driver ::driver-impl))

(s/def ::driver-impl (s/keys :req [::input
                                   ::output
                                   ::start]))
(s/def ::elements (s/map-of ::id (s/keys :opt [::driver])))
(s/def ::id qualified-keyword?)
(s/def ::input (s/coll-of qualified-keyword?))
(s/def ::output (s/coll-of qualified-keyword?))
(s/def ::start fn?)
(s/def ::system (s/keys ::req [::drivers
                               ::elements]))

(def ^:dynamic *default-placeholder-prefixes* #{">"})

(defn elements->digraph
  [root {::keys [elements]
         :as    system}]
  (let [elements (for [[id el] elements]
                   (assoc el ::id id))
        index-id->element (into {} (map (juxt ::id identity))
                                elements)
        index-element->requires (reduce (fn [idx {::keys [id requires]}]
                                          (assoc idx
                                            id (set (vals requires))))
                                        {}
                                        elements)
        #_#_index-require->element (reduce (fn [idx {::keys [id requires]}]
                                             (into idx
                                                   (for [r (keys requires)]
                                                     [r id])))
                                           {}
                                           elements)
        #_#_index-element->provides (reduce (fn [idx {::keys [id provides]}]
                                              (assoc idx
                                                id (set (keys provides))))
                                            {}
                                            elements)
        index-provide->element (reduce (fn [idx {::keys [id provides]}]
                                         (into idx
                                               (for [p (keys provides)]
                                                 [p id])))
                                       {}
                                       elements)]
    (concat [[root {:label (name root)}]]
            (for [{::keys [id driver requires]} elements
                  init (concat [[id {:label (str (str "id: " (pr-str id))
                                                 (if driver
                                                   (str "\ndriver: " (pr-str driver))
                                                   "\n(inline driver)"))}]]
                               (for [require (get index-element->requires id)
                                     :let [from (if (contains? system require)
                                                  root
                                                  (get index-provide->element require root))
                                           as (get (eql-as/reverse requires) require)
                                           from-attr (get (::provides (get index-id->element from)) require)]]
                                 [from id {:require require
                                           :label   (str (str "require: " (pr-str require))
                                                         (str "\nas: " (pr-str as))
                                                         (str "\nfrom: " (if (= from root)
                                                                           (name root)
                                                                           (pr-str from-attr))))}]))]
              init))))

(defn graph
  [system root]
  (apply uber/multidigraph (elements->digraph root system)))

(defn required-globals
  [system]
  (let [g (graph system ::system)]
    (mapv #(uber/attr g % :require) (uber/find-edges g {:src ::system}))))

(defn explain-data
  [{::keys [drivers elements]
    :as    system}]
  (let [g (graph system ::system)
        used-drivers (set (keep ::driver (vals elements)))]
    (concat (when-not (uber.alg/dag? g)
              [{::issue ::not-a-dag}])
            (for [driver (remove used-drivers (keys drivers))]
              {::issue  ::unused-driver
               ::driver driver})
            (for [k (required-globals system)
                  :when (and (not (contains? system k))
                             (not (contains? *default-placeholder-prefixes* (namespace k))))]
              {::issue ::missing-global
               ::key   k})
            (for [[id {::keys [driver start]}] elements
                  :when (and (not (contains? drivers driver))
                             (not (fn? start)))]
              {::issue       ::missing-driver
               ::required-by id
               ::driver      driver})
            (for [[id {::keys [driver requires]}] elements
                  missing-input (remove (set (keys requires))
                                        (get-in drivers [driver ::input]))
                  :when (not (contains? system missing-input))]
              {::issue       ::missing-input
               ::required-by id
               ::driver      driver
               ::missing     missing-input})
            (for [
                  [id {::keys [driver provides]}] elements
                  :let [availbe-outputs (some-> (get-in drivers [driver ::output])
                                                set)]
                  :when availbe-outputs
                  provide (vals provides)
                  :when (not (contains? availbe-outputs provide))]
              {::issue             ::missmatch-output
               ::element           id
               ::trying-to-provide provide
               ::but-driver        driver
               ::only-outputs      availbe-outputs}))))

(defn valid?
  [system]
  (empty? (explain-data system)))

(defn relevant-elements
  [g ids]
  (let [predecessors (partial uber/predecessors g)]
    (loop [nodes ids
           paths []]
      (if (empty? nodes)
        (set paths)
        (recur (mapcat predecessors nodes)
               (concat nodes paths))))))

(defn elements-for-start
  [{::keys [elements]
    :as    system} selected-ids]
  (let [g (graph system ::system)
        els (relevant-elements g selected-ids)]
    (doall (for [id (uber.alg/topsort g)
                 :when (and
                         (contains? els id)
                         (contains? elements id))]
             (assoc (get elements id)
               ::id id)))))

(defn map-select-from-ast
  [{:keys [children] :as ast-root} data]
  (cond
    (not children) data
    (map? data) (into {}
                      (keep (fn [{:keys [dispatch-key params query] :as ast}]
                              (cond
                                (contains? data dispatch-key) [(:pathom/as params dispatch-key)
                                                               (map-select-from-ast ast (get data dispatch-key))]
                                (= (namespace dispatch-key) ">") [(:pathom/as params dispatch-key)
                                                                  (map-select-from-ast ast data)]
                                :else nil)))
                      children)
    (coll? data) (map (partial map-select-from-ast ast-root) data)
    :else data))

(defn map-select
  [data query]
  (let [ast (eql/query->ast query)]
    (map-select-from-ast ast data)))

(defn start-el
  [{::keys [drivers stats clock]
    :as    env}
   {::keys [input output driver requires provides id start] :as element}]
  (let [input-selection (eql-as/ident-query {::eql-as/as-map requires
                                             ::eql-as/as-key :pathom/as})
        output-selection (eql-as/ident-query {::eql-as/as-map provides
                                              ::eql-as/as-key :pathom/as})
        env-for-start (if requires
                        (merge env
                               (map-select env input-selection))
                        env)
        start (or start
                  (::start (get drivers driver))
                  (throw (if element
                           (-> (if driver
                                 (str "Can't find start function on driver " (pr-str driver) " required by element " (pr-str id))
                                 (str "Can't find start function on element " (pr-str id)))
                               (ex-info (dissoc element ::start))
                               (throw))
                           (throw (ex-info "Element is nil" {::id id})))))
        pre-start (Instant/now clock)
        env-from-start (start env-for-start)
        post-start (Instant/now clock)]
    (merge env
           (if provides
             (map-select env-from-start output-selection)
             env-from-start)
           {::stats (conj stats
                          {::pre-start  pre-start
                           ::id         id
                           ::post-start post-start})})))


(defn execute
  [{::keys [elements
            ids-in-start-order]
    :as    system}]
  (reduce start-el
          system
          (for [id ids-in-start-order]
            (assoc (get elements id)
              ::id id))))

(defn start
  ([{::keys [clock]
     :as    system}
    ids]
   (-> system
       (assoc
         ::clock (or clock (Clock/systemUTC))
         ::stats []
         ::ids-in-start-order (map ::id (elements-for-start system ids)))
       execute))
  ([{::keys [elements]
     :as    system}]
   (start system (keys elements))))

(defn elements-for-stop
  [{::keys [elements]}]
  (for [[id el] elements]
    (assoc el ::id id)))

(defn stop-el
  [{::keys [drivers]
    :as    env}
   {::keys [input output driver requires provides]}]
  (let [input-selection (eql-as/as-query {::eql-as/as-map provides
                                          ::eql-as/as-key :pathom/as})
        output-selection (eql-as/as-query {::eql-as/as-map requires
                                           ::eql-as/as-key :pathom/as})
        env-for-stop (if requires
                       (map-select env input-selection)
                       env)
        {::keys [stop]} (get drivers driver)
        env-from-stop (stop (merge env env-for-stop))]
    (apply dissoc
           (merge env
                  (if requires
                    (map-select env-from-stop output-selection)
                    env-from-stop))
           (keys provides))))

(defn stop
  [system]
  (reduce stop-el
          system
          (elements-for-stop system)))
