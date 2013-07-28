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

(ns vc.guest
  (:import 
    com.vmware.vim25.NamePasswordAuthentication
    com.vmware.vim25.GuestProgramSpec
    com.vmware.vim25.GuestFileAttributes) 
  (:require 
    [clj-http.client :as client])
  (:use 
    [clojure.string :only (join split)]
    [slingshot.slingshot :only  [throw+]]
    [celestial.provider :only (wait-for)]
    [celestial.common :only (gen-uuid import-logging)]
    [clostache.parser :only (render-resource)] 
    [clojure.core.strint :only (<<)]
    [clojure.java.io :only (copy file reader)]
    [vc.vijava :only (with-service tools-installed? guest-status service find-vm)]))

(import-logging)

(defn- npa
   "converts auth map to new pass auth" 
   [{:keys [user password] }]
   {:pre [user password]}
  (doto (NamePasswordAuthentication.) (.setUsername user) (.setPassword password)))

(defn- manager 
  "file manage of vm" 
  [hostname t]
  (let [manager (.getGuestOperationsManager service)]
    (case t
      :file (.getFileManager manager (find-vm hostname))
      :proc  (.getProcessManager manager (find-vm hostname))
      )))

(defn upload-file  
  "uploads a file/string into a guest system" 
  [input dst hostname auth]
  {:pre [(tools-installed? hostname)]}
  (with-service 
    (let [url (.initiateFileTransferToGuest (manager hostname :file) (npa auth) dst (GuestFileAttributes.) (.length input) true)]
      (client/put url {:body input :insecure? true}))))

(defn download-file  
  "download a file from a guest system returns a stream" 
  [src hostname auth]
  {:pre [(tools-installed? hostname)]}
  (with-service 
    (let[{:keys [url]} (bean (.initiateFileTransferFromGuest (manager hostname :file) (npa auth) src))]
      (:body (client/get url {:as :stream :insecure? true})))))

(defn exit-code 
   "returns a remote pid exit status" 
   [m pid auth]
   (-> (.listProcessesInGuest m (npa auth) (long-array [pid])) first bean :exitCode))

(defn prog-spec [cmd args* {:keys [sudo] :as auth}]
  (if sudo 
     (doto (GuestProgramSpec.) (.setProgramPath "/usr/bin/sudo") (.setArguments (str cmd " " args*))) 
     (doto (GuestProgramSpec.) (.setProgramPath cmd) (.setArguments args*)))) 

(defn guest-run  
  "Runs a remote command using out of band using guest access, 
    wait - how much to wait before logging output
    uuid - the log file uuid, if missing no logging will take place"
  [hostname cmd args auth uuid timeout]
  {:pre [(= (guest-status hostname) :running)]}
  (with-service
    (let [m (manager hostname :proc) 
          args* (if uuid (<< "~{args} >> /tmp/run-~{uuid}.log") args) 
          pid (.startProgramInGuest m (npa auth) (prog-spec cmd args* auth))]
         (wait-for {:timeout timeout :sleep [200 :ms]} #(-> (exit-code m pid auth) nil? not) 
           {:type ::vc:guest-run-timeout :message (<< "Timed out on running ~{cmd} ~{args} in guest") :timeout timeout})
         (when-not (= (exit-code m pid auth) 0)
           (throw+ {:type ::vc:guest-run-fail :message (<< "Failed running ~{cmd} ~{args} in guest") :timeout timeout})))))

(defn fetch-log 
   "fetched remote log file by uuid"
   [hostname uuid auth]
  (slurp (download-file (<< "/tmp/run-~{uuid}.log") hostname auth)))

(defn set-ip 
  "set guest static ip" 
  [hostname auth config]
  (let [uuid (gen-uuid) tmp-file (<< "/tmp/intrefaces_~{uuid}")]
    (debug "setting up guest static ip")
    (upload-file (render-resource "static-ip.mustache" (update-in config [:names] (partial join ","))) tmp-file hostname auth)
    (guest-run hostname "/bin/cp" (<< "-v ~{tmp-file} /etc/network/interfaces") auth uuid [2 :seconds])
    (guest-run hostname "/usr/sbin/service" "networking restart" auth uuid [3 :seconds])
    (guest-run hostname "/bin/rm" (<< "-v ~{tmp-file}") auth uuid [2 :seconds])
    (guest-run hostname "echo" (<< "'kernel.hostname = ~{hostname}' | sudo tee -a /etc/sysctl.conf") auth uuid [2 :seconds])
    (guest-run hostname "sysctl" "-e -p" auth uuid [2 :seconds])
    (doseq [line (split (fetch-log hostname uuid auth) #"\n")] (debug line))))

(comment
  (set-ip "red1" {:user "ronen" :password "foobar" :sudo true} 
     {:ip "192.168.5.91" :mask "255.255.255.0" :network "192.168.5.0" :gateway "192.168.5.1" :search "local" :names ["192.168.5.1"]}) 
  (download-file "/tmp/project.clj" "/tmp/project.clj" "foo" {:user "root" :pass "foobar"})
  (upload-file "project.clj" "/tmp/project.clj" "red1" {:user "ronen" :pass "foobar"}) 
  (download-file "/tmp/project.clj" "/tmp/project.clj" "foo" {:user "ronen" :pass "foobar"}) 
  (tools-installed? "foo")) 