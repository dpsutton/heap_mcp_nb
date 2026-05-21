# Heap Dump Analysis Skill

## Overview

This skill provides a systematic methodology for analyzing Java `.hprof` heap dumps using the `heap-analyzer` MCP server. It codifies the approach used to diagnose OOM crashes: start broad, identify the dominant retained object, then trace through the object graph to understand what code created it and why.

## Prerequisites

The `heap-analyzer` MCP server must be configured. Add to `.mcp.json` in your working directory:

```json
{
  "mcpServers": {
    "heap-analyzer": {
      "command": "java",
      "args": ["-jar", "/Users/dan/projects/java/heap_mcp_nb/target/heap_mcp_nb-1.0-SNAPSHOT.jar"]
    }
  }
}
```

Then use `/mcp` in Claude Code to enable it.

## Available Tools

### Core tools
- `load_heap` — Load a .hprof file
- `get_summary` — Heap summary (total instances, total bytes)
- `get_system_properties` — JVM system properties from the dump
- `get_biggest_objects` — Top N objects by retained size

### Class inspection
- `get_classes_by_max_instances_count` — Classes sorted by instance count (paginated)
- `get_classes_by_max_instances_size` — Classes sorted by total size (paginated)
- `get_classes_by_regexp` — Regex class name search (paginated)
- `get_class_by_name` — Class details by fully qualified name
- `get_class_by_id` — Class details by internal ID

### Instance inspection
- `get_instance_by_id` — Full instance details: class, size, retained size, all field values
- `get_instances_by_class` — List instances of a class with IDs and sizes (paginated)
- `get_all_references` — Objects that reference a given instance (paginated)

### Data reading
- `get_string_value` — Decode a java.lang.String to text (handles Latin-1 and UTF-16 compact strings)
- `get_byte_array_contents` — Read byte[] contents as text or hex
- `get_array_element` — Single element from Object[] or ArrayList by index
- `get_array_elements` — Range of elements from Object[] or ArrayList (paginated)
- `get_map_entries` — Key-value pairs from HashMap, LinkedHashMap, PersistentArrayMap, or PersistentHashMap (paginated, auto-decodes String keys)

### Retention analysis
- `get_gc_roots` / `get_gc_roots_paginated` — GC root listing
- `get_gc_root_for` — Trace path from an object to its nearest GC root, with thread name, frame number, and stack trace
- `get_threads` — All threads with names and stack traces
- `get_retained_breakdown` — Top classes by size within an object's reachable graph
- `get_dominator_tree` — Dominator tree rooted at an object, children sorted by retained size
- `find_path` — BFS reference chain between two objects

### Batch
- `get_string_values_bulk` — Decode multiple Strings in one call

### Query
- `execute_oql` — OQL queries (bootstrap classloader classes only; use `get_instances_by_class` for app classes)
- `execute_clojure` — Evaluate Clojure expressions with full access to the heap (see below)

## Analysis Methodology

### Phase 1: Load and Orient

1. **Find the dump file:** Glob for `**/*.hprof` or `**/*.dump`
2. **Load it:** `load_heap` with the file path
3. **Get the summary:** `get_summary` for total instance count and heap size
4. **Get system properties:** `get_system_properties` to understand the JVM — what app is running, what version, OS, etc.
5. **Get the biggest objects:** `get_biggest_objects` with limit 20 — this almost always reveals the OOM culprit immediately

At this point you should know: total heap size, what the app is, and which object(s) dominate retained size.

### Phase 2: Identify the Dominator

Look at the `get_biggest_objects` output. Typically one object tree dominates 60-90% of the heap. Key things to note:

- **The class name** tells you what kind of object (array, list, map, frame, etc.)
- **Retained size vs shallow size** — a small object retaining gigabytes means it's the root of a huge tree
- **Multiple entries with similar retained sizes** often point to the same tree (parent -> child -> grandchild)

