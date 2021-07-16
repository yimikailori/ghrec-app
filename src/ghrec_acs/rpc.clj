(ns ghrec-acs.rpc

	(:use [clojure.data.zip.xml :only [xml-> xml1-> text]]
		  [necessary-evil.xml-utils :only [to-xml]])
	(:require [clojure.tools.logging :as log]
			  [clojure.zip :as zip]
			  [ghrec-acs.config :refer [env]]
			  [ghrec-acs.utils :as utils]
			  [necessary-evil.methodresponse :as methodresponse]
			  [clj-http.client :as http]
			  [clj-time.core :as t]
			  [clojure.zip :as zip]
			  [clojure.xml :as xml]
			  [clojure.data.zip.xml :as zip-xml :only [xml1 text]])
	(:import (java.util.concurrent TimeoutException)
			 (org.joda.time DateTime)
			 (java.io ByteArrayInputStream File)
			 (java.net NoRouteToHostException)))



(defn- parse-ocs-bal-response [content]
	(let [response (xml/parse (ByteArrayInputStream. (.getBytes content "UTF-8")))
		  response (zip/xml-zip response)
		  acclist  (zip-xml/xml1-> response :soapenv:Body :ars:QueryBalanceResultMsg :QueryBalanceResult :ars:AcctList)
		  rawdata {:baltype     (into [] (zip-xml/xml-> (zip/xml-zip (first acclist)) :ars:BalanceResult :arc:BalanceType zip-xml/text))
				   :totalAmount (into [] (zip-xml/xml-> (zip/xml-zip (first acclist)) :ars:BalanceResult :arc:TotalAmount zip-xml/text))
				   :balDetail (into [] (zip-xml/xml-> (zip/xml-zip (first acclist)) :ars:BalanceResult :arc:BalanceDetail :arc:BalanceInstanceID zip-xml/text))
				   :balDetailAmt (into [] (zip-xml/xml-> (zip/xml-zip (first acclist)) :ars:BalanceResult :arc:BalanceDetail :arc:BalanceInstanceID :arc:Amount zip-xml/text))
				   }
		  check_MA (.indexOf (:baltype rawdata) "C_MAIN_ACCOUNT")]
		(if (>= check_MA 0)
			{:acct-type (nth (:baltype rawdata)  check_MA)
			 :ma-balance (nth (:totalAmount rawdata) check_MA)
			 :txnid 	(nth (:balDetail rawdata) check_MA)})))

(defn parse-ocs-debit-response [content]
	(let [response (xml/parse (ByteArrayInputStream. (.getBytes content "UTF-8")))
		  response (zip/xml-zip response)]
		{:txnid         (zip-xml/xml1-> response :soapenv:Body :bcs:FeeDeductionResultMsg :FeeDeductionResult :bcs:DeductSerialNo zip-xml/text)
		 :balanceID     (zip-xml/xml1-> response :soapenv:Body :bcs:FeeDeductionResultMsg :FeeDeductionResult :bcs:AcctBalanceChangeList
							:bcs:BalanceChgInfo :bcc:BalanceID zip-xml/text)
		 :oldbalanceamt (zip-xml/xml1-> response :soapenv:Body :bcs:FeeDeductionResultMsg :FeeDeductionResult :bcs:AcctBalanceChangeList
							:bcs:BalanceChgInfo :bcc:OldBalanceAmt zip-xml/text)
		 :newbalanceamt (zip-xml/xml1-> response :soapenv:Body :bcs:FeeDeductionResultMsg :FeeDeductionResult :bcs:AcctBalanceChangeList
							:bcs:BalanceChgInfo :bcc:NewBalanceAmt zip-xml/text)
		 :resultCode    (zip-xml/xml1-> response :soapenv:Body :bcs:FeeDeductionResultMsg :ResultHeader :cbs:ResultCode zip-xml/text)
		 :resultDesc    (zip-xml/xml1-> response :soapenv:Body :bcs:FeeDeductionResultMsg :ResultHeader :cbs:ResultDesc zip-xml/text)}))


(defn- call-ocs [body calltype]
	(let [timeout      (get-in env [:ocs :ocs-timeout])
		  endpoint-url (if (= calltype :balance)
						   (get-in env [:ocs :ocs-balance-url])
						   (get-in env [:ocs :ocs-debit-url]))]
		(http/post endpoint-url {:content-type               "text/xml;charset=UTF-8"
								 :body                       body
								 :connection-request-timeout timeout
								 :connection-timeout         timeout
								 :socket-timeout             timeout})))
