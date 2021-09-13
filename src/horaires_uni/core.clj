(ns horaires-uni.core
  (:require [clojure.spec.alpha :as s]
            [clojure.core.cache :as cache]
            [compojure.core     :refer [defroutes GET POST]]

            [ring.adapter.jetty   :refer [run-jetty]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.util.response   :refer [response]]))

;; Define the shapes of the data
(s/def ::hour (partial re-matches #"[0-2][0-9]h[0-5][0-9]"))
(s/def ::range (s/cat :start ::hour :stop ::hour))
(s/def ::day (s/coll-of ::range))
(s/def ::timetable (s/coll-of ::day))

;; Initial `timetable` state, some ideas about how it could be
;; stored :
;; - In a RDBMS database, as a JSON column or as a raw String
;; - In a document database like MongoDB
;; - As an object in a S3 service
;; - Directly in the FS
;; - In memory
(def timetable
  [[["08h00" "12h00"] ["14h00" "18h00"]]
   [["08h00" "12h00"] ["14h00" "18h00"]]
   [["08h00" "12h00"]]
   [["08h00" "12h00"] ["14h00" "18h00"]]
   [["08h00" "12h00"] ["14h00" "18h00"]]])

;; Timetable is stored in memory in this example
(def state (atom timetable))

;; TTL cache of 10 minutes
(def ttl-cache (cache/ttl-cache-factory {:ttl (* 10 60 1000)}))

(defn get-timetable
  "Get the timetable from the cache
  or fetch it if needed"
  []
  (cache/through-cache ttl-cache :timetable (fn [_] @state)))

(defn change-timetable!
  "Store the new timetable and evict the cache"
  [timetable]
  ;; Validate the timetable against the specification
  (when (s/valid? ::timetable timetable)
    ;; If valid, store the data in the storage
    ;; and evict the cache
    (cache/evict ttl-cache :timetable)
    (reset! state timetable)))

;; Application routes (GET, POST)
(defroutes app-routes
  (GET "/horaires" [] (response (get-timetable)))
  (POST "/horaires" {timetable :body}
        (if (change-timetable! timetable)
          (response (get-timetable))
          (response {:status 400
                     :body "Invalid request"}))))

;; Bind the routes to JSON wrappers
(def handler
  (-> #'app-routes
      (wrap-json-response {:keywords? true})
      (wrap-json-body)))

;; Run the Jetty HTTP server
(run-jetty #'handler
           {:port 9001
            :join? false})
