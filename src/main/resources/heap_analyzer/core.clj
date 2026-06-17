(ns heap-analyzer.core
  (:import [org.netbeans.lib.profiler.heap
            Instance JavaClass ObjectArrayInstance PrimitiveArrayInstance
            FieldValue ObjectFieldValue GCRoot JavaFrameGCRoot ThreadObjectGCRoot]))

;; The HeapDumpService instance — set by Java before this ns is loaded
(def ^:dynamic *service* nil)

;; Maximum string length for resolve-value truncation (not for explicit `string` calls)
(def ^:dynamic *max-string-length* 200)

;; === Internal helpers ===

(defn- get-heap []
  (.getHeap *service*))

(defn- class-name [^Instance inst]
  (when inst
    (let [cls (.getJavaClass inst)]
      (when (and cls (.getName cls))
        (let [name (.getName cls)]
          (if (= name "java.lang.Class")
            (let [cn (.getValueOfField inst "name")]
              (if cn (str name "<" cn ">") name))
            name))))))

(defn- resolve-string [^Instance inst]
  (when (and inst (= (class-name inst) "java.lang.String"))
    (try (.getStringValue *service* (.getInstanceId inst))
         (catch Exception _ nil))))

(defn- parse-primitive
  "Parse a string representation of a primitive to its actual type.
   Returns numbers, booleans, or the original string."
  [^String s]
  (when s
    (case s
      "true" true
      "false" false
      (if (and (pos? (.length s))
               (let [c (.charAt s 0)]
                 (or (Character/isDigit c) (= c \-))))
        (try (Long/parseLong s)
             (catch NumberFormatException _
               (try (Double/parseDouble s)
                    (catch NumberFormatException _ s))))
        s))))

(defn resolve-value
  "Resolve an Instance to its value if it's a well-known type.
   String → string, Keyword → :keyword, boxed primitive → value.
   Returns nil for unknown types."
  [^Instance inst]
  (when inst
    (let [cn (class-name inst)]
      (case cn
        "java.lang.String"
        (try (let [s (.getStringValue *service* (.getInstanceId inst))]
               (if (and s (> (.length s) *max-string-length*))
                 (str (.substring s 0 *max-string-length*) "...")
                 s))
             (catch Exception _ nil))

        "clojure.lang.Keyword"
        (let [sym (.getValueOfField inst "sym")]
          (when (instance? Instance sym)
            (let [ns-str (resolve-string (.getValueOfField ^Instance sym "ns"))
                  name-str (resolve-string (.getValueOfField ^Instance sym "name"))]
              (when name-str
                (if ns-str
                  (keyword ns-str name-str)
                  (keyword name-str))))))

        "clojure.lang.Symbol"
        (let [ns-str (resolve-string (.getValueOfField inst "ns"))
              name-str (resolve-string (.getValueOfField inst "name"))]
          (when name-str
            (if ns-str (symbol ns-str name-str) (symbol name-str))))

        ("java.lang.Long" "java.lang.Integer" "java.lang.Short" "java.lang.Byte"
         "java.lang.Double" "java.lang.Float" "java.lang.Boolean" "java.lang.Character")
        (let [v (.getValueOfField inst "value")]
          (if (string? v) (parse-primitive v) v))

        nil))))

(defn- resolve-field-value
  "Resolve a FieldValue to a [keyword-name, value] pair.
   Object references: resolved if well-known type, else {:id :class}.
   Primitives: parsed to actual numeric/boolean values."
  [^FieldValue fv]
  (let [fname (keyword (.getName (.getField fv)))]
    (if (instance? ObjectFieldValue fv)
      (let [ref (.getInstance ^ObjectFieldValue fv)]
        (if ref
          (let [resolved (resolve-value ref)]
            (if (some? resolved)
              [fname resolved]
              [fname {:id (.getInstanceId ref) :class (class-name ref)}]))
          [fname nil]))
      [fname (parse-primitive (.getValue fv))])))

;; === Core primitives ===

(defn fields
  "Get an instance's fields as a map with common types auto-resolved.
   Strings→text, Keywords→:kw, boxed primitives→values, numeric primitives→numbers.
   Other references stay as {:id ... :class ...}."
  [id]
  (let [inst (.getInstanceByID (get-heap) (long id))]
    (when inst
      (into {} (map resolve-field-value) (.getFieldValues inst)))))

(defn- safe-retained [^Instance inst]
  (try (.getRetainedSize inst) (catch Exception _ 0)))

