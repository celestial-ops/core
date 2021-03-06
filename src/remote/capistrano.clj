(comment 
   re-core, Copyright 2012 Ronen Narkis, narkisr.com
   Licensed under the Apache License,
   Version 2.0  (the "License") you may not use this file except in compliance with the License.
   You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.)

(ns remote.capistrano
  (:require
    [clojure.core.strint :refer  (<<)]
    [slingshot.slingshot :refer [throw+]]
    [clojure.java.shell :refer (with-sh-dir)]
    [supernal.sshj :refer (copy sh- dest-path)]
    [re-core.common :refer (import-logging gen-uuid interpulate)]
    [clojure.java.shell :refer [sh]]
    [me.raynes.fs :refer (delete-dir exists? mkdirs tmpdir)]
    [re-core.core :refer (Remoter)]
    [re-core.model :refer (rconstruct)]))

(import-logging)

;; A capistrano remote agent
(defrecord Capistrano [src args dst timeout]
  Remoter
  (setup [this] 
         (when (exists? (dest-path src dst)) 
           (throw+ {:type ::old-code :message "Old code found in place, cleanup first"})) 
         (mkdirs dst) 
         (copy src dst {})
         (try 
           (sh- "cap" "-T" {:dir (dest-path src dst)})
           (catch Throwable e
             (error e)
             (throw+ {:type ::cap-sanity-failed :message "Failed to run Capistrano sanity"}))))
  (run [this]
       (info (dest-path src dst))
       (apply sh- "cap" (conj args {:dir (dest-path src dst) :timeout timeout})))
  (cleanup [this]
           (delete-dir dst)))

(defmethod rconstruct :capistrano [{:keys [capistrano name src] :as action} 
                                   {:keys [env] :as run-info}]
  (let [{:keys [args timeout]} (capistrano env)]
    (assert (and (:target run-info) (:hostname run-info)))
    (->Capistrano src (mapv #(interpulate % run-info) args) (<< "~(tmpdir)/~(gen-uuid)/~{name}") timeout)))


