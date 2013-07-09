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

(ns aws.provider
  (:require [aws.sdk.ec2 :as ec2])
  (:import (java.util UUID))
  (:require [celestial.persistency :as p]
            [celestial.validations :as cv])
  (:use 
    [aws.validations :only (provider-validation)]
    [bouncer [core :as b] [validators :as v]]
    [clojure.core.strint :only (<<)]
    [supernal.sshj :only (execute ssh-up?)]
    [flatland.useful.utils :only (defm)]
    [flatland.useful.map :only (dissoc-in*)]
    [slingshot.slingshot :only  [throw+ try+]]
    [aws.sdk.ec2 :only 
     (run-instances describe-instances terminate-instances start-instances
                     create-tags stop-instances instance-filter instance-id-filter)]
    [trammel.core :only (defconstrainedrecord)]
    [celestial.provider :only (str? vec? wait-for)]
    [celestial.redis :only (synched-map)]
    [celestial.core :only (Vm)]
    [celestial.common :only (get! import-logging )]
    [celestial.model :only (translate vconstruct)]))

(import-logging)

(defn creds [] (get! :hypervisor :aws))

(defm ids [] (synched-map :aws-keys))

(defn wait-for-status [instance req-stat timeout]
  "Waiting for ec2 machine status timeout is in mili"
  (wait-for {:timeout timeout} #(= req-stat (.status instance))
    {:type ::aws:status-failed :message "Timed out on waiting for status" :status req-stat :timeout timeout}))

(defmacro ec2 [f & args]
  `(~f (assoc (creds) :endpoint ~'endpoint) ~@args))

(defn instance-id [uuid]
  (if-let [id (@(ids) uuid)]
    id
    (throw+ 
      {:type ::aws:missing-id :message 
       "Instance id not found, make sure no to address deleted instances" :uuid uuid})
    ))

(defn image-desc [endpoint ami & ks]
  (-> (ec2/describe-images (assoc (creds) :endpoint endpoint) (ec2/image-id-filter ami))
      first (apply ks)))

(defn instance-desc [endpoint uuid & ks]
  (-> (ec2 describe-instances (instance-id-filter (instance-id uuid)))
      first :instances first (get-in ks)))

(defn wait-for-attach [endpoint uuid timeout]
  (wait-for timeout 
            #(= "attached" (instance-desc endpoint uuid :block-device-mappings 0 :ebs :status)) 
            {:type ::aws:ebs-attach-failed :message "Failed to wait for ebs root device attach"}))

(defn pub-dns [endpoint uuid]
  (instance-desc endpoint uuid :public-dns))

(defn wait-for-ssh [{:keys [endpoint uuid user] :as instance}]
  (let [timeout [5 :minute]] 
    (wait-for timeout
              #(ssh-up? {:host (pub-dns endpoint uuid) :port 22 :user user})
              {:type ::aws:ssh-failed :message "Timed out while waiting for ssh" :timeout timeout})))

(defn update-pubdns [{:keys [spec endpoint uuid] :as instance}]
  "updates public dns in the machine persisted data"
  (when (p/system-exists? (spec :system-id))
    (p/partial-system (spec :system-id) {:machine {:ssh-host (pub-dns endpoint uuid)}})))

(defn set-hostname [{:keys [spec endpoint uuid user] :as instance}]
  "Uses a generic method of setting hostname in Linux"
  (let [hostname (get-in spec [:machine :hostname]) remote {:host (pub-dns endpoint uuid) :user user}]
    (execute (<< "echo kernel.hostname=~{hostname} | sudo tee -a /etc/sysctl.conf") remote )
    (execute "sudo sysctl -p" remote) 
    (ec2 create-tags [(instance-desc endpoint uuid :id)] {:Name hostname})
    ))

(defconstrainedrecord Instance [endpoint spec uuid user]
  "An Ec2 instance, uuid used for instance-id tracking"
  [(provider-validation spec) (-> endpoint nil? not)  (-> uuid nil? not)]
  Vm
  (create [this] 
        (let [{:keys [aws]} spec]
          (debug "creating" uuid) 
          (swap! (ids) assoc uuid 
            (-> (ec2 run-instances aws) :instances first :id)) 
          (when (= (image-desc endpoint (aws :image-id) :root-device-type) "ebs")
            (wait-for-attach endpoint uuid [10 :minute])) 
          (update-pubdns this)
          (wait-for-ssh this)
          (set-hostname this)
          this))
  (delete [this]
        (debug "deleting" uuid)
        (ec2 terminate-instances (instance-id uuid))
        (wait-for-status this "terminated" [5 :minute]) 
        (swap! (ids) dissoc uuid))
  (start [this]
        (debug "starting" (instance-id uuid))
        (ec2 start-instances (instance-id uuid))
        (wait-for-status this "running" [5 :minute])
        (update-pubdns this))
  (stop [this]
        (debug "stopping" uuid)
        (ec2 stop-instances  (instance-id uuid))
        (wait-for-status this "stopped" [5 :minute]))
  (status [this] 
        (try+ 
          (instance-desc endpoint uuid :state :name)
          (catch [:type ::aws:missing-id] e false)))) 

(def defaults {:aws {:min-count 1 :max-count 1}})

(defn aws-spec 
   "creates an ec2 spec" 
   [{:keys [aws machine] :as spec}]
  (merge-with merge (dissoc-in* spec [:aws :endpoint]) defaults))

(defmethod translate :aws [{:keys [aws machine] :as spec}] 
  [(aws :endpoint) (aws-spec spec)  (str (UUID/randomUUID)) (or (machine :user) "root")])

(defmethod vconstruct :aws [spec]
  (apply ->Instance (translate spec)))

(comment 
  (use 'celestial.fixtures)
  (def m (.create (vconstruct celestial.fixtures/redis-ec2-spec))) 
  (.start m)
  ;; (instance-desc "ec2.eu-west-1.amazonaws.com" "ca5efc38-2483-4e1e-8c09-428252f82d12" :block-device-mappings 0 :ebs :status)
  (-> (ec2/describe-images (assoc (creds) :endpoint "ec2.eu-west-1.amazonaws.com") (ec2/image-id-filter "ami-64636a10"))
      first :root-device-type  
      )

  (-> (ec2/describe-images (creds) (ec2/image-id-filter "ami-5a60692e"))
      first :root-device-type  
      )
  ) 

