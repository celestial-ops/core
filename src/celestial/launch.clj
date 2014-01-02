(comment 
   Celestial, Copyright 2012 Ronen Narkis, narkisr.com
   Licensed under the Apache License,
   Version 2.0  (the "License") you may not use this file except in compliance with the License.
   You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.)

(ns celestial.launch
  "celestial lanching ground aka main"
  (:gen-class true)
  (:use 
    [clojure.core.strint :only (<<)]
    [clojure.tools.nrepl.server :only (start-server stop-server)]
    [celestial.persistency :as p]
    [gelfino.timbre :only (set-tid get-tid)]
    [celestial.ssl :only (generate-store)]
    [clojure.java.io :only (file resource)]
    [celestial.api :only (app)]
    [gelfino.timbre :only (gelf-appender)]
    [supernal.core :only [ssh-config]]
    [ring.adapter.jetty :only (run-jetty)] 
    [celestial.common :only (get! get* import-logging version)]
    [celestial.redis :only (clear-locks)]
    [taoensso.timbre :only (set-config! set-level!)])
  (:require 
    [celestial.persistency.migrations :as mg]
    [hypervisors.networking :refer [initialize-networking]]
    [celestial.jobs :as jobs]))

(import-logging)

(defn setup-logging 
  "Sets up logging configuration"
  []
  (let [log* (partial get* :celestial :log)]
    (when (log* :gelf)
      (set-config! [:appenders :gelf] gelf-appender) 
      (set-config! [:shared-appender-config :gelf] {:host (log* :gelf :host)})) 
    (set-config! [:shared-appender-config :spit-filename] (log* :path)) 
    (set-config! [:appenders :spit :enabled?] true) 
    (set-level! (log* :level))))

(defn clean-up []
  (debug "Shutting down...")
  (jobs/shutdown-workers)
  (jobs/clear-all)
  (clear-locks))

(defn add-shutdown []
  (.addShutdownHook (Runtime/getRuntime) (Thread. clean-up)))

(defn cert-conf 
  "Celetial cert configuration" 
  [k]
  (get! :celestial :cert k))

(defn default-key 
  "Generates a default keystore if missing" 
  []
  (when-not (.exists (file (cert-conf :keystore)))
    (info "generating a default keystore")
    (generate-store (cert-conf :keystore) (cert-conf :password))))

(defn start-nrepl
  []
  (when-let [port (get* :celestial :nrepl :port)]
    (info (<< "starting nrepl on port ~{port}"))
    (defonce server (start-server :port port))))

(def jetty (atom nil))

(defn setup 
  "one time setup" 
  []
  (initialize-networking)
  (setup-logging)
  (p/reset-admin)
  (mg/setup-migrations)
  (add-shutdown)
  (ssh-config {:key (get! :ssh :private-key-path) :user "root"} ) 
  (default-key)
  (start-nrepl)
  (run-jetty (app true)
     {:port (get! :celestial :port) :join? false
      :ssl? true :keystore (cert-conf :keystore)
      :key-password  (cert-conf :password)
      :ssl-port (get! :celestial :https-port)}))

(defn start 
  "main componenets startup (jetty, job workers etc..)"
  [jetty]
  (jobs/initialize-workers)
  (info (slurp (resource "main/resources/celestial.txt")))
  (info (<<  "version ~{version} see http://celestial-ops.com"))
   jetty
  )

(defn stop 
  "stopping the application"
  [jetty]
  (clean-up)
  (when jetty (.stop jetty)))

(defn -main [& args]
  (start (setup)))

(comment
  (setup)
  (start)
  (stop))
