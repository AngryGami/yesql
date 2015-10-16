# Hacked Yesql.

This is fork of [yesql](https://github.com/krisajenkins/yesql) project with additional features that I think are usable.
Initially I added this feature through `with-redefs` [hack](https://github.com/krisajenkins/yesql/issues/94) and was 
happy with that up until now when (as I was afraid initially) multithreading had bitten me.

## Feature 1 - Custom wrappers for internal handlers

Yesql has three types of sql executors (i.e. functions that actually talks to underling jdbc)

```
insert-handler
execute-handler
query-handler
```

It might be useful to override these handlers for various purposes such as mocking during tests:

```clojure
(expect {:select 1}
        ((generate-query-fn {:name "test" :statement "select a from b"}
                            {:query-wrapper (fn [query-h]
                                              (fn [db sql-and-params call-options]
                                                ; here original handler will call jdbc 
                                                ; but we will just return fixed result
                                                {:select 1}
                                                ))}) nil {:connection 1}))
```

Or wrapping into transaction macro

```clojure
(defqueries "my-queries.sql" 
   {:insert-wrapper (fn [handler]
                      (fn [db sql-and-params call-options]
                        (tx/required (handler db sql-and-params call-options))))})
```

## Feature 2 - transaction? parameter support

Speaking of transactions... clojure.java.jdbc supports `transaction?` flag for insert/update/execute operations
and I want to be able control that. It can be done totally using wrappers feature above, but I decided to add
this as default handlers capability just to reduce clutter:

```clojure
(tx/required
  ; these will be executed in single transaction
  (some-generated-from-sql-function {:param1 "value1"} {:connection dbconn :transaction? false})
  (some-generated-from-sql-function1 {:param2 "value2"} {:connection dbconn :transaction? false}))
```

Default value is `true` - i.e. each call creates it's own transaction.

## Feature 3 - Sql pre-processor function

Writing sql sometimes can become tedious and repetitive task when you have to create almost identical sql queries for 
every table in your schema. Sometimes you might need to construct sql dynamically based on whatever parameters you've received
e.g. to not do unnecessary joins or just to change `order by` column. It would be nice if yesql supported some sort of 
temple language that allow to solve cases as above, though there are so many templating frameworks out there - how to choose
best one? Well... best way would be to use whatever you, as a user of library, prefer. This is why sql pre-processor 
function feature here for.

```clojure
; here I use comb templating language to pre-process sql just before it get passed to jdbc
; you free to use whatever transformation suites you best 

(defqueries "my-queries.comb.sql" {:sql-pre-processor-fn (fn [sql options params]
                                                              (comb/eval sql (assoc options :$params params)))
                                   :do-stuff (fn [val] (do-something-with-val val))})

; options parameter above is merge of options that you supply for defqueries  
; and options that you supply as part of function invocation 

(query-processed-with-comb {:param1 "do-stuff-will-do-stuff-with-that-returning-stuff"} {:connection dbconn})

; it is also possible to specify pre-processor for particular call, it will override one specified in defqueries 

(my-very-important-query {:param1 "value1"} {:connection dbconn 
   :sql-pre-processor-fn (fn [sql options params] (println "I did nothing with yours sql, don't blame me") sql)})                                                              
```
Sql file might look something like that
```
-- name: query-processed-with-comb
-- doc columns for select will be calculated from parameters
SELECT <%=(do-stuff $params)%> 
FROM table_with_data

-- name: my-very-important-query
-- doc 
SELECT * from MY_TABLE
```

### Disclaimer

Providing `:sql-pre-processor-fn` key to either `defqueries` or to yesql-generated function call will cancel query 
parsing optimization that was introduced in 0.5.1 version of the original yesql library 
([patch](https://github.com/jstepien/yesql/commit/730dae9c1361677c15a03f2e63b9f558d99875e8)).
I.e. query text will be parsed every time you call function. If no sql-pre-processor-fn defined - optimization will be applied.

Beware of sql-injections. Since dynamic sql is... well, dynamic - there is always a chance for that.