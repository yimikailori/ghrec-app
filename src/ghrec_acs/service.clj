(ns ghrec-acs.service
	(:use [ghrec-acs.config])
	(:require [clojure.tools.logging :as log]
			  [ghrec-acs.utils :as utils]
			  [ghrec-acs.config :refer [env]]
		;[ghrec-acs.rmqutils :as rmq]
			  [ghrec-acs.rpc :as rpc]
			  [clojure.string :as str]
			  [clojure.data.json :as json]
			  [clj-time.format :as f]
			  [ghrec-acs.db :as db]
			  [ghrec-acs.counters :as counters]
			  [clj-time.core :as t]
			  [com.climate.claypoole :as cp]
			  [ghrec-acs.rmqutils :as rmqutils])
	(:import (java.net URLDecoder)))

(declare run-recovery %attempt-and-log attempt-recovery queue-recharge-notification run-cdr-recovery recharge-notifications-handler)
;;sms queues
(def send-sms-details (atom {}))

;;for recovery
(def send-recovery-details (atom {}))
(def select-values (comp vals select-keys))
(defn queues [] [(reset! send-sms-details {:host              (get-in env [:queue :sms :host])
										   :port              (get-in env [:queue :sms :port])
										   :username          (get-in env [:queue :sms :username])
										   :password          (get-in env [:queue :sms :password])
										   :vhost             (get-in env [:queue :sms :vhost])
										   :conn              (atom nil)
										   :channel           (atom nil)
										   ; queue info
										   :queue-name        (get-in env [:queue :sms :queue-name])
										   :queue-exchange    (get-in env [:queue :sms :queue-exchange])
										   :queue-routing-key (get-in env [:queue :sms :queue-routing-key])})

				 (reset! send-recovery-details {
												:host              (get-in env [:queue :recovery :host])
												:port              (get-in env [:queue :recovery :port])
												:username          (get-in env [:queue :recovery :username])
												:password          (get-in env [:queue :recovery :password])
												:vhost             (get-in env [:queue :recovery :vhost])
												:conn              (atom nil)
												:channel           (atom nil)
												; queue info
												:queue-name        (get-in env [:queue :recovery :queue-name])
												:queue-exchange    (get-in env [:queue :recovery :queue-exchange])
												:queue-routing-key (get-in env [:queue :recovery :queue-routing-key])
												:handler           recharge-notifications-handler
												:consumers         250
												})
				 ])

(defn initialize-queue []
	(rmqutils/initialize-queue-interfaces (queues)))

(defn shut-down []
	(let [queues [send-sms-details send-recovery-details]
		  _      (log/infof "Shutting down Rabbitmq Connections")]
		(doseq [queue queues]
			(when-not (nil? (:channel @queue))
				(rmqutils/closeQueue (:channel @queue))
				(rmqutils/closeQueue (:conn @queue))
				(reset! (:channel @queue) nil)
				(reset! (:conn @queue) nil)))))

(defn recharge-notifications-handler
	[ch {:keys [delivery-tag] :as meta} ^bytes payload]
	(let [payload (json/read-str (String. payload "UTF-8") :key-fn keyword)
		  {:keys [sub amount time attempts time-queued type]} payload
		  sub     (utils/submsisdn sub)
		  channel (:channel @send-recovery-details)]
		(log/debugf "[recovery consumer] payload: %s, delivery tag: %d, -> %s"
			payload delivery-tag meta)
		(try
			(run-cdr-recovery sub time amount (if (nil? type) :cdr (keyword type)))

			{:action :ack :value :ack}
			(catch Exception e
				(log/error (format "!handleReQNotification(%s) -> %s" payload (.getMessage e)) e)
				;;future modifications
				{:action :ack :value :ack}))))



(defn send-sms [subscriber type request-id amount owe]
	(let [sms-sender-name (format (get-in env [:recovery-messge :sms-sender-name]))
		  msg             (if (= type :full)
							  (format (get-in env [:recovery-messge :sms-full]) amount)
							  (format (get-in env [:recovery-messge :sms-partial]) amount owe))
		  sms-payload (if (not (empty? sms-sender-name))
						  {:msisdn  subscriber :id request-id :message msg :flash? false :from sms-sender-name}
						  {:msisdn  subscriber :id request-id :message msg :flash? false})]
		(log/infof "sendSMS(%s,%s,%s,%s) -> %s" request-id subscriber type amount msg)
		(try
			;(json/write-str {:sub subscriber :amount amount :time time
			;                                                          :attempts 0 :time-queued (System/currentTimeMillis) :type type})
			(rmqutils/initialize-rabbitmq (assoc @send-sms-details :msg (json/write-str sms-payload)))

			(catch Exception ex
				(log/errorf ex "cannotSendSMS(%s,%s,%s,%s) -> %s" request-id subscriber type amount (.getMessage ex))))))



