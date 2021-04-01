(ns ghrec-acs.rmqutils
	(:require
		[langohr.core :as rmq]
		[langohr.basic :as lb]
		[langohr.channel :as lch]
		[langohr.queue :as lq]
		[langohr.exchange :as le]
		[langohr.consumers :as lc]
		[clojure.data.json :as json]
		[clojure.tools.logging :as log]
		[ghrec-acs.utils :as utils]
		[ghrec-acs.config :refer [env]]))


(declare initialize-publisher initialize-consumer create-consumer-handler)


(def ^:dynamic value 1)

;;
(defn initialize-rabbitmq
	[args]
	(try
		(let [{:keys [queue-name queue-exchange queue-routing-key msg channel]} args
					_ (le/declare @channel queue-exchange "direct" {:durable true :auto-delete false})
					queue-name (:queue (lq/declare @channel queue-name {:durable true :auto-delete false}))]
			; bind queue to exchange

			(lq/bind @channel queue-name queue-exchange {:routing-key queue-routing-key})

			(lb/publish @channel queue-exchange queue-routing-key msg {"Content-type" "text/json"})
			;(rmq/close channel)
			;(rmq/close rabbitmq-conn)
			true)
		(catch Exception e (log/error e (.getMessage e))
											 (throw (Exception. (format "unableToConnectToRabbitmq[%s]" (.getMessage e)))))))
;;


(defn ack [channel tag]
	(lb/ack channel tag))
(defn closeQueue [queue]
	(rmq/close queue))


(defn initialize-queue [queue]
	(try
		(let [{:keys [queue-exchange queue-name queue-routing-key handler consumers conn channel retry-queue]
					 :or {consumers 10}} queue
				_ (reset! conn (rmq/connect queue))
				_ (reset! channel (lch/open @conn))
					_ (le/declare @channel queue-exchange "direct" {:durable true :auto-delete false})
					queue-name (:queue (lq/declare @channel queue-name {:durable true :auto-delete false :arguments nil
																		#_(when retry-queue
																			  {"x-dead-letter-exchange"    (:retry-exchange retry-queue)
																			   "x-dead-letter-routing-key" (:retry-routing-key retry-queue)})}))]
				; bind queue to exchange
				(lq/bind @channel queue-name queue-exchange {:routing-key queue-routing-key})
				;(lq/declare @channel queue-name {:durable true :auto-delete false})
				(when handler
					(doseq [count (repeat consumers "x")]
						;(lc/subscribe channel queue-name handler)
						(lc/subscribe @channel queue-name handler {:auto-ack true})))

				#_(when retry-queue
					(let [{:keys [retry-exchange retry-name retry-routing-key retry-delay retry-consumers]
								 :or   {retry-consumers 10}} retry-queue]
						_ (le/declare @channel retry-exchange "direct" {:durable true :auto-delete false})
						_ (log/infof "retry-queue=%s"retry-queue)
						retry-name (:queue (lq/declare @channel retry-name {:durable     true
																															 :auto-delete false :arguments {"x-dead-letter-exchange"    queue-exchange
																																															"x-dead-letter-routing-key" queue-routing-key
																																															"x-message-ttl" retry-delay}}))

						; (lq/declare @channel retry-name {:durable true :arguments {"x-dead-letter-exchange"    queue-exchange
						;																													"x-dead-letter-routing-key" queue-routing-key
						;																													"x-message-ttl"             retry-delay}})
						(lq/bind @channel retry-name retry-exchange {:routing-key retry-routing-key}))))
		(catch Exception e (log/error e (.getMessage e))
											 (throw (Exception. (format "unableToConnectToRabbitmq[%s] -> %s" queue (.getMessage e)))))))

(defn initialize-queue-interfaces [queues]
	(log/infof "initialize-queue-interfaces %s"queues)
	(doseq [queue queues]
		(initialize-queue queue))
	queues)