Use `get_instance_by_id` on the top entries to inspect their fields and understand the parent-child relationships.

**Use `get_retained_breakdown`** on the dominator to immediately see what classes make up its retained set — e.g., "600 MB in Strings, 200 MB in byte[], 100 MB in PersistentArrayMap" — without manually exploring.

**Use `get_dominator_tree`** for a MAT-style view of what the object retains, sorted by retained size at each level.

### Phase 3: Trace the Object Graph

Starting from the dominator, walk the object graph:

1. **Inspect the dominator:** `get_instance_by_id` — read its fields
2. **Follow references down:** inspect child objects that carry the retained size
3. **Follow references up:** `get_all_references` to find what points TO the dominator (what's keeping it alive)
4. **Identify the collection:** Often the issue is a List/Array/Map that has grown too large. Check its `length`/`size`/`capacity`.

**Use `find_path`** if you need to understand the reference chain between two specific objects.

### Phase 4: Understand the Data

Once you've found the large collection, understand what's in it:

**For arrays and ArrayLists:**
```
get_array_elements(id, from=0, to=20)
```
This returns each element's class and instance ID. Follow up with `get_instance_by_id` on interesting elements.

**For HashMaps and Clojure maps:**
```
get_map_entries(id, from=0, to=20)
```
This returns key-value pairs with String keys auto-decoded. Works with HashMap, LinkedHashMap, PersistentArrayMap, and PersistentHashMap (including the Clojure trie structure).

**For Strings:**
```
get_string_value(id)
```
Directly decodes the String — no more OQL IIFE workarounds. Handles both Latin-1 (coder=0) and UTF-16 (coder=1).

**For byte arrays:**
```
get_byte_array_contents(id, encoding="latin1")  // or "utf16" or "hex"
```

### Phase 5: Find the Code Path

#### Primary method: get_gc_root_for

**This is now a single tool call.** Given any object, `get_gc_root_for` traces the path to its nearest GC root and returns:
- The full reference chain from object to root
- The GC root kind (Java frame, thread object, etc.)
- The thread name
- The frame number
- The full stack trace

This immediately answers "what code is keeping this object alive?" without manually paging through GC roots.

#### Complementary: get_threads

Use `get_threads` to see all threads with their stack traces. This answers "what was running when the OOM hit?" in one call.

#### Reconstructing Quartz/scheduler context

When you find Quartz classes on the stack, trace the job details:
1. Find `CronTriggerImpl` or `SimpleTriggerImpl` -> read `name`, `jobName`, `jobGroup` fields
2. Find `JobDetailImpl` -> read `name`, `jobClass` fields
3. Use `get_map_entries` on the `jobDataMap` to read the job parameters directly

#### Inspecting closure captures

Framework closures (Clojure `IFn` implementations, Java lambdas) often have captured variables as instance fields. Use `get_instance_by_id` on the closure object — its fields are the closed-over values.

### Phase 6: Class Histograms and Instance Listing

- `get_classes_by_max_instances_count` — which classes have the most instances?
- `get_classes_by_max_instances_size` — which classes use the most total memory?
- `get_classes_by_regexp` — search for specific class patterns
- **`get_instances_by_class`** — list actual instances of a class with their IDs and sizes. This is the primary way to find instances of app-loaded classes that are invisible to OQL.

These help you answer: "Is this one huge object, or millions of small ones?" and "Show me the actual instances."

## ID Space

**All tools use the same decimal ID space.** IDs returned by `get_instance_by_id`, `get_biggest_objects`, `get_array_elements`, `get_map_entries`, `get_instances_by_class`, etc. are all interchangeable. You can take an ID from any tool output and pass it to any other tool.

OQL's `heap.findObject(id)` also uses the same decimal IDs.

## Dealing with App Classloader Visibility

In applications with custom classloaders (Metabase, OSGi apps, application servers), many classes are **invisible to OQL** but **visible to all other tools**.

### How to detect this
- OQL queries like `select s from com.myapp.MyClass s` return "class not found"
- But `get_class_by_name("com.myapp.MyClass")` works fine

### What to use instead of OQL for app classes
- **`get_instances_by_class`** — lists instances of any class, regardless of classloader
- **`get_instance_by_id`** — works for any object once you have its ID
- **`get_map_entries`** — reads map contents directly without OQL
- **`get_string_value`** — decodes strings directly without OQL IIFE workarounds

OQL remains useful for bootstrap classes (java.lang.String, java.util.HashMap, etc.) and for complex JavaScript-based analysis.

## OQL Reference

OQL (Object Query Language) supports JavaScript. Key patterns:

### Basic queries
```
select s from java.lang.String s where s.value.length > 10000
```

### JavaScript function pattern (IIFE)
When you need loops, string building, or complex logic:
```
select (function() {
  // your code here
  return result;
})() from java.lang.Object o where true
```

### Useful heap functions
- `heap.findObject(ID)` — get object by decimal ID (same IDs as all other tools)
- `classof(obj).name` — get class name
- `sizeof(obj)` — shallow size
- Array access: `heap.findObject(ARRAY_ID)[index]`

## Clojure Eval — Scripted Heap Analysis

The `execute_clojure` tool runs Clojure expressions in-process with full access to the loaded heap. Use it when you need to compose multiple operations in a single call instead of making sequential tool calls.

**When to use it:** Once you know what you're looking for and need to extract data. The MCP tools are better for initial exploration and orientation.

### Available functions

**Core primitives** (thin wrappers over the MCP tools, return Clojure data):
```clojure
(instance id)              ; → {:id ... :class ... :size ... :retained ... :fields [...]}
(string id)                ; → "the string text"
(strings [id1 id2 id3])    ; → {id1 "text1", id2 "text2", id3 "text3"}
(elements id from to)      ; → [{:id ... :class ...} ...] or resolved values
(entries id from to)        ; → [{:key ... :val ...} ...] with auto-decoding
(references id from to)    ; → [{:id ... :class ...} ...]
(instances "class.Name" from to) ; → [{:id ... :size ... :retained ...} ...]
(classes-matching "regex" from to)
(biggest n)                ; → [{:id ... :class ... :retained ... :owner {...}} ...]
(summary)                  ; → {:instances ... :bytes ... :time ...}
(gc-root id)               ; → {:path [...] :root-kind ... :thread ... :stack [...]}
(threads)                  ; → [{:id ... :name ... :frames n} ...]
(retained-breakdown id n)  ; → [{:class ... :size ... :count ...} ...]
(dominator-tree id depth max-children)
(find-path from-id to-id)
```

**Convenience helpers:**
```clojure
(fields id)        ; All fields resolved one level: Strings→text, Keywords→:kw, boxed→value
(describe id)      ; {:class ... :size ... :retained ... :fields (fields id)}
(resolve-value id) ; Decode a single instance: String/Keyword/boxed primitive → value
(keyword-name id)  ; Read a Clojure keyword's text
```

### Example patterns

```clojure
;; Top 5 biggest objects with all fields resolved (replaces ~15 tool calls)
(->> (biggest 5)
     (mapv #(assoc % :fields (fields (:id %)))))

;; Decode all strings in an array
(->> (elements some-array-id 0 100)
     (filter #(= (:class %) "java.lang.String"))
     (mapv #(string (:id %))))

;; Find what's in a HashMap — keys and values auto-decoded
(entries some-map-id 0 20)
;; => [{:key "void" :val "V"} {:key "boolean" :val "Z"} ...]

;; GC root chain with thread context
(let [root (gc-root suspicious-id)]
  {:thread (:thread root)
   :stack (take 10 (:stack root))
   :object (fields suspicious-id)})

;; Find large ArrayLists and show their sizes
(->> (instances "java.util.ArrayList" 0 50)
     (sort-by :retained >)
     (take 10)
     (mapv #(let [f (fields (:id %))]
              {:id (:id %) :size (:size f) :retained (:retained %)})))

;; Custom: group instances by a field value
(->> (instances "com.example.MyClass" 0 1000)
     (group-by #(get (fields (:id %)) :type))
     (map (fn [[k vs]] [k (count vs)]))
     (into {}))
```

### Key principle

The primitives return data, you write the composition. `(instance id)` is raw truth, `(fields id)` is the one-level-deep convenience. No magic — if you need deeper resolution, compose explicitly.

## GraalPy-Specific Notes

When analyzing GraalPy (Truffle Python) heaps:

- **Python objects** are `PythonObject` with `indexedSlots` (Object[]) for instance attributes
- **Python lists** are `PList` -> `ObjectSequenceStorage` -> `Object[]`
- **Python dicts** are `PDict` -> `ObjectHashMap`
- **Python strings** are `TruffleString` with `data` (byte[]) — use `get_byte_array_contents` to decode
- **Python functions/frames** are `PBytecodeRootNode` with `co` (BytecodeCodeUnit) containing `startLine`, `endLine`, `name`, `qualname`
- **Source files** are in `SourceImpl$ImmutableKey` — `path` and `content` fields

**Memory amplification warning:** GraalPy objects are 50-100x heavier than CPython equivalents.

## Clojure / Metabase-Specific Notes

When analyzing Clojure application heaps (including Metabase):

- **Clojure maps:** Use `get_map_entries` directly on PersistentHashMap or PersistentArrayMap instances. It handles the trie structure (BitmapIndexedNode, ArrayNode, HashCollisionNode) automatically and decodes String keys.
- **Clojure vectors:** `PersistentVector` has `cnt` field.
- **Clojure keywords:** `Keyword` -> `sym` (Symbol) -> `name` (String). Use `get_string_value` on the name.
- **Toucan2 instances:** `toucan2.instance.Instance` has `model` (a keyword) and `m` (a PersistentHashMap). Use `get_map_entries` on `m` to read the record fields.
- **Clojure closures:** Compiled functions are classes like `myapp.namespace$function_name$fn__12345`. Instance fields are the closed-over values.
- **Metabase Quartz jobs:** Look for `SyncAndAnalyzeDatabase`, `SendPulse`, etc. via `get_gc_root_for` or `get_threads`.

## Common Findings

### Pattern: Giant collection from data processing
One list/array/map dominates the heap because the app loaded/generated too much data in one batch. Fix: add limits, pagination, or streaming.

### Pattern: Memory amplification from runtime overhead
The data itself is modest, but the runtime representation is heavy (e.g., GraalPy, deep Clojure data structures). Fix: reduce object count, use more compact representations, or increase heap.

### Pattern: Cache without eviction
A cache (HashMap, ConcurrentHashMap) grows without bound. Fix: add size limits, TTL, or use a proper cache (Caffeine, Guava).

### Pattern: Leaked connections/resources
Many instances of connection, stream, or buffer objects. Fix: ensure proper close/cleanup in finally blocks.

### Pattern: Eager materialization of external query results
A query to an external system returns a large result set that is fully materialized before processing. Fix: stream/reduce results instead of collecting; restructure code so response objects can be GC'd before the next query starts.

### Pattern: Batch size too large for data shape
A batch processing operation uses a fixed batch size that's reasonable for typical data but catastrophic for pathological inputs. Fix: adaptive batch sizing, per-item caps, or pre-check queries to estimate work size.

## Output Template

When presenting findings, structure the report as:

1. **Heap overview** — total size, instance count, app identity
2. **The smoking gun** — what object dominates and how much of the heap it holds
3. **What it contains** — the actual data (strings, records, etc.)
4. **What code created it** — file, function, line numbers
5. **Why it's so big** — the root cause (huge input, amplification, leak, etc.)
6. **Recommendations** — concrete mitigations ranked by effort/impact
