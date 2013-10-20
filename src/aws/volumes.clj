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

(ns aws.volumes
  (:require 
    [celestial.common :refer (import-logging )] 
    [celestial.provider :refer (wait-for)] 
    [aws.sdk.ebs :as ebs]
    [aws.sdk.ec2 :as ec2]
    [celestial.persistency.systems :as s]
    [aws.common :refer (with-ctx instance-desc creds image-id)]
    )) 

(import-logging)

(defn image-desc [endpoint ami & ks]
  (-> (ec2/describe-images (assoc (creds) :endpoint endpoint) (ec2/image-id-filter ami))
      first (apply ks)))

(defn wait-for-attach [endpoint instance-id timeout]
  (wait-for {:timeout timeout} 
    #(= "attached" (instance-desc endpoint instance-id :block-device-mappings 0 :ebs :status)) 
    {:type ::aws:ebs-attach-failed :message "Failed to wait for ebs root device attach"}))

(defn handle-volumes 
   "attached and waits for ebs volumes" 
   [{:keys [aws machine] :as spec} endpoint instance-id]
  (when (= (image-desc endpoint (image-id machine) :root-device-type) "ebs")
    (wait-for-attach endpoint instance-id [10 :minute]))
  (let [zone (instance-desc endpoint instance-id :placement :availability-zone)]
    (doseq [{:keys [device size]} (aws :volumes)]
      (let [{:keys [volumeId]} (with-ctx ebs/create-volume size zone)]
        (wait-for {:timeout [10 :minute]} #(= "available" (with-ctx ebs/state volumeId))
           {:type ::aws:ebs-volume-availability :message "Failed to wait for ebs volume to become available"})
        (with-ctx ebs/attach-volume volumeId instance-id device)
        (wait-for {:timeout [10 :minute]} #(= "attached" (with-ctx ebs/attachment-status volumeId))
           {:type ::aws:ebs-volume-attach-failed :message "Failed to wait for ebs volume device attach"})))))

(defn clear?
   "is this ebs clearable" 
   [device-name system-id]
  (let [{:keys [volumes]} ((s/get-system system-id) :aws)]
    ((first (filter (fn [{:keys [device]}] (= device device-name)) volumes)) :clear)))

(defn delete-volumes 
  "Clear instance volumes" 
  [endpoint instance-id system-id]
  (doseq [{:keys [ebs device-name]} (-> (instance-desc endpoint instance-id) :block-device-mappings rest)]
    (when (clear? device-name system-id)
      (trace "deleting volume" ebs) 
      (with-ctx ebs/detach-volume (ebs :volume-id)) 
      (wait-for {:timeout [10 :minute]} 
        #(= "available" (with-ctx ebs/state  (ebs :volume-id)))
        {:type ::aws:ebs-volume-availability 
         :message "Failed to wait for ebs volume to become available"}) 
      (with-ctx ebs/delete-volume (ebs :volume-id)))))

(comment
  (clojure.pprint/pprint 
    (celestial.model/set-env :dev 
                             (-> (instance-desc "ec2.eu-west-1.amazonaws.com" "i-a5a88de9") :block-device-mappings rest)
                             ))
  )