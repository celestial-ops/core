(ns celestial.integration.workflows.docker
  "Docker workflows"
  (:require 
    [celestial.fixtures.core :refer (with-defaults is-type? with-admin with-conf)]  
    [celestial.fixtures.data :refer 
     (redis-type local-conf redis-docker-spec)]
    [celestial.fixtures.populate :refer (populate-system)]  
    [celestial.integration.workflows.common :refer (spec get-spec)]
    [celestial.workflows :as wf])
  (:import clojure.lang.ExceptionInfo)
  (:use midje.sweet)
 )

(with-admin
 (with-conf local-conf
  (with-state-changes [(before :facts (populate-system redis-type redis-docker-spec))]
    (fact "creation workflow" :integration :docker :workflow
        (wf/create (spec)) => nil 
       ;; (target) => (expected)
      )
    ) 
   ) 
  )