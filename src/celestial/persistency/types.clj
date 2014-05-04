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

(ns celestial.persistency.types
  (:refer-clojure :exclude [type])
  (:require 
    [puny.core :refer (entity)]
    [subs.core :as subs :refer (validate! combine)]))

(entity type :id type)

(def puppet-std-v 
  {:classes #{:required :Map}
   :puppet-std {
     :args #{:Vector} 
     :module {
       :name #{:required :String}
       :src  #{:required :String}}}})

(defn validate-type [{:keys [puppet-std] :as t}]
  (validate! t 
    (combine (if puppet-std puppet-std-v {})
      {:type #{:required :String}}) :error ::non-valid-type ))

