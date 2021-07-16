(ns ghrec-acs.handler
    (:require [compojure.core :refer :all]
              [compojure.route :as route]
              [mount.core :as mount]
              [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
              [ghrec-acs.db :as db :refer [*db*]]
              [ghrec-acs.config :refer [env]]
              [ghrec-acs.service :as service]
              [clojure.tools.logging :as log]
              [ghrec-acs.utils :as utils]
              [ghrec-acs.rmqutils :as rmqutils]
              [clojure.data.json :as json]
              [ghrec-acs.counters :as counters]
              [clojure.core.async :as async]
              [clj-time.format :as f]
              [clj-time.local :as l])
  (:import (java.util Timer TimerTask Date)))


(mount/defstate init
                :start (fn []
                         (log/info "\n-=[ghrec-acs started successfully]=-"))
                :stop  (fn []
                         (log/info "\n-=[ghrec-acs stopped successfully]=-")))

(def mythread (atom nil))

(defn destroy
  "destroy will be called when your application
   shuts down, put any clean up code here"
  []
  (doseq [component (:stopped (mount/stop))]
    (log/info component "stopped"))
  (service/shut-down)
  (shutdown-agents)

  (log/info "ghrec-acs has shut down!"))

(defn handle-recovery-request [request]
  (let [params (:params request)
        {subscriber :sub
         amount		:amount
         time	    :time
         type       :type
         channel    :channel} params
        _ (log/infof "Received notification params [%s]" params)]
    (if (and subscriber amount time)
      ;; Alright, we have all the stuff that we expect. Proceed.
      (utils/with-func-timed "callQueue-recharge-notification" []
                             (let [_ (utils/increment-counter counters/countof-recharge-alerts (keyword type))
                                   args  (json/write-str {:sub subscriber :amount amount :time time
                                                          :attempts 0 :time-queued (System/currentTimeMillis) :type type :channel channel})]
                                 (if (= channel "E")
                                     (log/warnf "Recovery AlertOfLendingTransaction (%s)" params)
                                     (do
                                         (log/debugf "sendRechargeNotification(%s)" args)
                                         (async/go (rmqutils/initialize-rabbitmq (assoc @service/send-recovery-details :msg args)))))

                                 {:status 200
                                  :headers {"Content-Type" "text/plain"}
                                  :body "ok"}))

      (do (swap! counters/countof-recharge-alerts-bad inc)
          (log/errorf "malformedRequest(%s)" {:sub subscriber :amt amount :time time})
          {:status 400
           :headers {"Content-Type" "text/plain"}
           :body "Bad request"}))))






(defn get-statistics [reset?]
    (let [now    (f/unparse
                     (f/formatters :mysql) (l/local-now))
          result (conj {:time                        now
                        :uptime                      @counters/*server-start-time*
                        ;; Counters.
                        :recharge-alerts             @counters/countof-recharge-alerts
                        :recharge-alerts-bad         @counters/countof-recharge-alerts-bad
                        ;--
                        :icc-succeeded               @counters/countof-icc-succeeded
                        :icc-failed                  @counters/countof-icc-failed
                        ;; ---
                        :getbalance-attempts         @counters/countof-getbalance-attempts
                        :recovery-attempts           @counters/countof-recovery-attempts
                        :successful-recoveries       @counters/countof-recovery-attempts-succeeded
                        :total-recoveries            @counters/total-recovered


                        })
          result (utils/rename-nil-keys result :null)
          _ (log/debugf "get-stats %s"result)
          result (json/write-str result)]
        (when reset?
            (counters/resetcounters))
        result))



(defn- statistics []
    {:status 200
     :headers {"Content-Type" "text/json"}
     :body (get-statistics false)})

(defn- get-and-reset-statistics []
    {:status 200
     :headers {"Content-Type" "text/json"}
     :body (get-statistics true)})




(defroutes app-routes
  (GET "/erl/gh" request (handle-recovery-request request))
    (GET "/statistics" request (statistics))
    (GET "/get-and-reset-statistics" request (get-and-reset-statistics))
  (route/not-found "Not Found"))

(def app
  (wrap-defaults app-routes api-defaults))

(defn- start-sweeper [sweeper-interval-delay batch-size sweeper-max-thread]
  (do
    (reset! mythread (doto
                       (Timer. "thread-pool-sweeper" true)
                       (.scheduleAtFixedRate (proxy [TimerTask] []
                                               (run [] (when-not (zero? sweeper-interval-delay)
                                                         (log/infof "startSweeper(%s)"sweeper-interval-delay)
                                                         (service/run-sweep-recovery sweeper-max-thread batch-size))))
                                             (Date.) (if (zero? sweeper-interval-delay) 1000 sweeper-interval-delay))))))




(defn init-app []
  "init will be called once when
	app is deployed as a servlet on
	an app server such as Tomcat
	put any initialization code here"
  #_(reset! counters/*server-start-time* (f/unparse
                                         (f/formatters :mysql) (l/local-now)))
  (doseq [component (:started (mount/start))]
    (log/info component "started"))
  ;(log/infof "env=>%s"env)
  (let [factor (env :denom-factor)
        _ (when (nil? factor)
              (throw (RuntimeException. (format "!found(denom-factor=%s) " factor))))
        _ (service/initialize-queue)
        sweeper-task (get-in env [:sweep :sweeper-task])
        sweeper-interval-delay (get-in env [:sweep :sweeper-interval-delay])
        sweeper-batch-size (get-in env [:sweep :sweeper-batch-size])
        sweeper-max-thread  (get-in env [:sweep :sweeper-max-thread])]
    (if (zero? sweeper-task)
      (log/warn "sweeperDisabled()")
      (start-sweeper sweeper-interval-delay sweeper-batch-size sweeper-max-thread))))
