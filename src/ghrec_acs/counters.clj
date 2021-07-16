(ns ghrec-acs.counters
	(:require [clj-time.format :as f]
			  [clj-time.local :as l]
			  [clojure.data.json :as json]
			  [ghrec-acs.utils :as utils]))

;;  Counters.
(def ^:dynamic *server-start-time* (atom (f/unparse
											 (f/formatters :mysql) (l/local-now))))

;; Recovery-related.
(def countof-recharge-alerts-bad (atom 0))
(def countof-recharge-alerts   (atom {}))

(def countof-recovery-attempts   (atom 0))

(def countof-getbalance-attempts   (atom 0))
(def countof-icc-succeeded (atom 0))
(def countof-recovery-attempts-succeeded (atom 0))
(def total-recovered (atom 0))
(def countof-icc-failed (atom {}))





(defn resetcounters []
	;(reset! *server-start-time*         reset-time)
	;; ---
	(reset! countof-recharge-alerts-bad    0)
	(reset! countof-recharge-alerts		{})
	(reset! countof-recovery-attempts 0)
	(reset! countof-recovery-attempts-succeeded 0)
	(reset! countof-getbalance-attempts	0)

	(reset! total-recovered 0)
	(reset! countof-icc-succeeded 0)
	(reset! countof-icc-failed              {}))