(defn instance
  "Get instance details by ID.
   Returns {:id :class :size :retained :fields {keyword-name → value}}."
  [id]
  (let [inst (.getInstanceByID (get-heap) (long id))]
    (when inst
      {:id (.getInstanceId inst)
       :class (class-name inst)
       :size (.getSize inst)
       :retained (safe-retained inst)
       :fields (fields id)})))

(defn field
  "Get a single field value from an instance. Reads only the requested field,
   not all fields — much faster than (get (fields id) :name) for scanning."
  [id field-name]
  (let [inst (.getInstanceByID (get-heap) (long id))
        fname (if (keyword? field-name) (name field-name) (str field-name))]
    (when inst
      (let [raw (.getValueOfField inst fname)]
        (cond
          (instance? Instance raw)
          (let [resolved (resolve-value raw)]
            (if (some? resolved) resolved {:id (.getInstanceId ^Instance raw) :class (class-name raw)}))

          (string? raw) (parse-primitive raw)
          :else raw)))))

(defn string
  "Decode a java.lang.String instance to text."
  [id]
  (.getStringValue *service* (long id)))

(defn strings
  "Decode multiple String instances. Returns a map of id → text."
  [ids]
  (into {} (map (fn [id]
                  [id (try (string id)
                           (catch Exception e (str "<error: " (.getMessage e) ">")))]))
        ids))

(defn elements
  "Get elements from an Object[], ArrayList, or PersistentVector.
   Returns vec of resolved values or {:id :class} maps."
  ([id] (elements id 0 50))
  ([id from to]
   (let [raw (.getArrayElements *service* (long id) (int from) (int to))]
     (vec (for [e raw]
            (let [v (.value e)]
              (cond
                (and v (not= v "null")) v
                (= (.className e) "null") nil
                :else {:id (.instanceId e) :class (.className e)})))))))

(defn entries
  "Get entries from a HashMap or Clojure map. Returns vec of {:key ... :val ...}."
  ([id] (entries id 0 50))
  ([id from to]
   (let [raw (.getMapEntries *service* (long id) (int from) (int to))]
     (vec
      (for [e raw]
        (let [ki (when (pos? (.keyId e)) (.getInstanceByID (get-heap) (.keyId e)))
              vi (when (pos? (.valueId e)) (.getInstanceByID (get-heap) (.valueId e)))
              k (or (when ki (resolve-value ki))
                    (when ki {:id (.keyId e) :class (.keyClass e)})
                    nil)
              v (or (when vi (resolve-value vi))
                    (when vi {:id (.valueId e) :class (.valueClass e)})
                    nil)]
          {:key k :val v}))))))

(defn references
  "Get objects that reference a given instance."
  ([id] (references id 0 50))
  ([id from to]
   (let [refs (.getAllReferences *service* (long id) (int from) (int to))]
     (vec (for [r refs]
            {:id (.instanceId r) :class (.className r)})))))

(defn instances
  "Get instances of a class by name. Returns vec of {:id :class :size :retained}."
  ([class-name] (instances class-name 0 50))
  ([class-name from to]
   (let [insts (.getInstancesByClass *service* class-name (int from) (int to))]
     (vec (for [i insts]
            {:id (.instanceId i) :class (.className i)
             :size (.size i) :retained (.retainedSize i)})))))

(defn instance-ids
  "Fast: get just instance IDs for a class. No size computation.
   Use with (field id :some-field) for fast scanning."
  ([class-name] (instance-ids class-name 0 50))
  ([class-name from to]
   (let [cls (.getJavaClassByName (.getHeap *service*) class-name)]
     (when cls
       (let [all (.getInstances cls)
             safe-to (min to (.size all))
             safe-from (min from safe-to)]
         (vec (for [^Instance inst (.subList all safe-from safe-to)]
                (.getInstanceId inst))))))))

(defn classes-matching
  "Find classes matching a regex."
  ([regexp] (classes-matching regexp 0 50))
  ([regexp from to]
   (let [classes (.getJavaClassesByRegExpPaginated *service* regexp (int from) (int to))]
     (vec (for [c classes]
            {:name (.getName c) :instances (.getInstancesCount c)})))))

(defn class-histogram
  "Classes sorted by total size. Returns [{:class ... :count ... :size ...}]"
  ([] (class-histogram 0 20))
  ([from to]
   (let [stats (.getClassesByMaxInstancesSize *service* (int from) (int to))]
     (vec (for [s stats]
            {:class (.className s) :count (.instanceCount s) :size (.size s)})))))

