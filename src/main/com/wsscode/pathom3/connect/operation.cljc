(ns com.wsscode.pathom3.connect.operation
  (:require
    [clojure.spec.alpha :as s]
    [com.fulcrologic.guardrails.core :refer [<- => >def >defn >fdef ? |]]
    [com.wsscode.misc.coll :as coll]
    [com.wsscode.misc.refs :as refs]
    [com.wsscode.pathom3.connect.operation.protocols :as pop]
    [com.wsscode.pathom3.format.eql :as pf.eql]
    [com.wsscode.pathom3.format.shape-descriptor :as pfsd])
  #?(:cljs
     (:require-macros
       [com.wsscode.pathom3.connect.operation])))

; region type predicates

(defn operation? [x] (satisfies? pop/IOperation x))
(defn resolver? [x] (satisfies? pop/IResolver x))
(defn mutation? [x] (satisfies? pop/IMutation x))

; endregion

; region specs

(>def ::op-name "Name of the operation" symbol?)
(>def ::input vector?)
(>def ::output vector?)
(>def ::params vector?)
(>def ::cache? boolean?)
(>def ::batch? boolean?)
(>def ::resolve fn?)
(>def ::mutate fn?)
(>def ::operation-type #{::operation-type-resolver ::operation-type-mutation})
(>def ::operation-config map?)
(>def ::operation operation?)
(>def ::resolver resolver?)
(>def ::mutation mutation?)
(>def ::provides ::pfsd/shape-descriptor)
(>def ::dynamic-name ::op-name)
(>def ::dynamic-resolver? boolean?)
(>def ::transform fn?)

; endregion

; region records

(defrecord Resolver [config resolve]
  pop/IOperation
  (-operation-config [_] config)
  (-operation-type [_] ::operation-type-resolver)

  pop/IResolver
  (-resolve [_ env input] (resolve env input))

  #?(:clj clojure.lang.IFn)
  #?(:clj (invoke [this] (resolve {} {})))
  #?(:clj (invoke [this input] (resolve {} input)))
  #?(:clj (invoke [this env input] (resolve env input)))

  #?(:cljs IFn)
  #?(:cljs (-invoke [this] (resolve {} {})))
  #?(:cljs (-invoke [this input] (resolve {} input)))
  #?(:cljs (-invoke [this env input] (resolve env input))))

(defrecord Mutation [config mutate]
  pop/IOperation
  (-operation-config [_] config)
  (-operation-type [_] ::operation-type-mutation)

  pop/IMutation
  (-mutate [_ env input] (mutate env input))

  #?(:clj clojure.lang.IFn)
  #?(:clj (invoke [this] (mutate {} {})))
  #?(:clj (invoke [this input] (mutate {} input)))
  #?(:clj (invoke [this env input] (mutate env input)))

  #?(:cljs IFn)
  #?(:cljs (-invoke [this] (mutate {} {})))
  #?(:cljs (-invoke [this input] (mutate {} input)))
  #?(:cljs (-invoke [this env input] (mutate env input))))

; endregion

; region constructors and helpers

(>defn operation-config [operation]
  [::operation => ::operation-config]
  (pop/-operation-config operation))

(>defn operation-type [operation]
  [::operation => ::operation-type]
  (pop/-operation-type operation))

(>defn resolver
  "Helper to create a resolver. A resolver have at least a name, the output definition
  and the resolve function.

  You can create a resolver using a map:

      (resolver
        {::op-name 'foo
         ::output  [:foo]
         ::resolve (fn [env input] ...)})

  Or with the helper syntax:

      (resolver 'foo {::output [:foo]} (fn [env input] ...))

  Returns an instance of the Resolver type.
  "
  ([op-name config resolve]
   [::op-name (s/keys :opt [::output ::params]) ::resolve => ::resolver]
   (resolver (-> config
                 (coll/merge-defaults {::op-name op-name})
                 (assoc ::resolve resolve))))
  ([{::keys [transform] :as config}]
   [(s/or :map (s/keys :req [::op-name] :opt [::output ::resolve ::transform])
          :resolver ::resolver)
    => ::resolver]
   (when-not (s/valid? (s/keys) config)
     (s/explain (s/keys) config)
     (throw (ex-info (str "Invalid config on defresolver " name)
                     {:explain-data (s/explain-data (s/keys) config)})))
   (if (resolver? config)
     config
     (let [{::keys [resolve output] :as config} (cond-> config transform transform)
           defaults (if output
                      {::input    []
                       ::provides (pfsd/query->shape-descriptor output)}
                      {})
           config'  (-> (merge defaults config)
                        (dissoc ::resolve ::transform))]
       (->Resolver config' (or resolve (fn [_ _])))))))

(>defn mutation
  "Helper to create a mutation. A mutation must have a name and the mutate function.

  You can create a mutation using a map:

      (mutation
        {::op-name 'foo
         ::output  [:foo]
         ::mutate  (fn [env params] ...)})

  Or with the helper syntax:

      (mutation 'foo {} (fn [env params] ...))

  Returns an instance of the Mutation type.
  "
  ([op-name config mutate]
   [::op-name (s/keys :opt [::output ::params]) ::mutate => ::mutation]
   (mutation (-> config
                 (coll/merge-defaults {::op-name op-name})
                 (assoc ::mutate mutate))))
  ([{::keys [transform] :as config}]
   [(s/or :map (s/keys :req [::op-name] :opt [::output ::resolve ::transform])
          :mutation ::mutation)
    => ::mutation]
   (when-not (s/valid? (s/keys) config)
     (s/explain (s/keys) config)
     (throw (ex-info (str "Invalid config on mutation " name)
                     {:explain-data (s/explain-data (s/keys) config)})))
   (if (mutation? config)
     config
     (let [{::keys [mutate output] :as config} (cond-> config transform transform)
           defaults (if output
                      {::provides (pfsd/query->shape-descriptor output)}
                      {})
           config'  (-> (merge defaults config)
                        (dissoc ::mutate ::transform))]
       (->Mutation config' (or mutate (fn [_ _])))))))

(>defn params
  "Pull parameters from environment. Always returns a map."
  [env]
  [map? => map?]
  (or (get-in env [:com.wsscode.pathom3.connect.planner/node
                   :com.wsscode.pathom3.connect.planner/params])
      {}))

(>defn with-node-params
  "Set current node params to params."
  ([params]
   [map? => map?]
   {:com.wsscode.pathom3.connect.planner/node
    {:com.wsscode.pathom3.connect.planner/params
     params}})

  ([env params]
   [map? map? => map?]
   (assoc-in env [:com.wsscode.pathom3.connect.planner/node
                  :com.wsscode.pathom3.connect.planner/params]
     params)))

; endregion

; region macros

#?(:clj
   (do
     (s/def ::simple-keys-binding
       (s/tuple #{:keys} (s/coll-of ident? :kind vector?)))

     (s/def ::qualified-keys-binding
       (s/tuple
         (s/and qualified-keyword? #(= (name %) "keys"))
         (s/coll-of simple-symbol? :kind vector?)))

     (s/def ::as-binding
       (s/tuple #{:as} simple-symbol?))

     (s/def ::map-destructure
       (s/every
         (s/or :simple-keys-binding ::simple-keys-binding
               :qualified-keys-bindings ::qualified-keys-binding
               :named-extract (s/tuple ::operation-argument keyword?)
               :as ::as-binding)
         :kind map?))

     (s/def ::operation-argument
       (s/or :sym symbol?
             :map ::map-destructure))

     (s/def ::operation-args
       (s/coll-of ::operation-argument :kind vector? :min-count 0 :max-count 2))

     (s/def ::defresolver-args
       (s/and
         (s/cat :name simple-symbol?
                :docstring (s/? string?)
                :arglist ::operation-args
                :options (s/? map?)
                :body (s/+ any?))
         (fn must-have-output-visible-map-or-options [{:keys [body options]}]
           (or (map? (last body)) options))))

     (s/def ::defmutation-args
       (s/and
         (s/cat :name simple-symbol?
                :docstring (s/? string?)
                :arglist ::operation-args
                :options (s/? map?)
                :body (s/+ any?)))))

   :cljs
   (s/def ::defresolver-args any?))

(defn as-entry? [x] (refs/kw-identical? :as (first x)))

(defn extract-destructure-map-keys-as-keywords [m]
  (into []
        (comp
          (remove as-entry?)
          (mapcat
            (fn [[k val]]
              (if (and (keyword? k)
                       (= "keys" (name k)))
                (map #(keyword (or (namespace %)
                                   (namespace k)) (name %)) val)
                [val]))))
        m))

(defn params->resolver-options [{:keys [arglist options body]}]
  (let [[input-type input-arg] (last arglist)
        last-expr (last body)]
    (cond-> options
      (and (map? last-expr) (not (::output options)))
      (assoc ::output (pf.eql/data->query last-expr))

      (and (refs/kw-identical? :map input-type)
           (not (::input options)))
      (assoc ::input (extract-destructure-map-keys-as-keywords input-arg)))))

(defn params->mutation-options [{:keys [arglist options body]}]
  (let [[input-type params-arg] (last arglist)
        last-expr (last body)]
    (cond-> options
      (and (map? last-expr) (not (::output options)))
      (assoc ::output (pf.eql/data->query last-expr))

      (and (refs/kw-identical? :map input-type)
           (not (::params options)))
      (assoc ::params (extract-destructure-map-keys-as-keywords params-arg)))))

(defn normalize-arglist
  "Ensures arglist contains two elements."
  [arglist]
  (loop [arglist arglist]
    (if (< (count arglist) 2)
      (recur (into '[[:sym _]] arglist))
      arglist)))

(defn full-symbol [sym ns]
  (if (namespace sym)
    sym
    (symbol ns (name sym))))

#?(:clj
   (defmacro defresolver
     "Defines a new Pathom resolver.

     Resolvers are the central abstraction around Pathom, a resolver is a function
     that contains some annotated information and follow a few rules:

     1. The resolver input must be a map, so the input information is labelled.
     2. A resolver must return a map, so the output information is labelled.
     3. A resolver also receives a separated map containing the environment information.

     Here are some examples of how you can use the defresolver syntax to define resolvers:

     The verbose example:

         (pco/defresolver song-by-id [env {:acme.song/keys [id]}]
           {::pco/input     [:acme.song/id]
            ::pco/output    [:acme.song/title :acme.song/duration :acme.song/tone]
            ::pco/params    []
            ::pco/transform identity}
           (fetch-song env id))

     The previous example demonstrates the usage of the most common options in defresolver.

     But we don't need to write all of that, for example, instead of manually saying
     the ::pco/input, we can let the defresolver infer it from the param destructuring, so
     the following code works the same (::pco/params and ::pco/transform also removed, since
     they were no-ops in this example):

         (pco/defresolver song-by-id [env {:acme.song/keys [id]}]
           {::pco/output [:acme.song/title :acme.song/duration :acme.song/tone]}
           (fetch-song env id))

     This makes for a cleaner write, now lets use this format and write a new example
     resolver:

         (pco/defresolver full-name [env {:acme.user/keys [first-name last-name]}]
           {::pco/output [:acme.user/full-name]}
           {:acme.user/full-name (str first-name \" \" last-name)})

     The first thing we see is that we don't use env, so we can omit it.

         (pco/defresolver full-name [{:acme.user/keys [first-name last-name]}]
           {::pco/output [:acme.user/full-name]}
           {:acme.user/full-name (str first-name \" \" last-name)})

     Also, when the last expression of the defresolver is a map, it will infer the output
     shape from it:

         (pco/defresolver full-name [{:acme.user/keys [first-name last-name]}]
           {:acme.user/full-name (str first-name \" \" last-name)})

     You can always override the implicit input and output by setting on the configuration
     map.

     Standard options:

       ::pco/output - description of resolver output, in EQL format
       ::pco/input - description of resolver input, in EQL format
       ::pco/params - description of resolver parameters, in EQL format
       ::pco/transform - a function to transform the resolver configuration before instantiating the resolver
       ::pcr/cache? - true by default, set to false to disable caching for the resolver

     Note that any other option that you send to the resolver config will be stored in the
     index and can be read from it at any time.

     The returned value is of the type Resolver, you can test your resolver by calling
     directly:

         (full-name {:acme.user/first-name \"Ada\"
                     :acme.user/last-name  \"Lovelace\"})
         => \"Ada Lovelace\"

     Note that similar to the way we define the resolver, we can also omit `env` (and even
     the input) when calling, the resolvers fns always support arity 0, 1 and 2.
     "
     {:arglists '([name docstring? arglist options? & body])}
     [& args]
     (let [{:keys [name docstring arglist body] :as params}
           (-> (s/conform ::defresolver-args args)
               (update :arglist normalize-arglist))

           arglist' (s/unform ::operation-args arglist)
           fqsym    (full-symbol name (str *ns*))
           defdoc   (cond-> [] docstring (conj docstring))]
       `(def ~name
          ~@defdoc
          (resolver '~fqsym ~(params->resolver-options params)
                    (fn ~name ~arglist'
                      ~@body))))))

#?(:clj
   (s/fdef defresolver
     :args ::defresolver-args
     :ret any?))

#?(:clj
   (defmacro defmutation
     "Defines a new Pathom mutation. The syntax of this macro is similar to defresolver,
     But where `defresolver` takes input, `defmutation` uses as ::params."
     {:arglists '([name docstring? arglist options? & body])}
     [& args]
     (let [{:keys [name docstring arglist body] :as params}
           (-> (s/conform ::defmutation-args args)
               (update :arglist normalize-arglist))

           arglist' (s/unform ::operation-args arglist)
           fqsym    (full-symbol name (str *ns*))
           defdoc   (cond-> [] docstring (conj docstring))]
       `(def ~name
          ~@defdoc
          (mutation '~fqsym ~(params->mutation-options params)
                    (fn ~name ~arglist'
                      ~@body))))))

#?(:clj
   (s/fdef defmutation
     :args ::defmutation-args
     :ret any?))

; endregion