(defn get-account-balance
	"Attempt to query balance and return a keyword indicating error
	class if failed or the account balance if successful."
	[request-id subscriber log-time outstanding]
	(let [log-time       (t/now)
		  ;request-id-new (str request-id (str (long (/ (.getMillis log-time) 1000))))
		  requestxml     (slurp (File. (get-in env [:ocs :ocs-getbalance-request-xml])))
		  body           (format requestxml
							 request-id
							 (get-in env [:ocs :ocs-loginsystemcode])
							 (get-in env [:ocs :ocs-password])
							 (get-in env [:ocs :ocs-operatorid])
							 (utils/submsisdn subscriber))
		  _ (log/debugf "get-account-balance(params(%s,%s,%s -> %s)) "
			  request-id subscriber log-time body)
		  {:keys [status body error] :as ret} (call-ocs body :balance)     ;{:body requestxml :status :ok}
		  #_{:request-type   :balance
			 :request-id     (BigInteger. request-id-new)
			 :old-request-id request-id :subscriber subscriber :log-time log-time
			 :service-url    service-url}
		  ]
		(log/debugf "OCS getBalance Response %s|%s|%s" subscriber status body)
		(if error
			(let [error-msg (condp instance? error
								TimeoutException ":timeout-on-connect"
								error)]
				(log/errorf "Connection exception %s|%s" error-msg ret))
			(let [{:keys [ma-balance acct-type txnid] :as parsed-body} (parse-ocs-bal-response body)]
				(log/infof "Parsed Account Balance Response %s" parsed-body)
				(if (number? (utils/toInt ma-balance))
					 parsed-body
					(do (log/errorf "!queryBalance(%s) -> %s"
							{:rid request-id :sub subscriber} ret)
						ret))))))



(defn debit-account
	"Attempt to query balance and return a keyword indicating error
	class if failed or the account balance if successful."
	[data]
	(log/debugf "debit-account(params(%s)) " data)
	(let [{:keys [request-id subscriber amount]} data
		  log-time   (t/now)
		  requestxml (slurp (File. ^String (get-in env [:ocs :ocs-debitbalance-request-xml])))
		  body       (format requestxml
						 request-id
						 (get-in env [:ocs :ocs-loginsystemcode])
						 (get-in env [:ocs :ocs-password])
						 (get-in env [:ocs :ocs-operatorid])
						 request-id
						 (utils/submsisdn subscriber)
						 amount)
		  {:keys [status body error] :as ret} (call-ocs body :debit)     ;{:body requestxml :status :ok}
		  ]
		(log/infof "Debit Response= %s|%s" subscriber ret)
		(if error
			(let [error-msg (condp instance? error
								TimeoutException ":timeout-on-connect"
								error)]
				(log/errorf "Connection exception %s|%s" error-msg ret))
			(let [{:keys [txnid balanceID oldbalanceamt newbalanceamt resultCode resultDesc] :as parsed-body} (parse-ocs-debit-response body)
				  _ (log/infof "Debit Response %s" parsed-body)
				  txnid			(if (nil? txnid) request-id txnid)
				  oldbalanceamt (if (nil? oldbalanceamt) 0 (biginteger oldbalanceamt))
				  newbalanceamt (if (nil? newbalanceamt) 0 (biginteger newbalanceamt))
				  amountdebited (- (biginteger oldbalanceamt) (biginteger newbalanceamt))
				  _ (log/infof "Debit -> subscriber %s, amount-debited %s" subscriber (- (biginteger oldbalanceamt) (biginteger newbalanceamt)))]
				(if (= "0" (str resultCode))
					{:request-id request-id :subscriber subscriber :amount-requested amount :txnid txnid
					 :balanceID  balanceID :oldbalanceamt oldbalanceamt :newbalanceamt newbalanceamt
					 :recovered  amountdebited
					 :mismatch (cond (= 0 amountdebited) false
								   (= (biginteger amount) amountdebited) false
								   :else true)
					 :resultCode resultCode
					 :resultDesc resultDesc}
					(do
						(log/errorf "!debitBalance(%s) -> %s" {:rid request-id :sub subscriber :amount-requested amount} parsed-body)
						{:request-id request-id :subscriber subscriber :amount-requested amount :txnid txnid :recovered 0 ::mismatch nil
						 :balanceID balanceID :oldbalanceamt oldbalanceamt :newbalanceamt newbalanceamt :resultCode resultCode :resultDesc resultDesc}))))))



