package com.onpositive.analyzer.mcp;

import com.onpositive.analyzer.ClojureEvaluator;
import com.onpositive.analyzer.HeapDumpService;
import com.onpositive.analyzer.JavaClassPrinter;
import com.onpositive.analyzer.JavaClassPrinter.ClassDetails;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import org.netbeans.lib.profiler.heap.HeapSummary;
import org.netbeans.lib.profiler.heap.GCRoot;
import org.netbeans.lib.profiler.heap.Instance;
import org.netbeans.lib.profiler.heap.JavaClass;

import org.netbeans.lib.profiler.heap.ObjectArrayInstance;
import org.netbeans.lib.profiler.heap.PrimitiveArrayInstance;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * MCP Tool Adapter.
 * Translates MCP requests into Service calls.
 */
public class HeapDumpTools {

    private final HeapDumpService heapDumpService;
    private ClojureEvaluator clojureEvaluator;

    // Constructor Injection ensures no magic instantiation
    public HeapDumpTools(HeapDumpService heapDumpService) {
        this.heapDumpService = heapDumpService;
    }

    private synchronized ClojureEvaluator getClojureEvaluator() {
        if (clojureEvaluator == null) {
            clojureEvaluator = new ClojureEvaluator(heapDumpService);
        }
        return clojureEvaluator;
    }

