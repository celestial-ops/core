(ns celestial.test.gc.openstack
  (:require 
    [celestial.security :refer (set-user current-user)]
    [openstack.gc :refer (find-candidates)]
    [celestial.persistency.systems :as s]
    [celestial.fixtures.core :refer (is-type? with-defaults with-conf)]
    [celestial.fixtures.populate :refer (populate-all)])
  (:use midje.sweet))

(def machines [ 
  {:system-id "1":machine {} :openstack {:instance-id "1a"}} 
  {:system-id "2" :machine {} :openstack {:instance-id "4d"}}])

(fact "find openstack candidates" :gc :openstack 
   (find-candidates machines ["1a" "2b" "3c"]) => '("2b" "3c")
   (find-candidates machines []) =>  '()
   (find-candidates machines [] ["1a"]) =>  '())

(fact "openstack candidates exclude" :gc :openstack 
   (find-candidates machines ["1a" "2b" "3c"] ["3c"]) => '("2b")
   )