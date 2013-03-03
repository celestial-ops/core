(ns celestial.test.proxmox
  (:use 
    [proxmox.remote :only (prox-get)]
    [proxmox.provider :only (vzctl enable-features ->Container)]
    [celestial.common :only (slurp-edn)]
    [celestial.model :only (construct translate)]
    [celestial.fixtures :only (spec)]
    expectations.scenarios) 
  (:import 
    [proxmox.provider Container]))

(let [{:keys [machine proxmox]} spec]
  (scenario 
    (expect java.lang.AssertionError 
       (construct (assoc-in spec [:proxmox :vmid] nil)))
    (expect java.lang.AssertionError 
       (construct (assoc-in spec [:proxmox :vmid] "string") ))
    ))


(def ct (construct (assoc-in spec [:proxmox :features] ["nfs:on"])))

(scenario 
  (enable-features ct) 
  (expect 
    (interaction (vzctl ct "set 33 --features \"nfs:on\" --save")) :once))

