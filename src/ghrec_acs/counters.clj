(ns ghrec-acs.counters)

;;  Counters.
(def ^:dynamic *server-start-time*               (atom nil))

;; Recovery-related.
(def countof-recharge-alerts-bad (atom {}))
(def countof-recovery-attempts   (atom 0))
(def countof-psa-succeeded (atom 0))
(def countof-recovery-attempts-succeeded (atom 0))
(def total-recovered (atom 0))
(def countof-psa-failed (atom {}))





(defn resetcounters [reset-time]
	(reset! *server-start-time*         reset-time)
	;; ---
	(reset! countof-recharge-alerts-bad    {})
	(reset! countof-recovery-attempts 0)
	(reset! countof-psa-succeeded 0)
	(reset! countof-recovery-attempts-succeeded 0)
	(reset! total-recovered 0)

	(reset! countof-psa-failed              {})
	)