    public SyncToolSpecification loadHeapTool() {
        McpSchema.JsonSchema filePathSchema = new McpSchema.JsonSchema(
                "string", // The type must be a string literal matching JSON types
                null,     // properties (null for primitive types)
                null,     // required (null for primitive types)
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("file_path", filePathSchema),
                List.of("file_path"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "load_heap",
                "Load Heap Dump",
                "Loads a .hprof heap dump file and returns its summary.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            try {
                Map<String, Object> args = request.arguments();
                String filePath = (String) args.get("file_path");
                HeapSummary summary = heapDumpService.loadHeap(filePath);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(formatSummary(summary))))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult("Failed to load heap: " + e.getMessage());
            }
        });
    }

    public SyncToolSpecification getClassesByMaxInstancesCountTool() {
        McpSchema.JsonSchema fromSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema toSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("from", fromSchema, "to", toSchema),
                List.of(),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_classes_by_max_instances_count",
                "Get Classes By Max Instances Count",
                "Returns a sorted list of classes by instance count (descending) with pagination.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<HeapDumpService.ClassStats> classes = heapDumpService.getClassesByMaxInstancesCount(from, to);
                String result = classes.stream()
                        .map(cs -> cs.className + " (Count: " + cs.instanceCount + ", Size: " + cs.size + ")")
                        .collect(Collectors.joining("\n"));
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getClassesByMaxInstancesSizeTool() {
        McpSchema.JsonSchema fromSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema toSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("from", fromSchema, "to", toSchema),
                List.of(),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_classes_by_max_instances_size",
                "Get Classes By Max Instances Size",
                "Returns a sorted list of classes by total instance size (descending) with pagination.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<HeapDumpService.ClassStats> classes = heapDumpService.getClassesByMaxInstancesSize(from, to);
                String result = classes.stream()
                        .map(cs -> cs.className + " (Count: " + cs.instanceCount + ", Size: " + cs.size + ")")
                        .collect(Collectors.joining("\n"));
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getBiggestObjectsTool() {
        McpSchema.JsonSchema limitSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("limit", limitSchema),
                List.of("limit"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_biggest_objects",
                "Get Biggest Objects",
                "Returns the biggest objects by retained size.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                int limit = parseIntArg(args.get("limit"), 10);
                List<HeapDumpService.BigObjectInfo> objects = heapDumpService.getBiggestObjectsWithOwner(limit);
                StringBuilder sb = new StringBuilder();
                boolean isFallback = !objects.isEmpty() && objects.get(0).retainedSize == 0;
                if (isFallback) {
                    sb.append("NOTE: Retained size computation failed (null GC root in dump). " +
                              "Showing top classes by total shallow size instead. " +
                              "Use get_classes_by_max_instances_size for more detail.\n\n");
                }
                for (HeapDumpService.BigObjectInfo obj : objects) {
                    if (isFallback) {
                        sb.append(String.format("Class: %s, Total Size: %d",
                                obj.className, obj.shallowSize));
                    } else {
                        sb.append(String.format("ID: %d, Class: %s, Retained: %d, Shallow: %d",
                                obj.instanceId, obj.className, obj.retainedSize, obj.shallowSize));
                    }
                    if (obj.ownerClass != null) {
                        sb.append(String.format(", Owner: %s (ID: %d)", obj.ownerClass, obj.ownerId));
                    }
                    sb.append("\n");
                }
                if (objects.isEmpty()) {
                    return errorResult("No valid instances found");
                }
                String text = sb.toString();
                if (text.isEmpty()) text = "No results";
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(text)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                String msg = e.getMessage();
                if (msg == null) msg = e.getClass().getName();
                return errorResult(msg);
            }
        });
    }

    public SyncToolSpecification getGCRootsTool() {
        McpSchema.JsonSchema fromSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema toSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("from", fromSchema, "to", toSchema),
                List.of(),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_gc_roots",
                "Get GC Roots",
                "Returns the GC roots of the loaded heap.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            if (args == null) {
                args = new HashMap<>();
            } else {
                args = new HashMap<>(args);
            }
            args.putIfAbsent("from", 0);
            args.putIfAbsent("to", 50);
            McpSchema.CallToolRequest delegateRequest = new McpSchema.CallToolRequest("get_gc_roots_paginated", args);
            return getGCRootsPaginatedTool().callHandler().apply(exchange, delegateRequest);
        });
    }

    public SyncToolSpecification getGCRootsPaginatedTool() {
        McpSchema.JsonSchema fromSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema toSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("from", fromSchema, "to", toSchema),
                List.of(),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_gc_roots_paginated",
                "Get GC Roots Paginated",
                "Returns GC roots with pagination, including kind and instance information.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<HeapDumpService.GCRootInfo> roots = heapDumpService.getGCRootsPaginated(from, to);
                String result = roots.stream()
                        .map(root -> "Kind: " + root.kind + ", Instance ID: " + root.instanceId + ", Class: " + root.instanceClassName)
                        .collect(Collectors.joining("\n"));
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getJavaClassByNameTool() {
        McpSchema.JsonSchema nameSchema = new McpSchema.JsonSchema(
                "string",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("name", nameSchema),
                List.of("name"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_class_by_name",
                "Get Class By Name",
                "Returns class details by its full name.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                String name = (String) args.get("name");
                JavaClass cls = heapDumpService.getJavaClassByName(name);
                if (cls == null) return errorResult("Class not found: " + name);
                ClassDetails details = JavaClassPrinter.getClassDetails(cls);
                String info = JavaClassPrinter.printClassDetails(details);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(info)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getJavaClassByIdTool() {
        McpSchema.JsonSchema idSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("id", idSchema),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_class_by_id",
                "Get Class By ID",
                "Returns class details by its internal ID.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                JavaClass cls = heapDumpService.getJavaClassById(id);
                if (cls == null) return errorResult("Class not found: " + id);
                ClassDetails details = JavaClassPrinter.getClassDetails(cls);
                String info = JavaClassPrinter.printClassDetails(details);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(info)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getInstanceByIdTool() {
        McpSchema.JsonSchema idSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("id", idSchema),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_instance_by_id",
                "Get Instance By ID",
                "Returns instance details by its internal ID, including class, size, retained size, and field values.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                HeapDumpService.InstanceInfo instance = heapDumpService.getInstanceById(id);
                if (instance == null) return errorResult("Instance not found: " + id);
                
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Instance ID: %d%n", instance.instanceId));
                sb.append(String.format("Class: %s%n", instance.className));
                sb.append(String.format("Size: %d%n", instance.size));
                sb.append(String.format("Retained Size: %d%n", instance.retainedSize));
                sb.append("Field Values:\n");
                for (HeapDumpService.FieldInfo field : instance.fields) {
                    if (field.objectInstanceId != null) {
                        if (field.inlineValue != null) {
                            sb.append(String.format("  %s: %s [%s] (Instance ID: %d)%n",
                                    field.name, field.inlineValue, field.refClassName, field.objectInstanceId));
                        } else {
                            sb.append(String.format("  %s: %s (Instance ID: %d)%n",
                                    field.name, field.refClassName != null ? field.refClassName : field.value, field.objectInstanceId));
                        }
                    } else {
                        sb.append(String.format("  %s: %s%n", field.name, field.value));
                    }
                }
                
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getAllReferencesTool() {
        McpSchema.JsonSchema idSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema fromSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema toSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("id", idSchema, "from", fromSchema, "to", toSchema),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_all_references",
                "Get All References",
                "Returns all references to an instance by its ID with pagination.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<HeapDumpService.ReferenceInfo> refs = heapDumpService.getAllReferences(id, from, to);
                String result = refs.stream()
                        .map(ref -> "Instance ID: " + ref.instanceId + ", Class: " + ref.className)
                        .collect(Collectors.joining("\n"));
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getJavaClassesByRegExpTool() {
        McpSchema.JsonSchema regexpSchema = new McpSchema.JsonSchema(
                "string",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema fromSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema toSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("regexp", regexpSchema, "from", fromSchema, "to", toSchema),
                List.of("regexp"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_classes_by_regexp",
                "Get Classes By RegExp",
                "Returns classes matching the regular expression with pagination.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                String regexp = (String) args.get("regexp");
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<JavaClass> classes = heapDumpService.getJavaClassesByRegExpPaginated(regexp, from, to);
                String result = classes.stream()
                        .map(cls -> cls.getName() + " (Instances: " + cls.getInstancesCount() + ")")
                        .collect(Collectors.joining("\n"));
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getSummaryTool() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "get_summary",
                "Get Heap Summary",
                "Returns the summary of the loaded heap.",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                HeapSummary summary = heapDumpService.getSummary();
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(formatSummary(summary))))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getSystemPropertiesTool() {
        McpSchema.Tool tool = new McpSchema.Tool(
                "get_system_properties",
                "Get System Properties",
                "Returns system properties from the heap dump.",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), false, null, null),
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                Properties props = heapDumpService.getSystemProperties();
                StringBuilder sb = new StringBuilder();
                props.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification executeOqlTool() {
        McpSchema.JsonSchema querySchema = new McpSchema.JsonSchema(
                "string",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema maxResultsSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "query", querySchema,
                        "max_results", maxResultsSchema
                ),
                List.of("query"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "execute_oql",
                "Execute OQL Query",
                "Executes an OQL query on the heap dump. Query syntax example: 'select s.value from java.lang.String s'. " +
                "Instance IDs returned are decimal and compatible with get_instance_by_id and other tools. " +
                "In OQL, use heap.findObject(decimalId) to look up objects by the same ID. " +
                "Note: OQL can only query classes loaded by the bootstrap classloader; for app classes, use get_instances_by_class instead.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                String query = (String) args.get("query");
                Number maxResultsObj = (Number) parseOptionalNumber(args, "max_results");
                int maxResults = (maxResultsObj != null) ? maxResultsObj.intValue() : 100;

                String result = heapDumpService.executeOql(query, maxResults);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                String message = "Use the correct OQL query syntax, which is slightly different from SQL\n" +
                        "example: select var_name from fully.qualified.Name var_name. You can use JS syntax, like var_name.field_name after select statement\n";
                return errorResult(message + e.getMessage());
            }
        });
    }

    // === New tools ===

    public SyncToolSpecification getArrayElementTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "index", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("id", "index"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_array_element",
                "Get Array Element",
                "Returns the element at a given index from an Object[] or ArrayList. Returns the element's class and instance ID.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                int index = parseIntArg(args.get("index"), 0);
                HeapDumpService.ArrayElementInfo elem = heapDumpService.getArrayElement(id, index);
                String result;
                if (elem.value != null && !elem.value.equals("null")) {
                    result = String.format("Index: %d, Value: %s [%s] (Instance ID: %d)",
                            elem.index, elem.value, elem.className, elem.instanceId);
                } else {
                    result = String.format("Index: %d, Class: %s, Instance ID: %d",
                            elem.index, elem.className, elem.instanceId);
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getArrayElementsTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "from", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "to", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_array_elements",
                "Get Array Elements",
                "Returns a range of elements from an Object[] or ArrayList with pagination. Each element includes its class and instance ID.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<HeapDumpService.ArrayElementInfo> elements = heapDumpService.getArrayElements(id, from, to);
                StringBuilder sb = new StringBuilder();
                for (HeapDumpService.ArrayElementInfo elem : elements) {
                    if (elem.value != null && !elem.value.equals("null")) {
                        sb.append(String.format("[%d] %s [%s] (ID: %d)\n", elem.index, elem.value, elem.className, elem.instanceId));
                    } else {
                        sb.append(String.format("[%d] %s (ID: %d)\n", elem.index, elem.className, elem.instanceId));
                    }
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getByteArrayContentsTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "offset", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "length", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "encoding", new McpSchema.JsonSchema("string", null, null, false, null, null)
                ),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_byte_array_contents",
                "Get Byte Array Contents",
                "Returns the contents of a byte[] as text. Encoding options: 'latin1' (default), 'utf16', 'hex'. Use offset and length to read a portion.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                Number offsetObj = (Number) args.get("offset");
                Number lengthObj = (Number) args.get("length");
                int offset = (offsetObj != null) ? offsetObj.intValue() : 0;
                int length = (lengthObj != null) ? lengthObj.intValue() : Integer.MAX_VALUE;
                String encoding = (String) args.getOrDefault("encoding", "latin1");

                String result = heapDumpService.getByteArrayContents(id, offset, length, encoding);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getStringValueTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("id", new McpSchema.JsonSchema("integer", null, null, false, null, null)),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_string_value",
                "Get String Value",
                "Decodes a java.lang.String instance and returns its text content. Handles both Latin-1 (coder=0) and UTF-16 (coder=1) compact strings.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                String value = heapDumpService.getStringValue(id);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(value)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getInstancesByClassTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "class_name", new McpSchema.JsonSchema("string", null, null, false, null, null),
                        "from", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "to", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("class_name"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_instances_by_class",
                "Get Instances By Class",
                "Returns instances of a given class with pagination. Each instance includes ID, class, size, and retained size.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                String className = (String) args.get("class_name");
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<HeapDumpService.InstanceInfo> instances = heapDumpService.getInstancesByClass(className, from, to);
                StringBuilder sb = new StringBuilder();
                for (HeapDumpService.InstanceInfo inst : instances) {
                    sb.append(String.format("ID: %d, Class: %s, Size: %d, Retained: %d\n",
                            inst.instanceId, inst.className, inst.size, inst.retainedSize));
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getGCRootForTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "max_frames", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_gc_root_for",
                "Get GC Root For Object",
                "Given an object ID, traces the path to its nearest GC root. Shows which root kind holds it alive, the thread name, frame number, and stack trace. Use max_frames to limit stack depth (default 50).",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                Number maxFramesObj = (Number) args.get("max_frames");
                int maxFrames = (maxFramesObj != null) ? maxFramesObj.intValue() : 50;

                HeapDumpService.GCRootPathInfo info = heapDumpService.getGCRootFor(id);
                StringBuilder sb = new StringBuilder();
                sb.append("Path to GC root:\n");
                for (int i = 0; i < info.path.size(); i++) {
                    HeapDumpService.PathElement pe = info.path.get(i);
                    sb.append(String.format("  %s→ [%d] %s\n", "  ".repeat(i), pe.instanceId, pe.className));
                }
                if (info.rootKind != null) {
                    sb.append("\nGC Root Kind: ").append(info.rootKind).append("\n");
                }
                if (info.threadName != null) {
                    sb.append("Thread: ").append(info.threadName).append("\n");
                }
                if (info.frameNumber >= 0) {
                    sb.append("Frame Number: ").append(info.frameNumber).append("\n");
                }
                if (info.stackTrace != null && info.stackTrace.length > 0) {
                    int framesToShow = Math.min(maxFrames, info.stackTrace.length);
                    sb.append("Stack Trace:\n");
                    for (int i = 0; i < framesToShow; i++) {
                        sb.append("  at ").append(info.stackTrace[i]).append("\n");
                    }
                    if (info.stackTrace.length > framesToShow) {
                        sb.append(String.format("  ... %d more frames\n", info.stackTrace.length - framesToShow));
                    }
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getThreadsTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "from", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "to", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "max_frames", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of(),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_threads",
                "Get Threads",
                "Returns threads from the heap dump with names and stack traces. Paginated (default 0-50). " +
                "Use max_frames to limit stack trace depth (default 20). Use get_instance_by_id on a thread ID for full details.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                Number fromObj = args != null ? (Number) parseOptionalNumber(args, "from") : null;
                Number toObj = args != null ? (Number) parseOptionalNumber(args, "to") : null;
                Number maxFramesObj = args != null ? (Number) args.get("max_frames") : null;
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;
                int maxFrames = (maxFramesObj != null) ? maxFramesObj.intValue() : 20;

                List<HeapDumpService.ThreadInfo> allThreads = heapDumpService.getThreads();
                int safeTo = Math.min(to, allThreads.size());
                int safeFrom = Math.min(from, safeTo);
                List<HeapDumpService.ThreadInfo> threads = allThreads.subList(safeFrom, safeTo);

                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Threads %d-%d of %d total:\n\n", safeFrom, safeTo, allThreads.size()));
                for (HeapDumpService.ThreadInfo thread : threads) {
                    int totalFrames = thread.stackTrace != null ? thread.stackTrace.length : 0;
                    sb.append(String.format("Thread: \"%s\" (ID: %d, %d frames)\n",
                            thread.threadName != null ? thread.threadName : "<unnamed>",
                            thread.instanceId, totalFrames));
                    if (thread.stackTrace != null && thread.stackTrace.length > 0) {
                        int framesToShow = Math.min(maxFrames, thread.stackTrace.length);
                        for (int i = 0; i < framesToShow; i++) {
                            sb.append("  at ").append(thread.stackTrace[i]).append("\n");
                        }
                        if (thread.stackTrace.length > framesToShow) {
                            sb.append(String.format("  ... %d more frames\n", thread.stackTrace.length - framesToShow));
                        }
                    } else {
                        sb.append("  (no stack trace)\n");
                    }
                    sb.append("\n");
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getMapEntriesTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "from", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "to", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_map_entries",
                "Get Map Entries",
                "Returns key-value pairs from a HashMap, LinkedHashMap, PersistentArrayMap, or PersistentHashMap. Keys that are Strings are auto-decoded. Supports pagination.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                Number fromObj = (Number) parseOptionalNumber(args, "from");
                Number toObj = (Number) parseOptionalNumber(args, "to");
                int from = (fromObj != null) ? fromObj.intValue() : 0;
                int to = (toObj != null) ? toObj.intValue() : 50;

                List<HeapDumpService.MapEntryInfo> entries = heapDumpService.getMapEntries(id, from, to);
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Entries %d-%d:\n", from, from + entries.size()));
                for (HeapDumpService.MapEntryInfo entry : entries) {
                    String keyDisplay = entry.keyInline != null
                            ? entry.keyInline
                            : entry.keyClass + " (ID: " + entry.keyId + ")";
                    String valDisplay = entry.valueInline != null
                            ? entry.valueInline + " [" + entry.valueClass + "] (ID: " + entry.valueId + ")"
                            : entry.valueClass + " (ID: " + entry.valueId + ")";
                    sb.append(String.format("  %s → %s\n", keyDisplay, valDisplay));
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getRetainedBreakdownTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "top_n", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_retained_breakdown",
                "Get Retained Size Breakdown",
                "For a given object, walks its object graph and shows the top classes by size within the reachable set. Useful for understanding what a large object retains.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                Number topNObj = (Number) args.get("top_n");
                int topN = (topNObj != null) ? topNObj.intValue() : 20;

                List<HeapDumpService.RetainedBreakdownEntry> entries = heapDumpService.getRetainedBreakdown(id, topN);
                StringBuilder sb = new StringBuilder();
                long totalSize = entries.stream().mapToLong(e -> e.totalSize).sum();
                sb.append(String.format("Retained breakdown (top %d classes, %d bytes total scanned):\n\n", topN, totalSize));
                sb.append(String.format("%-50s | %12s | %8s\n", "Class", "Size", "Count"));
                sb.append("-".repeat(75)).append("\n");
                for (HeapDumpService.RetainedBreakdownEntry entry : entries) {
                    sb.append(String.format("%-50s | %12d | %8d\n",
                            truncate(entry.className, 50), entry.totalSize, entry.instanceCount));
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification findPathTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "from_id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "to_id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "max_depth", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("from_id", "to_id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "find_path",
                "Find Path Between Objects",
                "Finds the reference chain between two objects using BFS. Returns the path of objects from source to destination.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long fromId = parseLongArg(args.get("from_id"));
                long toId = parseLongArg(args.get("to_id"));
                Number maxDepthObj = (Number) args.get("max_depth");
                int maxDepth = (maxDepthObj != null) ? maxDepthObj.intValue() : 50;

                List<HeapDumpService.PathElement> path = heapDumpService.findPath(fromId, toId, maxDepth);
                if (path.isEmpty()) {
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("No path found between " + fromId + " and " + toId)))
                            .isError(false)
                            .build();
                }
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("Path (%d steps):\n", path.size() - 1));
                for (int i = 0; i < path.size(); i++) {
                    HeapDumpService.PathElement pe = path.get(i);
                    sb.append(String.format("  %s[%d] %s\n", i > 0 ? "→ " : "  ", pe.instanceId, pe.className));
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    public SyncToolSpecification getDominatorTreeTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                        "id", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "depth", new McpSchema.JsonSchema("integer", null, null, false, null, null),
                        "max_children", new McpSchema.JsonSchema("integer", null, null, false, null, null)
                ),
                List.of("id"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_dominator_tree",
                "Get Dominator Tree",
                "Shows the dominator tree rooted at an object, with children sorted by retained size. Like Eclipse MAT's dominator view.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                long id = parseLongArg(args.get("id"));
                Number depthObj = (Number) args.get("depth");
                Number maxChildrenObj = (Number) args.get("max_children");
                int depth = (depthObj != null) ? depthObj.intValue() : 3;
                int maxChildren = (maxChildrenObj != null) ? maxChildrenObj.intValue() : 10;

                HeapDumpService.DominatorNode root = heapDumpService.getDominatorTree(id, depth, maxChildren);
                StringBuilder sb = new StringBuilder();
                formatDominatorNode(sb, root, 0);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    private void formatDominatorNode(StringBuilder sb, HeapDumpService.DominatorNode node, int indent) {
        sb.append("  ".repeat(indent));
        sb.append(String.format("[%d] %s (shallow: %d, retained: %d)\n",
                node.instanceId, node.className, node.shallowSize, node.retainedSize));
        for (HeapDumpService.DominatorNode child : node.children) {
            formatDominatorNode(sb, child, indent + 1);
        }
    }

    public SyncToolSpecification executeClojureTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("code", new McpSchema.JsonSchema("string", null, null, false, null, null)),
                List.of("code"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "execute_clojure",
                "Execute Clojure",
                "Evaluate a Clojure expression with access to the loaded heap. " +
                "Available functions: (instance id), (string id), (strings [ids]), (fields id), " +
                "(elements id from to), (entries id from to), (references id from to), " +
                "(instances \"class.Name\" from to), (classes-matching \"regex\" from to), " +
                "(biggest n), (summary), (gc-root id), (threads), (retained-breakdown id n), " +
                "(dominator-tree id depth max-children), (find-path from-id to-id), " +
                "(keyword-name id), (describe id). " +
                "Use resolve-value on any instance to decode Strings/Keywords/boxed primitives. " +
                "Results are returned as pr-str'd Clojure data.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                String code = (String) args.get("code");
                String result = getClojureEvaluator().eval(code);
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(result)))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                String msg = e.getMessage();
                if (e.getCause() != null) msg += "\nCaused by: " + e.getCause().getMessage();
                return errorResult(msg);
            }
        });
    }

    public SyncToolSpecification getStringValuesBulkTool() {
        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of("ids", new McpSchema.JsonSchema("array", null, null, false, null, null)),
                List.of("ids"),
                false, null, null
        );

        McpSchema.Tool tool = new McpSchema.Tool(
                "get_string_values_bulk",
                "Get String Values Bulk",
                "Decodes multiple java.lang.String instances in one call. Pass an array of instance IDs. Returns each ID with its decoded text.",
                inputSchema,
                null, null, null
        );

        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                List<?> rawIds = (List<?>) args.get("ids");
                List<Long> ids = new java.util.ArrayList<>();
                for (Object rawId : rawIds) {
                    ids.add(parseLongArg(rawId));
                }
                java.util.Map<Long, String> results = heapDumpService.getStringValuesBulk(ids);
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<Long, String> entry : results.entrySet()) {
                    sb.append(String.format("ID %d: %s\n", entry.getKey(), entry.getValue()));
                }
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();
            } catch (Exception e) {
                return errorResult(e.getMessage());
            }
        });
    }

    private static Object parseOptionalNumber(Map<String, Object> args, String key) {
        if (args == null) return null;
        Object value = args.get(key);
        if (value == null) return null;
        if (value instanceof Number) return value;
        if (value instanceof String) {
            try { return Long.parseLong((String) value); }
            catch (NumberFormatException e) {
                try { return Double.parseDouble((String) value); }
                catch (NumberFormatException e2) { return null; }
            }
        }
        return null;
    }

    private static long parseLongArg(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value instanceof String) return Long.parseLong((String) value);
        throw new IllegalArgumentException("Expected a number, got: " + value);
    }

    private static int parseIntArg(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) return Integer.parseInt((String) value);
        throw new IllegalArgumentException("Expected a number, got: " + value);
    }

    private String formatSummary(HeapSummary summary) {
        return String.format("Total Instances: %d\nTotal Size: %d bytes\nTime: %d",
                summary.getTotalLiveInstances(), summary.getTotalLiveBytes(), summary.getTime());
    }

    private McpSchema.CallToolResult errorResult(String message) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(new McpSchema.TextContent(message != null ? message : "Unknown error")))
                .isError(true)
                .build();
    }

    public McpServerFeatures.SyncToolSpecification analyzeHeapTool() {
        // 1. Define Input Schema
        // Arguments: file_path (string, required), limit (integer, optional, default 10)
        McpSchema.JsonSchema filePathSchema = new McpSchema.JsonSchema(
                "string",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema limitSchema = new McpSchema.JsonSchema(
                "integer",
                null,
                null,
                false, null, null
        );

        McpSchema.JsonSchema inputSchema = new McpSchema.JsonSchema(
                "object",
                Map.of(
                    "file_path", filePathSchema,
                    "limit", limitSchema
                ),
                List.of("file_path"), // required fields
                false,
                null,
                null
        );

        // 2. Define Tool Metadata
        McpSchema.Tool tool = new McpSchema.Tool(
                "analyze_heap_dump",
                "Analyze Heap Dump",
                "Parses a .hprof heap dump file and returns the top classes by instance count.",
                inputSchema,
                null, null, null
        );

        // 3. Define Execution Logic (Handler)
        return new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> args = request.arguments();
            try {
                String filePath = (String) args.get("file_path");
                
                // Handle optional 'limit' argument
                Number limitObj = (Number) args.get("limit");
                int limit = (limitObj != null) ? limitObj.intValue() : 10;

                // Call Domain Layer
                List<HeapDumpService.ClassStats> stats = heapDumpService.getTopClasses(filePath, limit);

                // Format Result for MCP
                StringBuilder sb = new StringBuilder();
                sb.append("Top ").append(stats.size()).append(" Classes in Heap Dump:\n");
                sb.append(String.format("%-50s | %-10s | %-10s%n", "Class Name", "Count", "Size"));
                sb.append("-".repeat(75)).append("\n");

                for (HeapDumpService.ClassStats stat : stats) {
                    sb.append(String.format("%-50s | %-10d | %-10d%n", 
                            truncate(stat.className, 50), stat.instanceCount, stat.size));
                }

                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent(sb.toString())))
                        .isError(false)
                        .build();

            } catch (IOException e) {
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Failed to parse heap dump: " + e.getMessage())))
                        .isError(true)
                        .build();
            } catch (Exception e) {
                return McpSchema.CallToolResult.builder()
                        .content(List.of(new McpSchema.TextContent("Unexpected error: " + e.getMessage())))
                        .isError(true)
                        .build();
            }
        });
    }
    
    private String truncate(String str, int len) {
        if (str.length() <= len) return str;
        return str.substring(0, len - 3) + "...";
    }
}