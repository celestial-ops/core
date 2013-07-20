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

(ns celestial.redis
  "Redis utilities like a distributed lock, connection managment and ref watcher"
  (:use  
    [flatland.useful.map :on map-vals]
    [clojure.set :only (difference)]
    [flatland.useful.utils :only (defm)]
    [celestial.common :only (get! curr-time gen-uuid half-hour minute)]
    [clojure.core.strint :only (<<)]
    [slingshot.slingshot :only  [throw+]]
    [taoensso.carmine.locks :only (with-lock)]
    [taoensso.timbre :only (debug trace info error warn)])
  (:require  
    [taoensso.nippy :as nippy]
    [clojure.data.json :as json]
    [taoensso.carmine.message-queue :as carmine-mq]
    [taoensso.carmine :as car])
  (:import java.util.Date))

(defm server-conn [] {:pool {} :spec {:host (get! :redis :host)}})

(defmacro wcar [& body] 
   `(try 
       (car/wcar (server-conn) ~@body)
       (catch Exception e# 
         (error e#)
         #_(throw+ {:type ::redis:connection :redis-host (get! :redis :host)} "Redis connection error")
         )))


(defn get- [k] (wcar (car/get k)))

; TODO this should be enabled in upstream
(def ^:private lkey (partial car/kname "carmine" "lock"))

(defn clear-locks []
  (trace "clearing locks")
  (when-let [lkeys (seq (wcar (car/keys (lkey "*"))))]
    (wcar (apply car/del lkeys))))

(defn create-worker [name f]
  (carmine-mq/worker (server-conn) name {:handler f :eoq-backoff-ms 200}))

(defn missing-keys [rk m]
  (difference (into #{} (map keyword (car/hkeys rk))) (into #{} (keys m))))

(defn hsetall* [rk m & [missing]]
  "The reverse action of hgetall*, missing keys that were removed"
    (when missing (wcar (doseq [d missing] (car/hdel rk d))))
    (apply car/hmset rk (flatten (into [] (map-vals m car/freeze)))) )

(defn clear-all []
  (wcar (car/flushdb)))