(defn- collection-count
  "Try to read the count/size of a known collection type. Returns nil if unknown."
  [^Instance inst]
  (let [cn (class-name inst)]
    (case cn
      "java.util.ArrayList"
      (let [v (.getValueOfField inst "size")]
        (when v (if (number? v) (long v) (parse-primitive (str v)))))

      ("java.util.HashMap" "java.util.LinkedHashMap")
      (let [v (.getValueOfField inst "size")]
        (when v (if (number? v) (long v) (parse-primitive (str v)))))

      "clojure.lang.PersistentVector"
      (let [v (.getValueOfField inst "cnt")]
        (when v (if (number? v) (long v) (parse-primitive (str v)))))

      "clojure.lang.PersistentHashMap"
      (let [v (.getValueOfField inst "count")]
        (when v (if (number? v) (long v) (parse-primitive (str v)))))

      "clojure.lang.PersistentArrayMap"
      (let [arr (.getValueOfField inst "array")]
        (when (instance? ObjectArrayInstance arr)
          (/ (.getLength ^ObjectArrayInstance arr) 2)))

      nil nil

      ;; default — check for raw arrays
      (cond
        (instance? ObjectArrayInstance inst) (.getLength ^ObjectArrayInstance inst)
        :else nil))))

(defn biggest
  "Get biggest objects by retained size. Includes :count for known collection types."
  ([] (biggest 10))
  ([n]
   (let [objs (.getBiggestObjectsWithOwner *service* (int n))]
     (vec (for [o objs]
            (let [inst (.getInstanceByID (get-heap) (.instanceId o))
                  cnt (when inst (collection-count inst))]
              (cond-> {:id (.instanceId o) :class (.className o)
                       :retained (.retainedSize o) :shallow (.shallowSize o)}
                (.ownerClass o) (assoc :owner {:class (.ownerClass o) :id (.ownerId o)})
                cnt (assoc :count cnt))))))))

(defn summary
  "Get heap summary."
  []
  (let [s (.getSummary *service*)]
    {:instances (.getTotalLiveInstances s)
     :bytes (.getTotalLiveBytes s)
     :time (.getTime s)}))

(defn gc-root
  "Trace path from an object to its nearest GC root."
  [id]
  (let [info (.getGCRootFor *service* (long id))]
    {:path (vec (for [p (.path info)]
                  {:id (.instanceId p) :class (.className p)}))
     :root-kind (.rootKind info)
     :thread (.threadName info)
     :frame (.frameNumber info)
     :stack (when (.stackTrace info)
              (vec (for [f (.stackTrace info)]
                     (str f))))}))

(defn threads
  "Get all threads with names and stack traces."
  []
  (let [ts (.getThreads *service*)]
    (vec (for [t ts]
           {:id (.instanceId t)
            :name (.threadName t)
            :frames (when (.stackTrace t)
                      (count (.stackTrace t)))}))))

(defn retained-breakdown
  "Show top classes by size within an object's reachable graph."
  ([id] (retained-breakdown id 20))
  ([id n]
   (let [entries (.getRetainedBreakdown *service* (long id) (int n))]
     (vec (for [e entries]
            {:class (.className e) :size (.totalSize e) :count (.instanceCount e)})))))

(defn dominator-tree
  "Show dominator tree rooted at an object."
  ([id] (dominator-tree id 3 10))
  ([id depth] (dominator-tree id depth 10))
  ([id depth max-children]
   (letfn [(walk [node]
             (cond-> {:id (.instanceId node) :class (.className node)
                      :retained (.retainedSize node) :shallow (.shallowSize node)}
               (seq (.children node))
               (assoc :children (vec (map walk (.children node))))))]
     (walk (.getDominatorTree *service* (long id) (int depth) (int max-children))))))

(defn find-path
  "Find reference chain between two objects."
  ([from-id to-id] (find-path from-id to-id 50))
  ([from-id to-id max-depth]
   (let [path (.findPath *service* (long from-id) (long to-id) (int max-depth))]
     (vec (for [p path]
            {:id (.instanceId p) :class (.className p)})))))

;; === Convenience ===

(defn keyword-name
  "Read a Clojure keyword's text representation."
  [id]
  (let [inst (.getInstanceByID (get-heap) (long id))]
    (when (and inst (= (class-name inst) "clojure.lang.Keyword"))
      (resolve-value inst))))

(defn describe
  "Quick one-level description: instance fields with common types resolved.
   Returns {:class ... :size ... :retained ... :fields {...}}."
  [id]
  (let [inst (.getInstanceByID (get-heap) (long id))]
    (when inst
      {:class (class-name inst)
       :size (.getSize inst)
       :retained (safe-retained inst)
       :fields (fields id)})))

(defn describe-all
  "Describe multiple instances. Returns a vec of describe results."
  [ids]
  (mapv describe ids))
