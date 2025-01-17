(ns monstr.store
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [monstr.file-sys :as file-sys]            
            [monstr.domain :as domain]
            [monstr.json :as json]
            [monstr.parse :as parse]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def get-datasource*
  (memoize
    #(jdbc/get-datasource (str "jdbc:sqlite:" %))))

(defn- comment-line?
  [line]
  (str/starts-with? line "--"))

(defn parse-schema []
  (let [resource (io/resource "monstr/schema.sql")]
    (with-open [reader (io/reader resource)]
      (loop [lines (line-seq reader) acc []]
        (if (next lines)
          (let [[ddl more] (split-with (complement comment-line?) lines)]
            (if (not-empty ddl)
              (recur more (conj acc (str/join "\n" ddl)))
              (recur (drop-while comment-line? lines) acc)))
          acc)))))

(defn apply-schema! [db]
  (doseq [statement (parse-schema)]
    (jdbc/execute-one! db [statement])))

(defn init!
  [path]
  (doto (get-datasource* path)
    apply-schema!))

(defonce db (init! (file-sys/db-path)))

;; --

(defn- insert-event!*
  [db id pubkey created-at kind content raw-event-tuple]
  {:post [(or (nil? %) (contains? % :rowid))]}
  (jdbc/execute-one! db
    [(str
       "insert or ignore into n_events"
       " (id, pubkey, created_at, kind, content_, raw_event_tuple)"
       " values (?, ?, ?, ?, ?, ?) returning rowid")
     id pubkey created-at kind content raw-event-tuple]
    {:builder-fn rs/as-unqualified-lower-maps}))

(defn insert-event!
  "Answers inserted sqlite rowid or nil if row already exists."
  [db id pubkey created-at kind content raw-event-tuple]
  (:rowid (insert-event!* db id pubkey created-at kind content raw-event-tuple)))

(defn insert-e-tag!
  [db source-event-id tagged-event-id]
  (jdbc/execute-one! db
    [(str
       "insert or ignore into e_tags"
       " (source_event_id, tagged_event_id)"
       " values (?, ?)")
     source-event-id tagged-event-id]))

(defn insert-p-tag!
  [db source-event-id tagged-pubkey]
  (jdbc/execute-one! db
    [(str
       "insert or ignore into p_tags"
       " (source_event_id, tagged_pubkey)"
       " values (?, ?)")
     source-event-id tagged-pubkey]))

(defn- raw-event-tuple->event-obj
  [raw-event-tuple]
  (-> raw-event-tuple json/parse (nth 2)))

(defn load-event
  [db event-id]
  (some->
    (jdbc/execute-one! db ["select e.raw_event_tuple from n_events e where e.id = ?" event-id]
      {:builder-fn rs/as-unqualified-lower-maps})
    :raw_event_tuple
    raw-event-tuple->event-obj))

(defn timeline-query
  [pubkeys]
  [(format (str "select raw_event_tuple from n_events"
                " where pubkey in (%s) and kind = 1"
                " order by created_at"
                " limit 100")
               (str/join ", " (map #(str "'" % "'") pubkeys)))])

(defn load-timeline-events
  [db pubkeys]
  (log/debugf "Loading timeline events for %d pubkeys" (count pubkeys))
  (when-not (empty? pubkeys)
    (mapv (comp raw-event-tuple->event-obj :raw_event_tuple)
          (jdbc/execute! db
                         (timeline-query pubkeys)
                         {:builder-fn rs/as-unqualified-lower-maps}))))


(defn relay-timeline-query
  [relay-url pubkeys]
  [(format (str "select raw_event_tuple from n_events e"
                " inner join relay_event_id r on e.id=r.event_id"
                " where r.relay_url='" relay-url "'"
                " and e.pubkey in (%s) and e.kind = 1"
                " order by e.created_at"
                " limit 100")
               (str/join ", " (map #(str "'" % "'") pubkeys)))])

(defn load-relay-timeline-events
  [db relay-url pubkeys]
  (log/debugf "Loading timeline events for %s with %d pubkeys" relay-url (count pubkeys))
  (when-not (empty? pubkeys)
    (mapv (comp raw-event-tuple->event-obj :raw_event_tuple)
          (jdbc/execute! db
                         (relay-timeline-query relay-url pubkeys)
                         {:builder-fn rs/as-unqualified-lower-maps}))))
  
;; --

(defn load-relays
  [db]
  (mapv
    (fn [{:keys [url read_ write_]}]
      (domain/->Relay url (pos? read_) (pos? write_)))
    (jdbc/execute! db ["select * from relays_"]
      {:builder-fn rs/as-unqualified-lower-maps})))

(defn load-identities
  [db]
  (mapv
    (fn [{:keys [public_key secret_key]}]
      (domain/->Identity public_key secret_key))
    (jdbc/execute! db ["select * from identities_"]
      {:builder-fn rs/as-unqualified-lower-maps})))

(defn- raw-event-tuple->parsed-metadata
  [raw-event-tuple]
  (let []
    (let [{:keys [created_at content] :as _event-obj} (parse/raw-event-tuple->event-obj raw-event-tuple)
          {:keys [name about picture nip05]} (json/parse content)]
      (domain/->ParsedMetadata name about picture nip05 created_at))))

;; todo make efficient via delete by trigger or gc process
(defn load-metadata
  [db pubkeys]
  (into
    {}
    (map (juxt :pubkey #(-> % :raw_event_tuple raw-event-tuple->parsed-metadata)))
    (jdbc/execute! db
      (vec
        (concat
          [(format
             (str "select pubkey, raw_event_tuple, max(created_at) as max_ from n_events"
               " where pubkey in (%s) and kind = 0 and deleted_ is false"
               " group by pubkey")
             (str/join "," (repeat (count pubkeys) "?")))]
          pubkeys))
      {:builder-fn rs/as-unqualified-lower-maps})))

(defn- raw-event-tuple->parsed-contact-list
  [raw-event-tuple]
  (let [{:keys [pubkey created_at] :as event-obj} (-> raw-event-tuple json/parse (nth 2))]
    (domain/->ContactList
      pubkey
      created_at
      (parse/parse-contacts* event-obj))))

;; todo make efficient via delete by trigger or gc process
(defn load-contact-lists
  "Answers {<pubkey> ContactList}."
  [db identities]
  (log/debugf "Loading contact lists for %d identities." (count identities))
  (let [public-keys (mapv :public-key identities)]
    (into
      {}
      (map (juxt :pubkey #(-> % :raw_event_tuple raw-event-tuple->parsed-contact-list)))
      (jdbc/execute! db
        (vec
          (concat
            [(format
               (str "select pubkey, raw_event_tuple, max(created_at) as max_ from n_events"
                 " where pubkey in (%s) and kind = 3 and deleted_ is false"
                 " group by pubkey")
               (str/join "," (repeat (count public-keys) "?")))]
            public-keys))
        {:builder-fn rs/as-unqualified-lower-maps}))))

(defn replace-relays!
  "Answers provided relays on success."
  [db relays]
  (jdbc/with-transaction [tx db]
    (jdbc/execute! tx ["delete from relays_"])
    (jdbc/execute-batch! tx
      "insert into relays_ (url,read_,write_) values (?,?,?)"
      (mapv (fn [{:keys [url read? write?]}] [url read? write?]) relays)
      {}))
  relays)

(defn contains-event-from-relay!
  [db relay-url event-id]
  (jdbc/execute-one! db
    ["insert or ignore into relay_event_id (relay_url, event_id) values (?,?)"
     relay-url event-id]))

(defn contains-event-from-relay?
  [db relay-url event-id]
  (pos?
    (:exists_
      (jdbc/execute-one! db
        ["select exists(select 1 from relay_event_id where event_id = ? and relay_url = ?) as exists_"
         event-id relay-url]
        {:builder-fn rs/as-unqualified-lower-maps}))))

(defn get-seen-on-relays
  [db event-id]
  (vec
    (sort
      (map
        :relay_url
        (jdbc/execute! db
          ["select relay_url from relay_event_id where event_id = ?" event-id]
          {:builder-fn rs/as-unqualified-lower-maps})))))

(defn count-events-on-relays
  [db]
  (let [result (jdbc/execute! db
                              ["select relay_url, count(*) from relay_event_id group by relay_url"])]
    (zipmap (map :relay_event_id/relay_url result)
            (map (keyword "count(*)") result))))
             
  
(defn event-signature-by-id
  "Returns a map from relay urls to event counts."
  [db event-id]
  (:signature_
    (jdbc/execute-one! db
      ["select signature_ from signature_event_id where event_id = ?" event-id]
      {:builder-fn rs/as-unqualified-lower-maps})))

(defn event-signature!
  [db event-id sig]
  (jdbc/execute-one! db
    ["insert or ignore into signature_event_id (event_id, signature_) values (?,?)"
     event-id sig]))

(defn insert-identity!
  [db public-key secret-key]
  ;; secret-key could be nil
  (jdbc/execute-one! db
    [(str "insert into identities_ (public_key, secret_key) values (?,?)"
       " on conflict(public_key) do update set secret_key=?"
       " where excluded.secret_key is not null")
     public-key secret-key secret-key]))

(defn delete-identity!
  [db public-key]
  (jdbc/execute-one! db
    ["delete from identities_ where public_key = ?" public-key]))
