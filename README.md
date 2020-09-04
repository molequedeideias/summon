# [WIP] net.molequedeideias/summon 
> Components with superpowers

It's a work-in-progress library. If you want to learn more about in, you can talk with me at [telegram](https://t.me/souenzzo) 

# Why another component system

It's not another component system. It use [com.stuartsierra/component](https://github.com/stuartsierra/component) interface
and some extra data, that is just ~4 keys in a map.

Extra things that are done:

- Load just what is needed for a target
- "statically" lint/inspect and find issues
- Avoid "get-in": everything is flat and named
- Easy to mock/replace/cache anything
- Built-in statitstics about timings
- Built-in graph plotter
- Do one thing, do well done:
  - graph path/calculations/plots are done by [ubergraph/ubergraph](https://github.com/Engelberg/ubergraph)
  - start/stop interfaces are the same from [com.stuartsierra/component](https://github.com/stuartsierra/component)
- It's already tested and used in a large project, with more then 30 elements
- Not all-in: you can use it in just some system/modules in your [com.stuartsierra/component](https://github.com/stuartsierra/component)

# Concepts:

## Local aliasing with requires

A you can map a "global value" into a "local value" for each component

```clojure
(def rest-api
  (with-meta {;; OPTIONAL: `input` will help will to detect missplaced keys
              ::summon/input [::jdbc-conn
                              ::port]}
             `{component/start (fn [{::keys [jdbc-conn port]}]
                                 ...)}))

(def system
  {:banana-jdbc-conn  ...
   :banana-port       ...
   :abacate-jdbc-conn ...
   :abacate-port      ...
   ::summon/elements  {:banana-rest-api  (assoc rest-api
                                           ::summon/requires {::jdbc-conn :banana-jdbc-conn
                                                              ::port      :banana-port})
                       :abacate-rest-api (assoc rest-api
                                           ::summon/requires {::jdbc-conn :abacate-jdbc-conn
                                                              ::port      :abacate-port})}})
```
With this code, we will start the `rest-api` component 2 times.
One with `banana` parameters, other with `abacate`.

## Global aliasing with provides 

Sometimes one component need a value returned by the other component.
For this we can use `::summon/provides`

```clojure
(def get-from-env
  (with-meta {::summon/input  [::key-name]
              ;; as `input`, `output` is just  a "extra metadata" that help you to find misplaced keywords
              ::summon/output [::key-value]}
             `{component/start ...}))

(def system
  {:banana-jdbc-conn     ...
   :banana-port-key-name ... ;; changed
   :abacate-jdbc-conn    ...
   :abacate-port         ...
   ::summon/elements     {:banana-port-env  (assoc get-from-env
                                              ::summon/requires {::key-name :banana-port-key-name}
                                              ::summon/provides {:banana-port ::key-value})
                          :banana-rest-api  (assoc rest-api
                                              ::summon/requires {::jdbc-conn :banana-jdbc-conna
                                                                 ::port      :banana-port})
                          :abacate-rest-api (assoc rest-api
                                              ::summon/requires {::jdbc-conn :abacate-jdbc-conn
                                                                 ::port      :abacate-port})}})
```

Summon will see that `:banana-rest-api` requires a key that isn't available in "global" env, but it's provided 
by another element. So it will always start `:banana-port-env` before `:banana-rest-api` and "export" the `::key-value`
as specificaded by `provided`

## Inline components

Sometimes your "component" is just a function that you need to run.
It will be used only in one place, so you can declare it in there.

```clojure
(def system
  {::summon/elements {:get-env          (with-meta {::summon/provides {:banana-jdbc-conn  :banana-jdbc-conn
                                                                       :banana-port       :banana-port
                                                                       :abacate-jdbc-conn :abacate-jdbc-conn
                                                                       :abacate-port      :abacate-port}}
                                                   `{component/start ~(fn [_]
                                                                        {:banana-jdbc-conn  (System/getenv ...)
                                                                         :banana-port       (System/getenv ...)
                                                                         :abacate-jdbc-conn (System/getenv ...)
                                                                         :abacate-port      (System/getenv ...)})})

                      :banana-rest-api  (assoc rest-api
                                          ::summon/requires {::jdbc-conn :banana-jdbc-conn
                                                             ::port      :banana-port})
                      :abacate-rest-api (assoc rest-api
                                          ::summon/requires {::jdbc-conn :abacate-jdbc-conn
                                                             ::port      :abacate-port})}})
```

## Just a component [WIP]

You should be able to start it as a component (or use inside a system)
```clojure
(-> system 
    (assoc ::summon/targets [:abacate-rest-api :banana-rest-api])
    summon/component
    component/start)
```

## "Smart" target system

Here you need to specify with modules you need to run.

In a system like this
```clojure
(def system
  {::summon/elements {:banana-env       (with-meta {::summon/provides {:banana-jdbc-conn :banana-jdbc-conn
                                                                       :banana-port      :banana-port}}
                                                   `{component/start ~(fn [_]
                                                                        {:banana-jdbc-conn (System/getenv ...)
                                                                         :banana-port      (System/getenv ...)})})
                      :abacate-env      (with-meta {::summon/provides {:abacate-jdbc-conn :abacate-jdbc-conn
                                                                       :abacate-port      :abacate-port}}
                                                   `{component/start ~(fn [_]
                                                                        {:abacate-jdbc-conn (System/getenv ...)
                                                                         :abacate-port      (System/getenv ...)})})

                      :banana-rest-api  (assoc rest-api
                                          ::summon/requires {::jdbc-conn :banana-jdbc-conn
                                                             ::port      :banana-port})
                      :abacate-rest-api (assoc rest-api
                                          ::summon/requires {::jdbc-conn :abacate-jdbc-conn
                                                             ::port      :abacate-port})}})
```

If you use `::summon/target [:banana-rest-api]`, it will never call `:abacate-env`. It can speed up your tests

Also, you can short-cut provide things, like

```clojure
(-> system
    (assoc :abacate-jdbc-conn ...
           :abacate-port ...
           ::summon/targets [:abacate-rest-api]))
```

This setup will **only** start the `:apacate-rest-api`, once everything that it need is already on `global`

It can be used to "cache" some components and turn your tests even faster 

## TDD/REPL oriented debug

If you misplace one require/provide or there is some missing provides for required values, a lint will warn you
```clojure
(summon/valid? system) ;; true/false
(summon/explain-data system) 
;; may return something like
;; [{::summon/issue ::summon/missing-global,
;;   ::summon/key   :banana-port}] 
```

If you have a large system, may be hard to know what is unused. So you can detect it:

```clojure
(summon/required-globals simple-system)
;; May return a list of "requires" that aren't provided by any "provides"
```

If you need to understand with module require each other, you can plot it

```clojure
(-> (summon/graph simple-system ::system)
    (uber/viz-graph))
```

Once `summon/graph` generate a ubergraph, you can write your custon lint over it, to ensure for exmaple,
that system X will not depend of anything from Y, using powerful graph operations
