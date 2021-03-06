(ns re-core.test.validations
 (:require
   [flatland.useful.map :refer (dissoc-in*)]
   [re-core.roles :refer (admin)]
   [re-core.persistency
    [types :refer (validate-type)] [users :refer (user-exists? validate-user)]]
   [re-core.persistency.quotas :refer (validate-quota)]
   [re-core.model :refer (check-validity)]
   [aws.validations :as awsv]
   [re-core.fixtures.data :refer
    (redis-type user-quota redis-ec2-spec
     redis-openstack-spec redis-physical)]
   [re-core.fixtures.core :refer (is-type? with-m?)])
 (:use midje.sweet)
 (:import clojure.lang.ExceptionInfo))

(fact "puppet std type validation"

    (validate-type redis-type) => truthy

    (validate-type (assoc-in redis-type [:puppet-std :dev :module :src] nil)) =>
       (throws ExceptionInfo (is-type? :re-core.persistency.types/non-valid-type))

    (validate-type (assoc-in redis-type [:puppet-std :dev :args] nil)) => truthy

    (validate-type (assoc-in redis-type [:puppet-std :dev :args] [])) => truthy

    (validate-type (assoc-in redis-type [:puppet-std :dev :args] {})) =>
       (throws ExceptionInfo (is-type? :re-core.persistency.types/non-valid-type))

    (validate-type (dissoc-in* redis-type [:puppet-std :dev :classes])) =>
       (throws ExceptionInfo (is-type? :re-core.persistency.types/non-valid-type)))

(fact "non puppet type"
  (validate-type {:type "foo"}) => truthy)

(fact "quotas validations"
     (validate-quota user-quota) => truthy
     (provided (user-exists? "foo") => true :times 1))

(fact "non int limit quota"
   (validate-quota (assoc-in user-quota [:quotas :dev :aws :limits :count] "1")) =>
      (throws ExceptionInfo (with-m? {:quotas {:dev {:aws {:limits {:count  "must be a integer"}}}}}))
   (provided (user-exists? "foo") => true :times 1))

(fact "user validation"
   (validate-user {:username "foo" :password "bar" :roles admin :envs [] :operations []})  => truthy
   (validate-user {:password "bar" :roles admin :envs [] :operations []})  =>
     (throws ExceptionInfo (with-m? {:username "must be present"}))

   (validate-user {:username "foo" :password "bar" :roles admin :operations []})  =>
     (throws ExceptionInfo (with-m? {:envs "must be present"}))

   (validate-user {:username "foo" :password "" :roles admin :envs [] :operations []})  =>
     (throws ExceptionInfo (with-m? {:password "must be a non empty string"}))

   (validate-user {:username "foo" :password "bar" :roles admin :envs [""] :operations []})  =>
     (throws ExceptionInfo (with-m? {:envs '({0 "must be a keyword"})} ))

   (validate-user {:username "foo" :password "bar" :roles admin :envs [] :operations [:bla]})  =>
     (throws ExceptionInfo (with-m?  {:operations  '({0 "operation must be either #{:provision :stage :create :start :destroy :stop :clone :reload :run-action :clear}"})}))

     (validate-user {:username "foo" :password "bar" :roles ["foo"] :envs [] :operations []})  =>
       (throws ExceptionInfo
          (with-m? {:roles '({0 "role must be either #{:re-core.roles/user :re-core.roles/admin :re-core.roles/anonymous :re-core.roles/super-user :re-core.roles/system}"})}))
      )

(fact "aws volume validations"
  ; TODO this should fail! seems to be a subs issue
  (check-validity
    (merge-with merge redis-ec2-spec
      {:aws {:volumes [{:device "/dev/sdg"}]}})) =>
      (throws ExceptionInfo
        (with-m?
          '{:aws {:volumes ({0 {:clear "must be present", :size "must be present", :volume-type "must be present"}})}}))

  (check-validity
    (merge-with merge redis-ec2-spec
      {:aws {:volumes [{:device "/dev/sdb" :volume-type "gp2" :size 100 :clear true}]}})) => {}

  (check-validity
    (merge-with merge redis-ec2-spec
      {:aws {:volumes [{:device "/dev/sda" :volume-type "io1" :iops 100 :size 10 :clear false}]}})) => {}

  (check-validity
    (merge-with merge redis-ec2-spec
      {:aws {:volumes [{:device "/dev/sda" :volume-type "io1" :size 10 :clear false}]}})) =>
      (throws ExceptionInfo
        (with-m?  {:aws {:volumes '({0 "iops required if io1 type is used"})}})))

(fact "aws entity validations"
  (check-validity redis-ec2-spec) => {}

  (check-validity
    (merge-with merge redis-ec2-spec {:aws {:security-groups [1]}})) =>
    (throws ExceptionInfo (with-m? {:aws {:security-groups '({0 "must be a string"})}}))

  (check-validity
    (merge-with merge redis-ec2-spec {:aws {:availability-zone 1}})) =>
    (throws ExceptionInfo (with-m? {:aws {:availability-zone "must be a string"}})))

(fact "aws provider validation"
  (let [base {:aws {:instance-type "m1.small" :key-name "foo" :min-count 1 :max-count 1}}]

   (awsv/provider-validation base) => {}

   (awsv/provider-validation (merge-with merge base {:aws {:placement {:availability-zone "eu-west-1a"}}})) => {}

   (awsv/provider-validation (merge-with merge base {:aws {:placement {:availability-zone 1}}})) =>
    (throws ExceptionInfo
      (with-m? {:placement {:availability-zone "must be a string"}}))))


(fact "physical systmes validation"
   (check-validity redis-physical) => {}

   (check-validity (assoc-in redis-physical [:physical :mac] "aa:bb")) =>
     (throws ExceptionInfo (with-m? {:physical {:mac "must be a legal mac address"}}))

   (check-validity (assoc-in redis-physical [:physical :broadcast] "a.1.2")) =>
      (throws ExceptionInfo (with-m? {:physical {:broadcast "must be a legal ip address"}})))

(fact "openstack volume validations"
  (let [spec (merge-with merge redis-openstack-spec {:openstack {:volumes [{:device "do" :size 10}]}})]
    (check-validity spec) =>
      (throws ExceptionInfo
        (with-m?
          '{:openstack
            {:volumes
              ({0 {:clear "must be present" :device "device should match /dev/{id} format"}})}}))))
