(ns heap-analyzer.core
  (:import [org.netbeans.lib.profiler.heap
            Instance JavaClass ObjectArrayInstance PrimitiveArrayInstance
            FieldValue ObjectFieldValue GCRoot JavaFrameGCRoot ThreadObjectGCRoot]))

;; The HeapDumpService instance — set by Java before this ns is loaded
(def ^:dynamic *service* nil)

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

(defn- resolve-value
  "Resolve an Instance to its value if it's a well-known type.
   String → string, Keyword → :keyword, boxed primitive → value.
   Returns nil for unknown types."
  [^Instance inst]
  (when inst
    (let [cn (class-name inst)]
      (case cn
        "java.lang.String"
        (try (.getStringValue *service* (.getInstanceId inst))
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
        (.getValueOfField inst "value")

        nil))))

(defn- field-values-raw
  "Return raw field values as a vec of maps."
  [^Instance inst]
  (vec
   (for [fv (.getFieldValues inst)
         :let [fv (cast FieldValue fv)
               fname (.getName (.getField fv))]]
     (if (instance? ObjectFieldValue fv)
       (let [ref (.getInstance ^ObjectFieldValue fv)]
         (if ref
           {:name fname :id (.getInstanceId ref) :class (class-name ref)
            :ref ref}
           {:name fname :value nil}))
       {:name fname :value (.getValue fv)}))))

;; === Core primitives ===

(defn instance
  "Get instance details by ID. Returns a map with :id, :class, :size, :retained, :fields."
  [id]
  (let [inst (.getInstanceByID (get-heap) (long id))]
    (when inst
      {:id (.getInstanceId inst)
       :class (class-name inst)
       :size (.getSize inst)
       :retained (.getRetainedSize inst)
       :fields (field-values-raw inst)})))

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

(defn fields
  "Get an instance's fields with common types auto-resolved.
   Strings become strings, Keywords become keywords, boxed primitives unwrap.
   Other references stay as {:id ... :class ...}."
  [id]
  (let [inst (.getInstanceByID (get-heap) (long id))]
    (when inst
      (into {}
            (for [fv (.getFieldValues inst)
                  :let [fv (cast FieldValue fv)
                        fname (keyword (.getName (.getField fv)))]]
              (if (instance? ObjectFieldValue fv)
                (let [ref (.getInstance ^ObjectFieldValue fv)]
                  (if ref
                    (let [resolved (resolve-value ref)]
                      (if (some? resolved)
                        [fname resolved]
                        [fname {:id (.getInstanceId ref) :class (class-name ref)}]))
                    [fname nil]))
                [fname (.getValue fv)]))))))

(defn elements
  "Get elements from an Object[] or ArrayList. Returns vec of resolved values or {:id :class} maps."
  ([id] (elements id 0 50))
  ([id from to]
   (let [inst (.getInstanceByID (get-heap) (long id))
         arr (cond
               (instance? ObjectArrayInstance inst) inst
               (= (class-name inst) "java.util.ArrayList")
               (let [ed (.getValueOfField inst "elementData")]
                 (when (instance? ObjectArrayInstance ed) ed))
               :else (throw (IllegalArgumentException.
                             (str "Not an array or ArrayList: " (class-name inst)))))
         values (.getValues ^ObjectArrayInstance arr)
         len (.getLength ^ObjectArrayInstance arr)
         safe-from (max 0 (min from len))
         safe-to (max safe-from (min to len))]
     (vec
      (for [i (range safe-from safe-to)
            :let [elem (.get values i)]]
        (if (nil? elem)
          nil
          (let [resolved (resolve-value elem)]
            (if (some? resolved)
              resolved
              {:id (.getInstanceId ^Instance elem)
               :class (class-name elem)}))))))))

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
  "Get instances of a class by name."
  ([class-name] (instances class-name 0 50))
  ([class-name from to]
   (let [insts (.getInstancesByClass *service* class-name (int from) (int to))]
     (vec (for [i insts]
            {:id (.instanceId i) :class (.className i)
             :size (.size i) :retained (.retainedSize i)})))))

(defn classes-matching
  "Find classes matching a regex."
  ([regexp] (classes-matching regexp 0 50))
  ([regexp from to]
   (let [classes (.getJavaClassesByRegExpPaginated *service* regexp (int from) (int to))]
     (vec (for [c classes]
            {:name (.getName c) :instances (.getInstancesCount c)})))))

(defn biggest
  "Get biggest objects by retained size."
  ([] (biggest 10))
  ([n]
   (let [objs (.getBiggestObjectsWithOwner *service* (int n))]
     (vec (for [o objs]
            (cond-> {:id (.instanceId o) :class (.className o)
                     :retained (.retainedSize o) :shallow (.shallowSize o)}
              (.ownerClass o) (assoc :owner {:class (.ownerClass o) :id (.ownerId o)})))))))

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
       :retained (.getRetainedSize inst)
       :fields (fields id)})))
