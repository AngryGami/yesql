(ns yesql.generate-test
  (:require [expectations :refer :all]
            [clojure.template :refer [do-template]]
            [yesql.statement-parser :refer [tokenize]]
            [yesql.generate :refer :all]))

(do-template [statement _ expected-parameters]
             (expect expected-parameters
                     (expected-parameter-list statement))

             "SELECT * FROM user"
             => #{}

             "SELECT * FROM user WHERE user_id = ?"
             => #{:?}

             "SELECT * FROM user WHERE user_id = :name"
             => #{:name}

             "SELECT * FROM user WHERE user_id = :name AND country = :country AND age IN (?,?)"
             => #{:name :country :?})

;;; Testing in-list-parmaeter for "IN-list" statements.
(expect [true true true false false]
        (map in-list-parameter?
             (list []
                   (list)
                   (lazy-seq (cons 1 [2]))
                   {:a 1}
                   #{1 2 3})))

;;; Testing reassemble-query
(do-template [statement parameters _ rewritten-form]
             (expect rewritten-form
                     (rewrite-query-for-jdbc (tokenize statement)
                                             parameters))

             "SELECT age FROM users WHERE country = :country"
             {:country "gb"}
             => ["SELECT age FROM users WHERE country = ?" "gb"]

             "SELECT age FROM users WHERE (country = ? OR country = ?) AND name = :name"
             {:?    ["gb" "us"]
              :name "tom"}
             => ["SELECT age FROM users WHERE (country = ? OR country = ?) AND name = ?" "gb" "us" "tom"]

             ;;; Vectors trigger IN expansion
             "SELECT age FROM users WHERE country = :country AND name IN (:names)"
             {:country "gb"
              :names   ["tom" "dick" "harry"]}
             => ["SELECT age FROM users WHERE country = ? AND name IN (?,?,?)" "gb" "tom" "dick" "harry"]

             ;;; Lists trigger IN expansion
             "SELECT age FROM users WHERE country = :country AND name IN (:names)"
             {:country "gb"
              :names   (list "tom" "dick" "harry")}
             => ["SELECT age FROM users WHERE country = ? AND name IN (?,?,?)" "gb" "tom" "dick" "harry"]

             ;;; Lazy seqs of cons of vectors trigger IN expansion
             "SELECT age FROM users WHERE country = :country AND name IN (:names)"
             {:country "gb"
              :names   (lazy-seq (cons "tom" ["dick" "harry"]))}
             => ["SELECT age FROM users WHERE country = ? AND name IN (?,?,?)" "gb" "tom" "dick" "harry"]

             ;;; Maps do not trigger IN expansion
             "INSERT INTO json (source, data) VALUES (:source, :data)"
             {:source "google"
              :data   {:a 1}}
             => ["INSERT INTO json (source, data) VALUES (?, ?)" "google" {:a 1}]

             "INSERT INTO json (data, source) VALUES (:data, :source)"
             {:source "google"
              :data   {:a 1}}
             => ["INSERT INTO json (data, source) VALUES (?, ?)" {:a 1} "google"]

             ;;; Empty IN-lists are allowed by Yesql - though most DBs will complain.
             "SELECT age FROM users WHERE country = :country AND name IN (:names)"
             {:country "gb"
              :names   []}
             => ["SELECT age FROM users WHERE country = ? AND name IN ()" "gb"]

             "SELECT * FROM users WHERE group_ids IN(:group_ids) AND parent_id = :parent_id"
             {:group_ids [1 2]
              :parent_id 3}
             => ["SELECT * FROM users WHERE group_ids IN(?,?) AND parent_id = ?" 1 2 3])

(expect {:select 1}
        ((generate-query-fn {:name "test" :statement "select a from b"}
                            {:query-wrapper (fn [query-h]
                                              (fn [db sql-and-params call-options]
                                                {:select 1}))}) nil {:connection 1}))

(expect {:insert 1}
        ((generate-query-fn {:name "test<!" :statement "select a from b"}
                            {:insert-wrapper (fn [query-h]
                                               (fn [db sql-and-params call-options]
                                                 {:insert 1}))}) nil {:connection 1}))

(expect {:execute 1}
        ((generate-query-fn {:name "test!" :statement "select a from b"}
                            {:execute-wrapper (fn [query-h]
                                                (fn [db sql-and-params call-options]
                                                  {:execute 1}))}) nil {:connection 1}))

(expect {:execute "hahaselect a from b"}
        ((generate-query-fn {:name "test" :statement "select a from b"}
                            {:sql-pre-processor-fn (fn [statement query-options call-options] (str "haha" statement))
                             :query-wrapper        (fn [query-h]
                                                     (fn [db [sql & params] call-options]
                                                       {:execute sql}))}) nil {:connection 1}))

(expect {:execute "1hahaselect a from b"}
        ((generate-query-fn {:name "test" :statement "select a from b"}
                            {:query-wrapper (fn [query-h]
                                              (fn [db [sql & params] call-options]
                                                {:execute sql}))}) nil {:sql-pre-processor-fn (fn [statement query-options call-options] (str "1haha" statement))
                                                                        :connection           1}))

;;; Incorrect parameters.
(expect AssertionError
        (rewrite-query-for-jdbc (tokenize "SELECT age FROM users WHERE country = :country AND name = :name")
                                {:country "gb"}))

(expect AssertionError
        (rewrite-query-for-jdbc (tokenize "SELECT age FROM users WHERE country = ? AND name = ?")
                                {}))
