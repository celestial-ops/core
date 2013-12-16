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

(ns celestial.security
  (:use 
    [celestial.common :only (gen-uuid)]
    [gelfino.timbre :only (set-tid)]
    [celestial.common :only (import-logging)])
  (:require 
    [slingshot.slingshot :refer  [throw+ try+]]
    [celestial.roles :as roles]
    [celestial.persistency :as p]
    [clojure.core.strint :refer (<<)]
    [cemerick.friend :as friend]
    (cemerick.friend [workflows :as workflows]
                     [credentials :as creds])))

(import-logging)

(def ^:dynamic current-user 
   "Grab current user, by default uses friend unless binded differently" 
   (fn [] (friend/current-authentication)))

(defmacro set-user [u & body] `(binding [current-user (fn [] ~u)] ~@body))

(defn user-tracking [app]
  "A tiny middleware to track api access"
  (fn [{:keys [uri request-method] :as req}]
    (set-tid (gen-uuid)
      (info request-method " on " uri "by" (current-user))
      (app req))))

(defn login-redirect
  [{:keys [form-params params] :as request}]
  (let [ user (java.net.URLEncoder/encode (or (get form-params "username") (:username params "")))
         param (<< "&login_failed=Y&user=~{user}")]
   (ring.util.response/redirect (<< "/login?~{param}"))))

(defn sign-in-resp 
   [req]
   {:status 401 :body "please sign in first"})

(defn user-with-pass [id]
  {:post [(not-empty (% :password ))]}
  (p/get-user! id))

(defn check-creds 
   "Runs bcrypt password check if user exists" 
  [creds]
  (if (p/user-exists? (:username creds))
    (try 
      (creds/bcrypt-credential-fn user-with-pass creds)
      (catch IllegalArgumentException e 
       (throw+ {:msg "Please ask admin to reset your password, persisted hashed version has been correupted"})))
   
    nil))

(defn secured-app [routes]
  (friend/authenticate 
    (friend/wrap-authorize (user-tracking routes) roles/user) 
    {:allow-anon? false
     :credential-fn check-creds
     :unauthenticated-handler sign-in-resp 
     :workflows [
        (workflows/interactive-form :login-failure-handler login-redirect)
        (workflows/http-basic :realm "basic-celestial")]}))