(def locked-account-numbers (atom #{}))

(defn- account-locked?
	"Return `true' if a subscriber account is locked and false
	`otherwise'."
	[#^Integer subscriber-number]
	(if (contains? @locked-account-numbers subscriber-number) true false))

(defn lock-subscriber-number
	"Flag this account number as in-use by the recovery module. Returns
	TRUE if the lock succeeds and FALSE if it fails. Failure means that
	the account number is already locked."
	[#^Integer subscriber-number]
	(if (account-locked? subscriber-number)
		false
		(do (swap! locked-account-numbers conj subscriber-number)
			(log/debug (str "lockingAccount(" subscriber-number ")"))
			true)))


(defn run-cdr-recovery [subscriber recharge-time trigger-amount event-source & args]
	(let [custom-formatter   (f/formatter "yyyyMMddHHmmss")
		  trigger-time       (f/unparse
								 (f/formatter "yyyy-MM-dd HH:mm:ss") (f/parse custom-formatter recharge-time))
		  known-event-source #{:cdr :manual :cci}
		  _                  (log/infof "cdr-recover-parameters (subscriber=%s,trigger-time=%s, recharge-time=%s,trigger-amount= %s,event-source=%s)" subscriber trigger-time recharge-time
								 trigger-amount event-source)]
		(if (known-event-source event-source)
			(let [records (db/get-recovery-candidates {:sub subscriber :time trigger-time})
				  _       (log/infof "Subscriber loan details [%s]" records)
				  tuples  (loop [ftuple (first records)
								 rtuple (rest records)
								 result []]
							  (if (nil? ftuple)
								  result
								  (let [{:keys [issue_loan_paid issue_recon issue_mismatch trigger_issue_age loanid]} ftuple
										error? (contains? (into #{} (map (fn [x]
																			 (if (empty? x) true false))
																		[issue_loan_paid issue_recon issue_mismatch trigger_issue_age]))
												   false)]
									  (if error?
										  (let [err-state (disj (into #{} (map (fn [x]
																				   (if (empty? x) false x))
																			  [issue_loan_paid issue_recon issue_mismatch trigger_issue_age])) false)]
											  (log/error (format "AlertOfIssueInRecord(%s,%s,%s)" subscriber loanid err-state))
											  (recur (first rtuple) (rest rtuple) result))
										  (do
											  ;(log/debug (format "NonIssueRecoveryTuples (%s)" ftuple))
											  (recur (first rtuple) (rest rtuple) (conj result ftuple)))))))]
				(if (empty? tuples)
					(log/warnf "AlertOfNonLendingTransaction(%s,%s)"
						{:sub subscriber :time trigger-time :amount trigger-amount}
						tuples)
					(do
						(log/infof "AlertOfLendingTransaction (%s)" tuples)
						(run-recovery event-source tuples nil args))))
			(throw (Exception. (format "unknown event-source %s" event-source))))))

(defn run-sweep-recovery [thread-pool batch-size]
	(utils/with-func-timed "runSweep" []
		(let [tuples (utils/with-func-timed "getSweepLoans" []
						 (into [] (db/get-sweep-candidates {:batch_size batch-size})))
			  tcount (count tuples)]
			(when (> tcount 0)
				(run-recovery :sweep tuples thread-pool {:thread-name "thread-sweep"})))))



(def present  (atom 0))
(defn- run-recovery [event-source tuples thread-pool & args]
	(when (not (= event-source :sweep)) (log/debugf "RunRecovery -> [event-source=%s,tuples=%s,thread-pool=%s,args=%s]" event-source tuples thread-pool args))
	(when tuples
		(let [locked-subscriber (loop [x (first tuples)
									   xs (next tuples)
									   result []]
									(let [subscriber (x :subscriber)
										  result (if (lock-subscriber-number subscriber)
													 (do
														 (reset! present 1)
														 (if (not (= event-source :sweep))
															 tuples
															 (conj result x)))
													 (do
														 (if (= @present 1)
															 (conj result x)
															 (log/debug (str "alreadyLockedAcc(" subscriber ")")))
														 result))]
										(if xs
											(recur (first xs) (next xs) result)
											result)))
			  _ (reset! present 0)]
			(if thread-pool
				(cp/with-shutdown! [pool (cp/threadpool thread-pool :name (or (:thread-name args) "thread-pool"))]
					(->> [locked-subscriber]
						(cp/pmap pool %attempt-and-log)
						doall))
				(utils/with-func-timed "attemptRecovery-chunks" []
					(%attempt-and-log event-source locked-subscriber)))
			(count locked-subscriber))))


(defn %attempt-and-log
	([tuple]
	 (%attempt-and-log :sweep tuple))
	([event-source tuple]
	(try
		(attempt-recovery event-source tuple)
		(finally
			(doseq [acc tuple]
				(when (= :sweep event-source)
					(let [loanid (or (:loanid acc) (:loan_id acc))]
						(db/update-last-recovery-attempt {:loan_id loanid})))
				(swap! locked-account-numbers disj (:subscriber acc))
				(log/infof "unlockAccount(%s)" (:subscriber acc)))))))


(let [+recovery-methods+  {:cdr 1 :sweep 2 :retry 3 :manual 4 :cci 5}
	  generate-request-id (fn []
							  (let [timestamp  (System/currentTimeMillis)
									rand       (format "%04d" (rand-int 9999))
									request-id (str timestamp rand)]
								  (biginteger request-id)))]
	(defn- insert-log-recovery
		"Log recovery start into and return a new ID for the recovery transaction."
		[recovery-method loan-id subscriber cedis_balance amount-requested]
		(log/info (format "log-recovery-start Result = %s" [recovery-method loan-id subscriber cedis_balance amount-requested]))
		(let [request-id (generate-request-id)]
			(db/insert-attempt-recovery
				{:recovery_method (+recovery-methods+ recovery-method)
				 :request_id      request-id
				 :loan_fk         loan-id :subscriber subscriber
				 :cedis_balance   cedis_balance :amount_requested amount-requested
				 })
			[request-id])))

(defn- reconfirm-loan-balance
	"Reconfirm loan balance to before puting in unreconciled state"
	[subscriber loan-id]
	(log/debugf "getting loan_balance %s->%s" subscriber loan-id)
	(let [data (:outstanding (db/get-loan-balance {:loan_id loan-id}))]
		(log/debugf "Balance values %s,%s -> %s" subscriber loan-id data)
		data))


(defn- do-recover [data]
	(log/infof "do-recover %s" data)
	(let [{:keys [event-source loan-id subscriber to-recover loan-type]} data
		  init-outstanding (reconfirm-loan-balance subscriber loan-id)]
		(cond (nil? init-outstanding) (do (log/errorf "!noValidLoan (id=%s,sub=%s,old=%s,new=%s,recon=true)"
											  loan-id subscriber init-outstanding to-recover)
										  {:status :failed :error-class :loan-bal-changed :need-recon? false})
			(>= init-outstanding to-recover)
			(let [[request-id] (insert-log-recovery event-source loan-id subscriber init-outstanding to-recover)
				  {:keys [txnid balanceID oldbalanceamt newbalanceamt resultCode resultDesc recovered mismatch] :as debit-response }
				  (rpc/debit-account {:request-id request-id :subscriber subscriber
									  :loan-type  loan-type :amount to-recover :loan-id loan-id})
				  recovery-code (when recovered
									(condp = recovered
										init-outstanding 0
										0 2
										1))]

				(cond (not (= (str txnid) (str request-id))) (do
													 (log/errorf "OCS transactionID Expected(%s),found(%s) -> %s" request-id txnid
														[loan-id subscriber init-outstanding to-recover true debit-response])
													 (utils/increment-counter counters/countof-psa-failed :txnid-mismatch)
													 {:status :failed :error-class :txnid-mismatch :need-recon? true})
					(nil? mismatch) (do
										(utils/increment-counter counters/countof-psa-failed resultCode)
										(log/errorf "!mismatch got %s -> %s" mismatch [loan-id subscriber init-outstanding to-recover true debit-response])
										{:status :failed :error-class resultCode :need-recon? false})
					(true? mismatch) (do (log/errorf "OCS loanBalMismatch(loanid=%s,sub=%s,outstanding=%s,amount_requested=%s,
					 						need-recon=%s -> %s"
											 loan-id subscriber init-outstanding to-recover true debit-response)
										 (utils/increment-counter counters/countof-psa-failed :mismatch)
										 {:status :failed :error-class :mismatch :need-recon? true})

					:else (do
							  (db/update-recovery-result {:request_id request-id :loan_id loan-id
														  :cedis_recovered recovered :recovery_code recovery-code
														  :error_message resultDesc})
							  (if (= resultCode "0")
								  (do
									  (swap! counters/countof-psa-succeeded inc)
									  (swap! counters/countof-recovery-attempts-succeeded inc)
									  (swap! counters/total-recovered + recovered)
									  ;; ---
									  {:status :ok  :error-class :success :recovered recovered})))))
			:else (do (log/errorf "Internal loanBalChanged(id=%s,sub=%s,old=%s,new=%s)"
						  loan-id subscriber init-outstanding to-recover)
					  {:status :failed :error-class :loan-bal-changed :need-recon? false}))))



(defn- attempt-recovery [event-source loaninfo & args]
	(swap! counters/countof-recovery-attempts inc)
	(let [counter (atom 1)]
		(log/infof "attemptingRecovery %s" loaninfo)
		(doseq [loan-info loaninfo]
			(log/infof "SingleLoan Processing [%s -> %s]" @counter loan-info)
			(swap! counter inc)
			(try
				(let [{:keys [loanid loan_id subscriber cedis_paid cedis_loaned loantype loaned paid trigger_event_time loan_time]} loan-info
					  loan_type (keyword loantype)
					  loanid (or loanid loan_id)
					  loaned (or loaned cedis_loaned)
					  paid (or paid cedis_paid)]
					(log/info (condp = event-source
								  :sweep (format "sweepRecovery(id=%s,sub=%s)" loanid subscriber)
								  (format "Recovery (event-source=%s,id=%s,sub=%s,typ=%s,time=%s)"
									  event-source loanid subscriber loan_type
									  trigger_event_time)))
					(let [outstanding (- loaned paid)
						  {:keys [ma-balance] :as ret} (rpc/get-account-balance  loanid subscriber (t/now) outstanding)
						  ocs-recover (fn [delta]
										  (let [return-from-recovery (do-recover {:event-source event-source
																				  :loan-id      loanid
																				  :subscriber   subscriber
																				  :loan-type    loan_type
																				  :loaned       loaned
																				  :paid         paid
																				  :to-recover   delta
																				  ;; Counters
																				  })]
											  (log/info (str "return from account debit = " return-from-recovery))
											  return-from-recovery))]
						(if (= (biginteger ma-balance) 0)
							(log/infof "Low Balance %s -> [outstanding=%s,MA-balance=%s]" subscriber outstanding ma-balance)
							(let [ma-balance (biginteger ma-balance)
								  outstanding (biginteger outstanding)
								  {:keys [status recovered error-class need-recon?] :as details} (ocs-recover (min ma-balance outstanding))
								  balance (- outstanding (or recovered 0))]
								(log/info (format "Recovered (sub=%s,recovered=%s,error-class=%s,need-recon=%s,new-balance=%s,old-user-MA=%s)" subscriber recovered error-class need-recon? balance ma-balance))
								(cond recovered                                    ;;attempt to send sms
									(let [loan-balance (- loaned paid)
										  new-balance  (- loan-balance recovered)]
										(when (> recovered 0)
											(let [type (condp = new-balance
														   0 :full
														   :partial)
												  _    (log/info (format "sending recovery sms [sub=%s,amt=%s,balance=%s,loan=%s]" subscriber recovered balance loaned))]
												(send-sms subscriber type loanid (utils/cedis recovered) (utils/cedis new-balance)))))
									need-recon? false
									:else (condp = error-class
											  :txid-mismatch (log/errorf "OCS TransationID mismatch needs reconcilation %s" details)
											  :mismatch (log/errorf "OCS balance mismatch needs reconcilation %s" details)
											  :loan-bal-changed (log/errorf "loan-bal-changed needs reconcilation %s" details)
											  :low-balance (log/errorf "loan-balance %s" details)
											  (log/errorf "OCS general error => %s|%s" error-class details)))))))
				(catch Exception e
					(log/error (format "recoveryError() -> %s" (.getMessage e)) e))))))


