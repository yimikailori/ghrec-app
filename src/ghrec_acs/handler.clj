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
            [ghrec-acs.counters :as counters])
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
         amount			:amount
         time		    :time
         type       :type} params
        _ (log/infof "Received notification params [%s]" params)]
    (if (and subscriber amount time)
      ;; Alright, we have all the stuff that we expect. Proceed.
      (utils/with-func-timed "callQueue-recharge-notification" []
                             (let [args  (json/write-str {:sub subscriber :amount amount :time time
                                                          :attempts 0 :time-queued (System/currentTimeMillis) :type type})]
                               (log/debugf "sendRechargeNotification(%s)" args)
                               (rmqutils/initialize-rabbitmq (assoc @service/send-recovery-details :msg args))
                                 {:status 200
                                  :headers {"Content-Type" "text/plain"}
                                  :body "ok"}))

      (do (utils/increment-counter
            counters/countof-recharge-alerts-bad (request :remote-addr))
          (log/errorf "malformedRequest(%s)" {:sub subscriber :amt amount :time time})
          {:status 400
           :headers {"Content-Type" "text/plain"}
           :body "Bad request"}))))

(defroutes app-routes
  (GET "/erl/gh" request (handle-recovery-request request))
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
