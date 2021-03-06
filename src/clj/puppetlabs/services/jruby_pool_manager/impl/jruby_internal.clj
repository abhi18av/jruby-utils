(ns puppetlabs.services.jruby-pool-manager.impl.jruby-internal
  (:require [schema.core :as schema]
            [puppetlabs.services.jruby-pool-manager.jruby-schemas :as jruby-schemas]
            [clojure.tools.logging :as log]
            [puppetlabs.i18n.core :as i18n])
  (:import (com.puppetlabs.jruby_utils.pool JRubyPool)
           (puppetlabs.services.jruby_pool_manager.jruby_schemas JRubyInstance PoisonPill
                                                                 ShutdownPoisonPill)
           (org.jruby CompatVersion Main RubyInstanceConfig RubyInstanceConfig$CompileMode)
           (org.jruby.embed LocalContextScope)
           (java.util.concurrent TimeUnit)
           (clojure.lang IFn)
           (com.puppetlabs.jruby_utils.jruby InternalScriptingContainer
                                             ScriptingContainer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(def default-jruby-1-7-compat-version
  "Default value for JRuby's 'CompatVersion' setting.  This value is only
  meaningful for JRuby 1.7.  For JRuby 9k, this will return `nil` because
  JRuby 9k effectively doesn't support configurable language compatibility
  versions."
  (when-not jruby-schemas/using-jruby-9k? CompatVersion/RUBY1_9))

(schema/defn ^:always-validate initialize-gem-path :- {schema/Keyword schema/Any}
  [{:keys [gem-path gem-home] :as jruby-config} :- {schema/Keyword schema/Any}]
  (if gem-path
    jruby-config
    (assoc jruby-config :gem-path nil)))

(defn instantiate-free-pool
  "Instantiate a new queue object to use as the pool of free JRuby's."
  [size]
  {:post [(instance? jruby-schemas/pool-queue-type %)]}
  (JRubyPool. size))

(schema/defn ^:always-validate get-compile-mode :- RubyInstanceConfig$CompileMode
  [config-compile-mode :- jruby-schemas/SupportedJRubyCompileModes]
  (case config-compile-mode
    :jit RubyInstanceConfig$CompileMode/JIT
    :force RubyInstanceConfig$CompileMode/FORCE
    :off RubyInstanceConfig$CompileMode/OFF))

(schema/defn ^:always-validate init-jruby :- jruby-schemas/ConfigurableJRuby
  "Applies configuration to a JRuby... thing.  See comments in `ConfigurableJRuby`
  schema for more details."
  [jruby :- jruby-schemas/ConfigurableJRuby
   config :- jruby-schemas/JRubyConfig]
  (let [{:keys [ruby-load-path compile-mode lifecycle]} config
        initialize-scripting-container-fn (:initialize-scripting-container lifecycle)]
    (doto jruby
      (.setLoadPaths ruby-load-path)
      (.setCompileMode (get-compile-mode compile-mode)))
    (when-let [compat-version default-jruby-1-7-compat-version]
      (.setCompatVersion jruby compat-version))
    (initialize-scripting-container-fn jruby config)))

(schema/defn ^:always-validate empty-scripting-container :- ScriptingContainer
  "Creates a clean instance of a JRuby `ScriptingContainer` with no code loaded."
  [config :- jruby-schemas/JRubyConfig]
  (-> (InternalScriptingContainer. LocalContextScope/SINGLETHREAD)
      (init-jruby config)))

(schema/defn ^:always-validate create-scripting-container :- ScriptingContainer
  "Creates an instance of `org.jruby.embed.ScriptingContainer`."
  [config :- jruby-schemas/JRubyConfig]
  ;; for information on other legal values for `LocalContextScope`, there
  ;; is some documentation available in the JRuby source code; e.g.:
  ;; https://github.com/jruby/jruby/blob/1.7.11/core/src/main/java/org/jruby/embed/LocalContextScope.java#L58
  ;; I'm convinced that this is the safest and most reasonable value
  ;; to use here, but we could potentially explore optimizations in the future.
  (doto (empty-scripting-container config)
    ;; As of JRuby 1.7.20 (and the associated 'jruby-openssl' it pulls in),
    ;; we need to explicitly require 'jar-dependencies' so that it is used
    ;; to manage jar loading.  We do this so that we can instruct
    ;; 'jar-dependencies' to not actually load any jars.  See the environment
    ;; variable configuration in 'init-jruby-config' for more
    ;; information.
    (.runScriptlet "require 'jar-dependencies'")))

(schema/defn borrow-with-timeout-fn :- jruby-schemas/JRubyInternalBorrowResult
  [timeout :- schema/Int
   pool :- jruby-schemas/pool-queue-type]
  (.borrowItemWithTimeout pool timeout TimeUnit/MILLISECONDS))

(schema/defn insert-shutdown-poison-pill
  [pool :- jruby-schemas/pool-queue-type]
  (.insertPill pool (ShutdownPoisonPill. pool)))

(schema/defn insert-poison-pill
  [pool :- jruby-schemas/pool-queue-type
   error :- Throwable]
  (.insertPill pool (PoisonPill. error)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public

(schema/defn ^:always-validate
  create-pool-from-config :- jruby-schemas/PoolState
  "Create a new PoolState based on the config input."
  [{size :max-active-instances} :- jruby-schemas/JRubyConfig]
  {:pool (instantiate-free-pool size)
   :size size})

(schema/defn ^:always-validate
  cleanup-pool-instance!
  "Cleans up and cleanly terminates a JRubyInstance and removes it from the pool."
  [{:keys [scripting-container] :as instance} :- JRubyInstance
   cleanup-fn :- IFn]
  (let [pool (get-in instance [:internal :pool])]
    (.unregister pool instance)
    (cleanup-fn instance)
    (.terminate scripting-container)
    (log/infof (i18n/trs "Cleaned up old JRubyInstance with id {0}."
                         (:id instance)))))

(schema/defn ^:always-validate
  create-pool-instance! :- JRubyInstance
  "Creates a new JRubyInstance and adds it to the pool."
  [pool :- jruby-schemas/pool-queue-type
   id :- schema/Int
   config :- jruby-schemas/JRubyConfig
   flush-instance-fn :- IFn]
  (let [{:keys [ruby-load-path lifecycle]} config
        initialize-pool-instance-fn (:initialize-pool-instance lifecycle)]
    (when-not ruby-load-path
      (throw (Exception.
              (i18n/trs "JRuby service missing config value 'ruby-load-path'"))))
    (log/infof (i18n/trs "Creating JRubyInstance with id {0}." id))
    (let [scripting-container (create-scripting-container
                               config)]
      (let [instance (jruby-schemas/map->JRubyInstance
                      {:scripting-container scripting-container
                       :id id
                       :internal {:pool pool
                                  :max-borrows (:max-borrows-per-instance config)
                                  :flush-instance-fn flush-instance-fn
                                  :state (atom {:borrow-count 0})}})
            modified-instance (initialize-pool-instance-fn instance)]
        (.register pool modified-instance)
        modified-instance))))

(schema/defn ^:always-validate
  get-pool-state-container :- jruby-schemas/PoolStateContainer
  "Gets the PoolStateContainer from the pool context."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:internal :pool-state]))

(schema/defn ^:always-validate
  get-pool-state :- jruby-schemas/PoolState
  "Gets the PoolState from the pool context."
  [context :- jruby-schemas/PoolContext]
  @(get-pool-state-container context))

(schema/defn ^:always-validate
  get-pool :- jruby-schemas/pool-queue-type
  "Gets the JRuby pool object from the pool context."
  [context :- jruby-schemas/PoolContext]
  (:pool (get-pool-state context)))

(schema/defn ^:always-validate
  get-pool-size :- schema/Int
  "Gets the size of the JRuby pool from the pool context."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:config :max-active-instances]))

(schema/defn ^:always-validate
  get-flush-timeout :- schema/Int
  "Gets the size of the JRuby pool from the pool context."
  [context :- jruby-schemas/PoolContext]
  (get-in context [:config :flush-timeout]))

(schema/defn ^:always-validate
  get-instance-state-container :- jruby-schemas/JRubyInstanceStateContainer
  "Gets the InstanceStateContainer (atom) from the instance."
  [instance :- JRubyInstance]
  (get-in instance [:internal :state]))

(schema/defn borrow-without-timeout-fn :- jruby-schemas/JRubyInternalBorrowResult
  [pool :- jruby-schemas/pool-queue-type]
  (.borrowItem pool))

(schema/defn borrow-from-pool!* :- jruby-schemas/JRubyBorrowResult
  "Given a borrow function and a pool, attempts to borrow a JRubyInstance from a pool.
  If successful, updates the state information and returns the JRubyInstance.
  Returns nil if the borrow function returns nil; throws an exception if
  the borrow function's return value indicates an error condition."
  [borrow-fn :- (schema/pred ifn?)
   pool :- jruby-schemas/pool-queue-type]
  (let [instance (borrow-fn pool)]
    (cond (instance? PoisonPill instance)
          (do
            (.releaseItem pool instance)
            (throw (IllegalStateException.
                    (i18n/tru "Unable to borrow JRubyInstance from pool")
                    (:err instance))))

          (jruby-schemas/jruby-instance? instance)
          instance

          (jruby-schemas/shutdown-poison-pill? instance)
          instance

          (nil? instance)
          instance

          :else
          (throw (IllegalStateException.
                  (i18n/tru "Borrowed unrecognized object from pool!: {0}"
                            instance))))))

(schema/defn ^:always-validate
  borrow-from-pool :- jruby-schemas/JRubyInstanceOrPill
  "Borrows a JRuby interpreter from the pool. If there are no instances
  left in the pool then this function will block until there is one available."
  [pool-context :- jruby-schemas/PoolContext]
  (borrow-from-pool!* borrow-without-timeout-fn
                      (get-pool pool-context)))

(schema/defn ^:always-validate
  borrow-from-pool-with-timeout :- jruby-schemas/JRubyBorrowResult
  "Borrows a JRuby interpreter from the pool, like borrow-from-pool but a
  blocking timeout is provided. If an instance is available then it will be
  immediately returned to the caller, if not then this function will block
  waiting for an instance to be free for the number of milliseconds given in
  timeout. If the timeout runs out then nil will be returned, indicating that
  there were no instances available."
  [pool-context :- jruby-schemas/PoolContext
   timeout :- schema/Int]
  {:pre  [(>= timeout 0)]}
  (borrow-from-pool!* (partial borrow-with-timeout-fn timeout)
                      (get-pool pool-context)))

(schema/defn ^:always-validate
  return-to-pool
  "Return a borrowed pool instance to its free pool.
  Also check if the borrow count has exceeded, and flush it if needed.
  If the instance is not a JRubyInstance, it must be a poison pill, in
  which case this function is a noop"
  [instance :- jruby-schemas/JRubyInstanceOrPill]
  (when (jruby-schemas/jruby-instance? instance)
    (let [new-state (swap! (get-instance-state-container instance)
                           update-in [:borrow-count] inc)
          {:keys [max-borrows flush-instance-fn pool]} (:internal instance)]
      (if (and (pos? max-borrows)
               (>= (:borrow-count new-state) max-borrows))
        (do
          (log/infof
           (i18n/trs "Flushing JRubyInstance {0} because it has exceeded the maximum number of borrows ({1})"
                     (:id instance)
                     max-borrows))
          (flush-instance-fn instance))
        (.releaseItem pool instance)))))

(schema/defn ^:always-validate new-main :- jruby-schemas/JRubyMain
  "Return a new JRubyMain instance which should only be used for CLI purposes,
  e.g. for the ruby, gem, and irb subcommands.  Internal core services should
  use `create-scripting-container` instead of `new-main`."
  [config :- jruby-schemas/JRubyConfig]
  (let [jruby-config (init-jruby
                      (RubyInstanceConfig.)
                      config)]
    (Main. jruby-config)))
