(ns clojure.core.typed.load1
  "Implementation of clojure.core.typed.load."
  (:require [clojure.core.typed :as t]
            [clojure.core.typed.errors :as err]
            [clojure.core.typed.ns-deps-utils :as ns-utils]
            [clojure.core.typed.analyze-clj :as ana-clj]
            [clojure.tools.analyzer.env :as ta-env]
            [clojure.core.typed.current-impl :as impl]
            [clojure.tools.reader.reader-types :as readers]
            [clojure.tools.reader :as reader]
            [clojure.java.io :as io]
            [clojure.core.typed.profiling :as p]
            [clojure.core.typed.check-form-clj :as chk-frm-clj]
            [clojure.core.typed.check-form-common :as chk-frm]
            [clojure.core.typed.lang :as lang]
            [clojure.tools.analyzer.jvm :as taj]
            [clojure.core.typed.util-vars :as vs])
  (:import java.net.URL))

;; based on clojure.tools.analyzer.jvm/analyze-ns
;; (IFn [String -> nil]
;;      [String ToolsAnalyzerEnv -> nil]
;;      [String ToolsAnalyzerEnv ToolsReaderOpts -> nil])
(defn load-typed-file
  "Loads a whole typed namespace, returns nil. Assumes the file is typed."
  ([filename] (load-typed-file filename (taj/empty-env) {}))
  ([filename env] (load-typed-file filename env {}))
  ([filename env opts]
   {:pre [(string? filename)]
    :post [(nil? %)]}
    (t/load-if-needed)
    (ta-env/ensure (p/p :typed-load/global-env
                        (taj/global-env))
     (let [[file-url filename]
           (or (let [f (str filename ".clj")]
                 (when-let [r (io/resource f)]
                   [r f]))
               (let [f (str filename ".cljc")]
                 (when-let [r (io/resource f)]
                   [r f])))]
       (assert file-url (str "Cannot find file " filename))
       (binding [*ns*   *ns*
                 *file* filename
                 vs/*in-typed-load* true]
         (with-open [rdr (io/reader file-url)]
           (let [pbr (readers/indexing-push-back-reader
                       (java.io.PushbackReader. rdr) 1 filename)
                 eof (Object.)
                 opts {:eof eof :features #{:clj :t.a.jvm}}
                 opts (if (.endsWith ^String filename "cljc")
                        (assoc opts :read-cond :allow)
                        opts)
                 config (assoc (chk-frm-clj/config-map)
                               :env env)]
             (impl/with-full-impl (:impl config)
               (loop []
                 (let [form (p/p :typed-load/read (reader/read opts pbr))]
                   (when-not (identical? form eof)
                     (let [{:keys [ex]} (p/p :typed-load/check-form
                                             (chk-frm/check-form-info config form))]
                       (when ex
                         (throw ex)))
                     ;(ana-clj/analyze+eval form (assoc env :ns (ns-name *ns*)) opts)
                     (recur))))))))))))

(defn typed-load1
  "Checks if the given file is typed, and loads it with core.typed if so,
  otherwise with clojure.core/load"
  [base-resource-path]
  {:pre [(string? base-resource-path)]
   :post [(nil? %)]}
  ;(prn "typed load" base-resource-path)
  (cond
    (or (ns-utils/file-should-use-typed-load? (str base-resource-path ".clj"))
        (ns-utils/file-should-use-typed-load? (str base-resource-path ".cljc")))
    (do
      (when @#'clojure.core/*loading-verbosely*
        (printf "Loading typed file\n" base-resource-path))
      (load-typed-file base-resource-path))

    :else (clojure.lang.RT/load base-resource-path)))

(defn typed-eval [form]
  (let [{:keys [ex result]} (t/check-form-info form)]
    (if ex
      (throw ex)
      result)))

(defn install-typed-load
  "Extend the :lang dispatch table with the :core.typed language"
  []
  {:post [(nil? %)]}
  (alter-var-root #'lang/lang-dispatch
                  (fn [m]
                    (-> m 
                        (assoc-in [:core.typed :load] #'typed-load1)
                        (assoc-in [:core.typed :eval] #'typed-eval))))
  nil)

(defn monkey-patch-typed-load
  "Install the :core.typed :lang, and monkey patch `load`"
  []
  {:post [(nil? %)]}
  (install-typed-load)
  (lang/monkey-patch-extensible-load)
  nil)

(defn monkey-patch-typed-eval
  "Install the :core.typed :lang, and monkey patch `eval`"
  []
  {:post [(nil? %)]}
  (install-typed-load)
  (lang/monkey-patch-extensible-eval)
  nil)

(defn install 
  "Install the :core.typed :lang. Takes an optional set of features
  to install, defaults to #{:load :eval}.

  Features:
    - :load    Installs typed `load` over `clojure.core/load`
    - :eval    Installs typed `eval` over `clojure.core/eval`

  eg. (install)            ; installs `load` and `eval`
  eg. (install #{:eval})   ; installs `eval`
  eg. (install #{:eval})   ; installs `load`"
  ([] (install :all))
  ([features]
   {:pre [((some-fn set? #{:all}) features)]
    :post [(nil? %)]}
   (lang/install features)
   (when (or (= features :all)
             (:load features))
     (monkey-patch-typed-load))
   (when (or (= features :all)
             (:eval features))
     (monkey-patch-typed-eval))
   nil))

(comment (find-resource "clojure/core/typed/test/load_file.clj")
         (typed-load "/clojure/core/typed/test/load_file.clj")
         (load "/clojure/core/typed/test/load_file")
         (require 'clojure.core.typed.test.load-file :reload :verbose)
         )
