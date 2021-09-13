(ns horaires-uni.core
  (:require [clojure.spec.alpha :as s]
            [clojure.core.cache.wrapped :as cache]
            [compojure.core     :refer [defroutes GET POST]]

            [next.jdbc :as jdbc]

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

(defprotocol TimetableStore
  "Interface to store and get the timetable"
  (-get-timetable [_])
  (-update-timetable [_ timetable]))

;; In memory storage (implementation of TimetableStore)
(defn make-inmemory-store []
  (let [store (atom timetable)]
    (reify TimetableStore
      (-get-timetable [_] (deref store))
      (-update-timetable [_ timetable] (reset! store timetable)))))

;; Filesystem storage (implementation of TimetableStore)
(defn make-fs-store []
  ;; Ensure timetable.json exists by creating it
  (let [_ (spit "timetable.json" (pr-str timetable))]
    (reify TimetableStore
      (-get-timetable [_] (read-string (slurp "timetable.json")))
      (-update-timetable [_ timetable] (spit "timetable.json" (pr-str timetable))))))

;; DB storage (implementation of TimetableStore)
(defn make-db-store []
  (let [db {:dbtype "h2" :dbname "mezza"}
        ;; Ensure table TIMETABLES exists with data by creating it
        _ (jdbc/execute! db ["DROP TABLE IF EXISTS TIMETABLES"])
        _ (jdbc/execute! db ["CREATE TABLE TIMETABLES (TIMETABLE VARCHAR(2000))"])
        _ (jdbc/execute-one! db ["INSERT INTO TIMETABLES (TIMETABLE) VALUES (?)" (pr-str timetable)])]
    (reify TimetableStore
      (-get-timetable [_] (->> (jdbc/execute! db ["SELECT TIMETABLE FROM TIMETABLES"]) (first) (first) (second) (read-string)))
      (-update-timetable [_ timetable] (jdbc/execute-one! db ["UPDATE TIMETABLES SET TIMETABLE=?" (pr-str timetable)])))))

;; Choose your implementation (make-inmemory-store, make-fs-store, make-db-store)
(def store (make-db-store))

;; TTL cache of 10 minutes
(def ttl-cache (cache/ttl-cache-factory {:ttl (* 10 60 1000)}))

(defn get-timetable
  "Get the timetable from the cache
  or fetch it if needed"
  []
  (cache/lookup-or-miss ttl-cache :timetable (fn [_] (-get-timetable store))))

(defn change-timetable!
"Store the new timetable and evict the cache"
[timetable]
;; Validate the timetable against the specification
(when (s/valid? ::timetable timetable)
  ;; If valid, store the data in the storage
  ;; and evict the cache
  (cache/evict ttl-cache :timetable)
  (-update-timetable store timetable)))

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
