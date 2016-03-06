(ns ringless.core
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.java.shell :as sh]
            [bidi.bidi :as bidi]
            [bidi.ring :as br]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [schema.core :as sc]))

(sc/defschema BuildConfig
  {:web-prefix sc/Str

   :dev {:less-classpath-prefix sc/Str
         :less-filename sc/Str
         (sc/optional-key :lessjs-version) sc/Str
         (sc/optional-key :lessjs-opts) (sc/maybe {sc/Any sc/Any})
         (sc/optional-key :lessjs-watch?) (sc/maybe sc/Bool)}

   :build {(sc/optional-key :lessc-optsv) (sc/maybe [sc/Str])
           :build-path sc/Str
           :classpath-prefix sc/Str
           :build-filename sc/Str}})

(defn- get-built-resource [{{:keys [classpath-prefix build-filename]} :build}]
  (io/resource (s/join "/" [classpath-prefix build-filename])))

(defn- less-dir [{{:keys [less-classpath-prefix]} :dev}]
  (io/as-file (io/resource less-classpath-prefix)))

(defn- web-routes [{:keys [web-prefix],
                    {:keys [classpath-prefix]} :build,
                    :as build-config}]

  [web-prefix (if-let [built-resource (get-built-resource build-config)]
                (br/resources-maybe {:prefix classpath-prefix})
                (-> (br/files {:dir (.getPath (less-dir build-config))
                               :mime-types {"less" "text/css"}})

                    (br/wrap-middleware (fn [handler]
                                          (fn [req]
                                            (when-let [resp (handler req)]
                                              (-> resp
                                                  (assoc-in [:headers :cache-control] "must-revalidate"))))))))])

(sc/defn style-handler [build-config :- BuildConfig]
  (br/make-handler (web-routes build-config)))

(defn async-middleware [handler]
  (fn [req >resp]
    (handler req (fn [resp]
                   (>resp resp)))))

(sc/defn include-style :- sc/Str
  [{:keys [web-prefix lessjs-version], :or {lessjs-version "2.6.1"}
    {:keys [lessjs-version lessjs-watch? less-path lessjs-opts less-filename]} :dev,
    {:keys [build-filename]} :build
    :as build-config} :- BuildConfig]

  (if (get-built-resource build-config)
    (format "<link rel=\"stylesheet\" type=\"text/css\" href=\"%s\" />"
            (s/join "/" [web-prefix build-filename]))

    (s/join "\n" (concat [(format "<script>less = %s;</script>" (json/encode (merge {:env "development"} lessjs-opts)))
                          (format "<link rel=\"stylesheet/less\" type=\"text/css\" href=\"%s\" />"
                                  (s/join "/" [web-prefix less-filename]))
                          (format "<script src=\"//cdnjs.cloudflare.com/ajax/libs/less.js/2.6.1/less.min.js\"></script>"
                                  lessjs-version)]

                         (when-not (false? lessjs-watch?)
                           ["<script>less.watch();</script>"])))))

(sc/defn build-less! [{{:keys [less-filename]} :dev,
                       {:keys [lessc-optsv build-path classpath-prefix build-filename]} :build,
                       :as build-config} :- BuildConfig]

  (let [less-main (io/file (less-dir build-config) less-filename)
        css-main (doto (io/file build-path classpath-prefix build-filename)
                   io/make-parents)]

    (log/infof "Compiling LESS: '%s' to '%s'..." (.getAbsolutePath less-main) (.getAbsolutePath css-main))

    (let [{:keys [exit out err]} (apply sh/sh (concat ["lessc"] lessc-optsv [(.getPath less-main) (.getPath css-main)]))]

      (if (pos? exit)
        (throw (ex-info "Failed compiling LESS"
                        {:out out
                         :err err}))

        (log/info "Compiled LESS.")))))
