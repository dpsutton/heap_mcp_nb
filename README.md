# Java Heap Dump MCP Server

An [MCP](https://modelcontextprotocol.io) server that lets an AI assistant analyze Java `.hprof` heap dumps — built for diagnosing OOMs in JVM apps, with first-class support for **Clojure** data structures (Metabase, in particular).

Point Claude at a heap dump and ask *"what's eating the heap?"* — it loads the dump, finds the dominant object, walks the reference graph, and decodes the Clojure data along the way. Backed by the NetBeans Profiler heap-analysis library.

## Why this exists (for Metabase devs)

A Metabase heap dump is full of `PersistentVector`, `PersistentArrayMap`, `PersistentHashMap`, `Keyword`, and intern'd `String` objects. Generic heap tools (MAT, jhat) show you the raw object trie — thousands of `PersistentVector$Node` boxes — and leave you to reconstruct the actual data by hand.

This server understands those types:

- **`Keyword`s, `String`s, and boxed primitives are decoded inline** — a field shows `:source/native` or `"SELECT * FROM ..."`, not an instance ID you have to chase.
- **`PersistentVector` / `PersistentArrayMap` / `PersistentHashMap` are flattened** — `get_map_entries` / `get_array_elements` return real key/value pairs, walking the Clojure trie for you.
- **`execute_clojure` runs Clojure *in-process against the dump*** — so you can script the analysis in the same language the data was written in, composing many lookups into one call.

The result: instead of "instance 0x7f… points to instance 0x8a…", you get `{:card-id 42, :dataset_query {:type :query, ...}}`.

## Quick start

```bash
mvn clean package          # builds target/heap_mcp_nb-1.0-SNAPSHOT.jar (shaded, runnable)
```

Register it with your MCP client. For **Claude Code**, drop a `.mcp.json` in your working directory:

```json
{
  "mcpServers": {
    "heap-analyzer": {
      "command": "java",
      "args": ["-jar", "/absolute/path/to/heap_mcp_nb-1.0-SNAPSHOT.jar"]
    }
  }
}
```

Then `/mcp` to enable it, and ask:

> Load the heap dump at `./crash.hprof` and tell me what's retaining the most memory.

A heap dump can be tens of GB on disk; give the JVM headroom with `-Xmx` (e.g. `java -Xmx8g -jar …`) for large dumps.

> **Methodology:** [`SKILL.md`](SKILL.md) is a ready-to-use Claude skill that codifies the full workflow — load → find the dominator → trace the graph → decode the data. Start there if you want the AI to drive the investigation.

## What it can do

The server exposes ~35 tools. The ones you'll actually reach for:

### Orient
| Tool | Use |
|------|-----|
| `load_heap` | Load a `.hprof` file |
| `get_summary` | Total instances + bytes |
| `get_system_properties` | JVM/app identity from the dump |
| `get_biggest_objects` | Top N objects by **retained** size — usually reveals the OOM culprit immediately |

### Find the dominator
| Tool | Use |
|------|-----|
| `get_retained_breakdown` | What classes make up an object's retained set (e.g. "600 MB Strings, 200 MB byte[]") |
| `get_dominator_tree` | MAT-style tree of what an object retains, sorted by retained size |
| `find_path` | Reference chain between two specific objects |
| `get_all_references` | What points *to* an object (what's keeping it alive) |
| `get_gc_root_for` | Path from an object to its nearest GC root, with thread name + stack trace |

### Inspect classes & instances
| Tool | Use |
|------|-----|
| `get_classes_by_max_instances_count` / `_size` | Class histograms (paginated) |
| `get_classes_by_regexp` | Regex class-name search |
| `get_class_by_name` / `get_class_by_id` | Class details: fields, statics, superclass |
| `get_instance_by_id` | Full instance: class, size, retained size, decoded fields |
| `get_instances_by_class` | Instances of a class with IDs and sizes |

### Read the data (Clojure-aware)
| Tool | Use |
|------|-----|
| `get_string_value` / `get_string_values_bulk` | Decode `String`s (Latin-1 + compact UTF-16) |
| `get_byte_array_contents` | `byte[]` as text or hex |
| `get_array_elements` / `get_array_element` | `Object[]`, `ArrayList`, **`PersistentVector`** elements |
| `get_map_entries` | Entries from `HashMap`, `LinkedHashMap`, **`PersistentArrayMap`**, **`PersistentHashMap`** — keys/values auto-decoded |
| `get_threads` | All threads with stack traces |

### Script it
| Tool | Use |
|------|-----|
| `execute_oql` | OQL queries (bootstrap classes; use `get_instances_by_class` for app classes) |
| `execute_clojure` | **Evaluate Clojure against the heap** (see below) |

## `execute_clojure` — scripting the analysis

Once you know what you're looking for, `execute_clojure` lets you compose many lookups into a single call, returning Clojure data. It runs in-process with the heap loaded, exposing thin primitives plus convenience helpers:

```clojure
;; Top 5 biggest objects, each with all fields resolved (replaces ~15 tool calls)
(->> (biggest 5)
     (mapv #(assoc % :fields (fields (:id %)))))

;; Read a PersistentVector directly — the trie is flattened for you
(elements pv-id 0 20)

;; A HashMap / Clojure map with keys and values auto-decoded
(entries some-map-id 0 20)
;; => [{:key :source/native :val "SELECT ..."} {:key :card-id :val 42} ...]

;; Single field, no boilerplate
(field card-id :dataset_query)

;; Where's the memory? (no retained-size computation needed)
(class-histogram 0 20)
;; => [{:class "byte[]" :count 11891538 :size 389411986} ...]

;; GC-root chain with thread context
(let [r (gc-root suspicious-id)]
  {:thread (:thread r), :stack (take 10 (:stack r)), :object (fields suspicious-id)})
```

Primitives like `Keyword`/`String`/boxed numbers are decoded to real Clojure values; other references come back as `{:id … :class …}` for further lookup. Inline-decoded strings truncate at 200 chars by default (`(binding [*max-string-length* 1000] …)` to change it); `(string id)` returns the full value.

See the **Clojure Eval** section of [`SKILL.md`](SKILL.md) for the complete function reference and more patterns.

## Requirements

- Java 17+
- Maven 3.6+

## Running tests

```bash
mvn test
mvn test -Dtest=ClojureEvalUnitTest      # a single test
```

## Project layout

```
HeapDumpTools          MCP adapter — maps tool calls to service methods
  └─ HeapDumpService   core analysis over the NetBeans Profiler heap API
ClojureEvaluator       in-process Clojure eval, backed by heap_analyzer/core.clj
McpServerLauncher      STDIO entry point (main class)
```

Tools are generated from annotated methods via the reflection-based `ToolsFactory`
(see `src/main/java/com/onpositive/analyzer/mcp/reflection/`):

```java
@Tool(name = "my_tool", title = "My Tool", description = "Does something")
public String myToolMethod(@Required("param1") String param1,
                           @Default(name = "param2", value = "50") int param2) { … }
```

## Dependencies

- `io.modelcontextprotocol.sdk:mcp` — MCP Java SDK
- `org.netbeans.modules:org-netbeans-lib-profiler` (RELEASE200) — heap analysis
- `org.netbeans.modules:org-netbeans-modules-profiler-oql` (RELEASE200) — OQL engine
- `org.clojure:clojure` (1.12.0) — in-process Clojure eval
- JUnit 5 — tests

## License

MIT
