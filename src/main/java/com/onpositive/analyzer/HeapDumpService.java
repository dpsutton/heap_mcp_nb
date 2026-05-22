package com.onpositive.analyzer;

import com.onpositive.analyzer.util.LRUCache;
import org.netbeans.lib.profiler.heap.*;
import org.netbeans.lib.profiler.utils.StringUtils;
import org.netbeans.modules.profiler.oql.engine.api.OQLEngine;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

public class HeapDumpService {

    private Heap heap;
    private OQLEngine oqlEngine;
    private List<ClassStats> classesSortedByCount;
    private List<ClassStats> classesSortedBySize;
    private LRUCache<String, List<JavaClass>> classesByRegexp = new LRUCache<>(10);

    public static class ClassStats {
        public String className;
        public long instanceCount;
        public long size;

        public ClassStats(String className, long instanceCount, long size) {
            this.className = className;
            this.instanceCount = instanceCount;
            this.size = size;
        }
    }

    public HeapSummary loadHeap(String filePath) throws IOException {
        File heapFile = new File(filePath);
        if (!heapFile.exists()) {
            throw new IOException("Heap dump file not found: " + filePath);
        }
        // Clear previous state so old heap can be GC'd
        if (heap != null) {
            heap = null;
            oqlEngine = null;
            classesSortedByCount = null;
            classesSortedBySize = null;
            classesByRegexp = new LRUCache<>(10);
            System.gc(); // hint to release memory-mapped buffers
        }
        heap = HeapFactory.createHeap(heapFile);
        return heap.getSummary();
    }

    public List<ClassStats> getClassesByMaxInstancesCount(int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        if (classesSortedByCount == null) {
            classesSortedByCount = ((Collection<JavaClass>) heap.getAllClasses()).stream()
                    .map(cls -> new ClassStats(cls.getName(), cls.getInstancesCount(), cls.getAllInstancesSize()))
                    .sorted(Comparator.comparingLong((ClassStats cs) -> cs.instanceCount).reversed())
                    .collect(Collectors.toList());
        }
        int safeTo = Math.min(to, classesSortedByCount.size());
        int safeFrom = Math.min(from, safeTo);
        return classesSortedByCount.subList(safeFrom, safeTo);
    }

    public List<ClassStats> getClassesByMaxInstancesSize(int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        if (classesSortedBySize == null) {
            classesSortedBySize = ((Collection<JavaClass>) heap.getAllClasses()).stream()
                    .map(cls -> new ClassStats(cls.getName(), cls.getInstancesCount(), cls.getAllInstancesSize()))
                    .sorted(Comparator.comparingLong((ClassStats cs) -> cs.size).reversed())
                    .collect(Collectors.toList());
        }
        int safeTo = Math.min(to, classesSortedBySize.size());
        int safeFrom = Math.min(from, safeTo);
        return classesSortedBySize.subList(safeFrom, safeTo);
    }

    public List<Instance> getBiggestObjectsByRetainedSize(int limit) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        return heap.getBiggestObjectsByRetainedSize(limit);
    }

    public static class GCRootInfo {
        public String kind;
        public long instanceId;
        public String instanceClassName;

        public GCRootInfo(String kind, long instanceId, String instanceClassName) {
            this.kind = kind;
            this.instanceId = instanceId;
            this.instanceClassName = instanceClassName;
        }
    }

    public List<GCRootInfo> getGCRootsPaginated(int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Collection<GCRoot> allRoots = heap.getGCRoots();
        List<GCRoot> rootsList = new ArrayList<>(allRoots);
        int safeTo = Math.min(to, rootsList.size());
        int safeFrom = Math.min(from, safeTo);
        List<GCRoot> page = rootsList.subList(safeFrom, safeTo);
        List<GCRootInfo> result = new ArrayList<>();
        for (GCRoot root : page) {
            Instance inst = root.getInstance();
            if (inst != null) {
                result.add(new GCRootInfo(
                        root.getKind(),
                        inst.getInstanceId(),
                        getClassName(inst)
                ));
            }
        }
        return result;
    }

    private static String getClassName(Instance inst) {
        if (inst.getJavaClass() == null || inst.getJavaClass().getName() == null) {
            return "";
        }
        String name = inst.getJavaClass().getName();
        if (name.equals("java.lang.Class")) {
            Object clzName = inst.getValueOfField("name");
            if (clzName != null) {
                return name + "<" + clzName + ">";
            }
        }
        return name;
    }

    /**
     * Tries to decode a well-known type inline: Strings, Keywords, boxed primitives.
     * Returns null if the instance is not a decodable type.
     */
    private String resolveInlineValue(Instance instance) {
        if (instance == null) return null;
        String className = getClassName(instance);
        try {
            switch (className) {
                case "java.lang.String": {
                    String s = getStringValue(instance.getInstanceId());
                    if (s != null && s.length() > 200) {
                        s = s.substring(0, 200) + "...";
                    }
                    return "\"" + s + "\"";
                }
                case "java.lang.Long":
                case "java.lang.Integer":
                case "java.lang.Short":
                case "java.lang.Byte":
                case "java.lang.Double":
                case "java.lang.Float":
                case "java.lang.Boolean":
                case "java.lang.Character": {
                    Object val = instance.getValueOfField("value");
                    return val != null ? val.toString() : null;
                }
                case "clojure.lang.Keyword": {
                    Object sym = instance.getValueOfField("sym");
                    if (sym instanceof Instance) {
                        Instance symInst = (Instance) sym;
                        String ns = resolveStringField(symInst, "ns");
                        String name = resolveStringField(symInst, "name");
                        if (name != null) {
                            return ns != null ? ":" + ns + "/" + name : ":" + name;
                        }
                    }
                    return null;
                }
                case "clojure.lang.Symbol": {
                    String ns = resolveStringField(instance, "ns");
                    String name = resolveStringField(instance, "name");
                    if (name != null) {
                        return ns != null ? ns + "/" + name : name;
                    }
                    return null;
                }
                default:
                    return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveStringField(Instance instance, String fieldName) {
        Object fieldVal = instance.getValueOfField(fieldName);
        if (fieldVal instanceof Instance) {
            Instance strInst = (Instance) fieldVal;
            if (getClassName(strInst).equals("java.lang.String")) {
                try {
                    return getStringValue(strInst.getInstanceId());
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    public JavaClass getJavaClassByName(String name) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        return heap.getJavaClassByName(name);
    }

    public JavaClass getJavaClassById(long id) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        return heap.getJavaClassByID(id);
    }

    public static class InstanceInfo {
        public long instanceId;
        public String className;
        public long size;
        public long retainedSize;
        public List<FieldInfo> fields;

        public InstanceInfo(long instanceId, String className, long size, long retainedSize, List<FieldInfo> fields) {
            this.instanceId = instanceId;
            this.className = className;
            this.size = size;
            this.retainedSize = retainedSize;
            this.fields = fields;
        }
    }

    public static class FieldInfo {
        public String name;
        public String value;
        public Long objectInstanceId;
        public String refClassName;
        public String inlineValue; // decoded String/Keyword/boxed primitive

        public FieldInfo(String name, String value, Long objectInstanceId, String refClassName, String inlineValue) {
            this.name = name;
            this.value = value;
            this.objectInstanceId = objectInstanceId;
            this.refClassName = refClassName;
            this.inlineValue = inlineValue;
        }
    }

    public InstanceInfo getInstanceById(long id) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) return null;

        List<FieldInfo> fields = new ArrayList<>();
        for (Object fvObj : instance.getFieldValues()) {
            FieldValue fv = (FieldValue) fvObj;
            String fieldName = fv.getField().getName();
            String valueStr = String.valueOf(fv.getValue());
            Long objectInstanceId = null;
            String refClassName = null;
            String inlineValue = null;

            if (fv instanceof ObjectFieldValue) {
                ObjectFieldValue ofv = (ObjectFieldValue) fv;
                Instance refInstance = ofv.getInstance();
                if (refInstance != null) {
                    objectInstanceId = refInstance.getInstanceId();
                    refClassName = getClassName(refInstance);
                    inlineValue = resolveInlineValue(refInstance);
                }
            }

            fields.add(new FieldInfo(fieldName, valueStr, objectInstanceId, refClassName, inlineValue));
        }

        long retained = 0;
        try { retained = instance.getRetainedSize(); } catch (Exception e) { /* skip */ }
        return new InstanceInfo(
                instance.getInstanceId(),
                getClassName(instance),
                instance.getSize(),
                retained,
                fields
        );
    }

    public static class ReferenceInfo {
        public long instanceId;
        public String className;
        public String fieldName;

        public ReferenceInfo(long instanceId, String className, String fieldName) {
            this.instanceId = instanceId;
            this.className = className;
            this.fieldName = fieldName;
        }
    }

    public List<ReferenceInfo> getAllReferences(long instanceId, int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(instanceId);
        if (instance == null) return new ArrayList<>();
        
        Collection<?> references = instance.getReferences();
        List<Instance> refsList = new ArrayList<>();
        for (Object refObj : references) {
            if (refObj instanceof FieldValue) {
                FieldValue fv = (FieldValue) refObj;
                if (fv instanceof ObjectFieldValue) {
                    ObjectFieldValue ofv = (ObjectFieldValue) fv;
                    Instance refInstance = ofv.getInstance();
                    if (refInstance != null) {
                        refsList.add(refInstance);
                    }
                }
            }
        }
        int safeTo = Math.min(to, refsList.size());
        int safeFrom = Math.min(from, safeTo);
        
        List<ReferenceInfo> result = new ArrayList<>();
        for (Instance ref : refsList.subList(safeFrom, safeTo)) {
            result.add(new ReferenceInfo(
                    ref.getInstanceId(),
                    getClassName(ref),
                    null
            ));
        }
        return result;
    }

    public List<JavaClass> getJavaClassesByRegExpPaginated(String regexp, int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        List<JavaClass> classesList = classesByRegexp.get(regexp);
        if (classesList == null) {
            classesList = new ArrayList<>(heap.getJavaClassesByRegExp(regexp));
        }
        int safeTo = Math.min(to, classesList.size());
        int safeFrom = Math.min(from, safeTo);
        return classesList.subList(safeFrom, safeTo);
    }

    public HeapSummary getSummary() {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        return heap.getSummary();
    }

    public Properties getSystemProperties() {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        return heap.getSystemProperties();
    }

    public List<ClassStats> getTopClasses(String filePath, int limit) throws IOException {
        loadHeap(filePath);
        return getClassesByMaxInstancesCount(0, limit);
    }

    public String executeOql(String query, int maxResults) throws Exception {
        if (heap == null) {
            throw new IllegalStateException("Heap not loaded. Please load a heap dump first.");
        }
        
        if (oqlEngine == null) {
            oqlEngine = new OQLEngine(heap);
        }

//        String convertedQuery = convertToNetBeansOql(query);
        
        StringBuilder resultBuilder = new StringBuilder();

        oqlEngine.executeQuery(query, new OQLEngine.ObjectVisitor() {
            int count = 0;

            @Override
            public boolean visit(Object o) {
                count++;
                if (count > maxResults) {
                    return false;
                }
                
                if (o instanceof PrimitiveArrayInstance arrayInstance) {
                    resultBuilder.append("Array:").append(getClassName(arrayInstance)).append(" values:[");
                    int to = Math.min(100, arrayInstance.getLength());
                    List values = arrayInstance.getValues();
                    for (int i = 0; i < to; i++) {
                        resultBuilder.append(values.get(i).toString());
                        if (i < to - 1) {
                            resultBuilder.append(",");
                        }
                    }
                    resultBuilder.append("]\n");
                } else if (o instanceof Instance) {
                    Instance inst = (Instance) o;
                    resultBuilder.append(String.format("[%d] %s (ID: %d, Size: %d)\n",
                            count,
                            inst.getJavaClass().getName(),
                            inst.getInstanceId(),
                            inst.getSize()));
                } else if (o != null) {
                    resultBuilder.append(String.format("[%d] %s\n", count, o.toString()));
                }
                return true;
            }
        });

        if (resultBuilder.length() == 0) {
            return "No results found or empty result set.";
        }

        return "Query Results:\n" + resultBuilder.toString();
    }
    
    // === Task #1: Array element access ===

    public static class ArrayElementInfo {
        public int index;
        public String className;
        public long instanceId;
        public String value; // inline-decoded value for Strings, Keywords, boxed primitives

        public ArrayElementInfo(int index, String className, long instanceId, String value) {
            this.index = index;
            this.className = className;
            this.instanceId = instanceId;
            this.value = value;
        }
    }

    private ArrayElementInfo makeArrayElementInfo(int index, Instance element) {
        if (element == null) {
            return new ArrayElementInfo(index, "null", 0, "null");
        }
        return new ArrayElementInfo(index, getClassName(element), element.getInstanceId(), resolveInlineValue(element));
    }

    /**
     * Extract the backing Object[] values and logical length from an instance.
     * Supports: Object[], ArrayList, PersistentVector (flattened), PersistentVector$Node (array field).
     */
    private record ArrayBacking(List<Instance> values, int length) {}

    private ArrayBacking resolveArrayBacking(Instance instance) {
        if (instance instanceof ObjectArrayInstance) {
            ObjectArrayInstance arr = (ObjectArrayInstance) instance;
            return new ArrayBacking(arr.getValues(), arr.getLength());
        }

        String className = getClassName(instance);

        if (className.equals("java.util.ArrayList")) {
            Object elementData = instance.getValueOfField("elementData");
            if (elementData instanceof ObjectArrayInstance) {
                ObjectArrayInstance arr = (ObjectArrayInstance) elementData;
                Object sizeObj = instance.getValueOfField("size");
                int size = (sizeObj instanceof Number) ? ((Number) sizeObj).intValue() : arr.getLength();
                return new ArrayBacking(arr.getValues(), size);
            }
        }

        if (className.equals("clojure.lang.PersistentVector") || className.equals("clojure.lang.PersistentVector$TransientVector")) {
            // Flatten the trie: walk root node tree + tail
            List<Instance> flat = new ArrayList<>();
            Object rootNode = instance.getValueOfField("root");
            Object shiftObj = instance.getValueOfField("shift");
            int shift = (shiftObj instanceof Number) ? ((Number) shiftObj).intValue() : 5;
            if (rootNode instanceof Instance) {
                flattenVectorNode((Instance) rootNode, shift, flat);
            }
            Object tail = instance.getValueOfField("tail");
            if (tail instanceof ObjectArrayInstance) {
                for (Instance elem : (List<Instance>) ((ObjectArrayInstance) tail).getValues()) {
                    flat.add(elem);
                }
            }
            Object cntObj = instance.getValueOfField("cnt");
            int cnt = (cntObj instanceof Number) ? ((Number) cntObj).intValue() : flat.size();
            // Trim to actual count (tail array may have nulls beyond cnt)
            if (flat.size() > cnt) flat = flat.subList(0, cnt);
            return new ArrayBacking(flat, cnt);
        }

        if (className.equals("clojure.lang.PersistentVector$Node")) {
            // Just expose the node's array field directly
            Object arrayObj = instance.getValueOfField("array");
            if (arrayObj instanceof ObjectArrayInstance) {
                ObjectArrayInstance arr = (ObjectArrayInstance) arrayObj;
                List<Instance> values = arr.getValues();
                // Filter out trailing nulls
                int len = values.size();
                while (len > 0 && values.get(len - 1) == null) len--;
                return new ArrayBacking(values.subList(0, len), len);
            }
        }

        if (className.contains("SubVector")) {
            Object v = instance.getValueOfField("v");
            Object startObj = instance.getValueOfField("start");
            Object endObj = instance.getValueOfField("end");
            if (v instanceof Instance) {
                ArrayBacking underlying = resolveArrayBacking((Instance) v);
                int start = (startObj instanceof Number) ? ((Number) startObj).intValue() : 0;
                int end = (endObj instanceof Number) ? ((Number) endObj).intValue() : underlying.length;
                return new ArrayBacking(underlying.values.subList(start, Math.min(end, underlying.length)), end - start);
            }
        }

        throw new IllegalArgumentException("Instance " + instance.getInstanceId() +
                " is not an array, ArrayList, or PersistentVector, it is: " + className);
    }

    private void flattenVectorNode(Instance node, int shift, List<Instance> result) {
        Object arrayObj = node.getValueOfField("array");
        if (!(arrayObj instanceof ObjectArrayInstance)) return;
        List<Instance> arr = ((ObjectArrayInstance) arrayObj).getValues();

        if (shift == 0) {
            // Leaf node — elements are the actual values
            for (Instance elem : arr) {
                result.add(elem);
            }
        } else {
            // Internal node — children are sub-nodes
            for (Instance child : arr) {
                if (child != null) {
                    flattenVectorNode(child, shift - 5, result);
                }
            }
        }
    }

    public ArrayElementInfo getArrayElement(long id, int index) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        ArrayBacking backing = resolveArrayBacking(instance);
        if (index < 0 || index >= backing.length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + backing.length);
        }
        return makeArrayElementInfo(index, backing.values.get(index));
    }

    public List<ArrayElementInfo> getArrayElements(long id, int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        ArrayBacking backing = resolveArrayBacking(instance);
        int safeFrom = Math.max(0, Math.min(from, backing.length));
        int safeTo = Math.max(safeFrom, Math.min(to, backing.length));
        List<ArrayElementInfo> result = new ArrayList<>();
        for (int i = safeFrom; i < safeTo; i++) {
            result.add(makeArrayElementInfo(i, backing.values.get(i)));
        }
        return result;
    }

    // === Task #2: Byte array contents ===

    public String getByteArrayContents(long id, int offset, int length, String encoding) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        if (!(instance instanceof PrimitiveArrayInstance)) {
            throw new IllegalArgumentException("Instance " + id + " is not a primitive array, it is: " + getClassName(instance));
        }
        PrimitiveArrayInstance arr = (PrimitiveArrayInstance) instance;
        int arrLen = arr.getLength();
        int safeOffset = Math.max(0, Math.min(offset, arrLen));
        int safeLength = Math.min(length, arrLen - safeOffset);

        List<String> values = arr.getValues();
        byte[] bytes = new byte[safeLength];
        for (int i = 0; i < safeLength; i++) {
            bytes[i] = Byte.parseByte(values.get(safeOffset + i));
        }

        if ("hex".equalsIgnoreCase(encoding)) {
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            return sb.toString();
        } else if ("utf16".equalsIgnoreCase(encoding)) {
            return new String(bytes, StandardCharsets.UTF_16);
        } else {
            // Default: Latin-1 / ISO-8859-1 which also works for ASCII
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    // === Task #6: String value decoding ===

    public String getStringValue(long id) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        String className = getClassName(instance);
        if (!className.equals("java.lang.String")) {
            throw new IllegalArgumentException("Instance " + id + " is not a String, it is: " + className);
        }

        Object valueField = instance.getValueOfField("value");
        if (!(valueField instanceof PrimitiveArrayInstance)) {
            throw new IllegalStateException("String.value is not a primitive array");
        }

        PrimitiveArrayInstance valueArray = (PrimitiveArrayInstance) valueField;
        List<String> values = valueArray.getValues();
        String arrayClass = valueArray.getJavaClass().getName();

        if (arrayClass.equals("char[]")) {
            // Pre-Java-9: String.value is char[]
            StringBuilder sb = new StringBuilder(values.size());
            for (String v : values) {
                if (v.length() == 1) {
                    sb.append(v.charAt(0));
                } else {
                    // Numeric char value
                    sb.append((char) Integer.parseInt(v));
                }
            }
            return sb.toString();
        }

        // Java 9+: String.value is byte[]
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            bytes[i] = Byte.parseByte(values.get(i));
        }

        Object coderObj = instance.getValueOfField("coder");
        if (coderObj instanceof Number) {
            int coder = ((Number) coderObj).intValue();
            if (coder == 1) {
                return new String(bytes, StandardCharsets.UTF_16);
            }
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    // === Batch string decoding ===

    public Map<Long, String> getStringValuesBulk(List<Long> ids) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Map<Long, String> results = new LinkedHashMap<>();
        for (Long id : ids) {
            try {
                results.put(id, getStringValue(id));
            } catch (Exception e) {
                results.put(id, "<error: " + e.getMessage() + ">");
            }
        }
        return results;
    }

    // === Biggest objects with owner info ===

    public static class BigObjectInfo {
        public long instanceId;
        public String className;
        public long retainedSize;
        public long shallowSize;
        public String ownerClass; // class of the first object that references this one
        public long ownerId;

        public BigObjectInfo(long instanceId, String className, long retainedSize, long shallowSize, String ownerClass, long ownerId) {
            this.instanceId = instanceId;
            this.className = className;
            this.retainedSize = retainedSize;
            this.shallowSize = shallowSize;
            this.ownerClass = ownerClass;
            this.ownerId = ownerId;
        }
    }

    public List<BigObjectInfo> getBiggestObjectsWithOwner(int limit) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        List<Instance> biggest;
        try {
            // Run with a timeout — retained size computation can take 10+ minutes on large heaps
            var future = java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> heap.getBiggestObjectsByRetainedSize(limit));
            biggest = future.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // Timeout, NPE on null GC roots, NoSuchElementException, etc.
            return getBiggestByShallowSize(limit);
        }
        List<BigObjectInfo> result = new ArrayList<>();
        for (Instance inst : biggest) {
            try {
                String ownerClass = null;
                long ownerId = 0;
                // Find first referrer
                List<?> refs = inst.getReferences();
                if (refs != null && !refs.isEmpty()) {
                    for (Object refObj : refs) {
                        Instance owner = null;
                        if (refObj instanceof Value) {
                            owner = ((Value) refObj).getDefiningInstance();
                        }
                        if (owner != null) {
                            ownerClass = getClassName(owner);
                            ownerId = owner.getInstanceId();
                            break;
                        }
                    }
                }
                result.add(new BigObjectInfo(
                        inst.getInstanceId(),
                        getClassName(inst),
                        inst.getRetainedSize(),
                        inst.getSize(),
                        ownerClass, ownerId));
            } catch (Exception e) {
                // skip
            }
        }
        return result;
    }

    private List<BigObjectInfo> getBiggestByShallowSize(int limit) {
        // Fallback when retained size computation fails:
        // Use class-level stats (already cached/fast) and return the first
        // instance from the top classes by total size.
        List<ClassStats> topClasses = getClassesByMaxInstancesSize(0, limit);
        List<BigObjectInfo> result = new ArrayList<>();
        for (ClassStats cs : topClasses) {
            try {
                JavaClass cls = heap.getJavaClassByName(cs.className);
                if (cls == null) continue;
                List<Instance> instances = cls.getInstances();
                if (instances == null || instances.isEmpty()) continue;
                Instance inst = instances.get(0);
                result.add(new BigObjectInfo(
                        inst.getInstanceId(), cs.className,
                        0, // retained unknown — note in output
                        cs.size, // total class size as proxy
                        null, 0));
            } catch (Exception e) {
                // Skip classes that can't be queried
                result.add(new BigObjectInfo(
                        0, cs.className,
                        0, cs.size,
                        null, 0));
            }
        }
        return result;
    }

    // === Task #9: Get instances by class ===

    public List<InstanceInfo> getInstancesByClass(String className, int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        JavaClass cls = heap.getJavaClassByName(className);
        if (cls == null) throw new IllegalArgumentException("Class not found: " + className);

        List<Instance> instances = cls.getInstances();
        int safeTo = Math.min(to, instances.size());
        int safeFrom = Math.min(from, safeTo);

        List<InstanceInfo> result = new ArrayList<>();
        for (Instance inst : instances.subList(safeFrom, safeTo)) {
            long retained = 0;
            try { retained = inst.getRetainedSize(); } catch (Exception e) { /* skip */ }
            result.add(new InstanceInfo(
                    inst.getInstanceId(),
                    getClassName(inst),
                    inst.getSize(),
                    retained,
                    List.of() // skip fields for listing — use get_instance_by_id for details
            ));
        }
        return result;
    }

    // === Task #4: GC root path for a specific object ===

    public static class GCRootPathInfo {
        public List<PathElement> path;
        public String rootKind;
        public String threadName;
        public int frameNumber;
        public StackTraceElement[] stackTrace;

        public GCRootPathInfo(List<PathElement> path, String rootKind, String threadName, int frameNumber, StackTraceElement[] stackTrace) {
            this.path = path;
            this.rootKind = rootKind;
            this.threadName = threadName;
            this.frameNumber = frameNumber;
            this.stackTrace = stackTrace;
        }
    }

    public static class PathElement {
        public long instanceId;
        public String className;

        public PathElement(long instanceId, String className) {
            this.instanceId = instanceId;
            this.className = className;
        }
    }

    public GCRootPathInfo getGCRootFor(long id) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        // Walk the nearest GC root pointer chain
        List<PathElement> path = new ArrayList<>();
        Instance current = instance;
        int maxDepth = 200;
        try {
            while (current != null && path.size() < maxDepth) {
                path.add(new PathElement(current.getInstanceId(), getClassName(current)));
                Instance next = current.getNearestGCRootPointer();
                if (next == null || next.getInstanceId() == current.getInstanceId()) break;
                current = next;
            }
        } catch (NullPointerException e) {
            // NetBeans profiler NPE when GC root has null instance — return partial path
        }

        // Check if the end of the chain is a GC root
        String rootKind = null;
        String threadName = null;
        int frameNumber = -1;
        StackTraceElement[] stackTrace = null;

        if (current != null) {
            GCRoot gcRoot = heap.getGCRoot(current);
            if (gcRoot != null) {
                rootKind = gcRoot.getKind();
                if (gcRoot instanceof JavaFrameGCRoot) {
                    JavaFrameGCRoot frameRoot = (JavaFrameGCRoot) gcRoot;
                    frameNumber = frameRoot.getFrameNumber();
                    ThreadObjectGCRoot threadRoot = frameRoot.getThreadGCRoot();
                    if (threadRoot != null) {
                        stackTrace = threadRoot.getStackTrace();
                        Instance threadInst = threadRoot.getInstance();
                        if (threadInst != null) {
                            threadName = resolveThreadName(threadInst);
                        }
                    }
                } else if (gcRoot instanceof ThreadObjectGCRoot) {
                    ThreadObjectGCRoot threadRoot = (ThreadObjectGCRoot) gcRoot;
                    stackTrace = threadRoot.getStackTrace();
                    Instance threadInst = threadRoot.getInstance();
                    if (threadInst != null) {
                        threadName = resolveThreadName(threadInst);
                    }
                }
            }
        }

        return new GCRootPathInfo(path, rootKind, threadName, frameNumber, stackTrace);
    }

    // === Task #7: Thread listing ===

    public static class ThreadInfo {
        public long instanceId;
        public String threadName;
        public StackTraceElement[] stackTrace;

        public ThreadInfo(long instanceId, String threadName, StackTraceElement[] stackTrace) {
            this.instanceId = instanceId;
            this.threadName = threadName;
            this.stackTrace = stackTrace;
        }
    }

    public List<ThreadInfo> getThreads() {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Collection<GCRoot> roots = heap.getGCRoots();
        Map<Long, ThreadInfo> threads = new LinkedHashMap<>();

        for (GCRoot root : roots) {
            if (root instanceof ThreadObjectGCRoot) {
                ThreadObjectGCRoot threadRoot = (ThreadObjectGCRoot) root;
                Instance inst = threadRoot.getInstance();
                if (inst != null && !threads.containsKey(inst.getInstanceId())) {
                    String name = resolveThreadName(inst);
                    threads.put(inst.getInstanceId(), new ThreadInfo(
                            inst.getInstanceId(),
                            name,
                            threadRoot.getStackTrace()
                    ));
                }
            }
        }
        return new ArrayList<>(threads.values());
    }

    private String resolveThreadName(Instance threadInstance) {
        Object nameObj = threadInstance.getValueOfField("name");
        if (nameObj instanceof Instance) {
            Instance nameInst = (Instance) nameObj;
            if (getClassName(nameInst).equals("java.lang.String")) {
                try {
                    return getStringValue(nameInst.getInstanceId());
                } catch (Exception e) {
                    // fall through
                }
            }
        }
        if (nameObj != null) return nameObj.toString();
        return null;
    }

    // === Task #5: Map entry walking ===

    public static class MapEntryInfo {
        public long keyId;
        public String keyClass;
        public String keyInline; // decoded if key is a String/Keyword/boxed primitive
        public long valueId;
        public String valueClass;
        public String valueInline; // decoded if value is a String/Keyword/boxed primitive

        public MapEntryInfo(long keyId, String keyClass, String keyInline, long valueId, String valueClass, String valueInline) {
            this.keyId = keyId;
            this.keyClass = keyClass;
            this.keyInline = keyInline;
            this.valueId = valueId;
            this.valueClass = valueClass;
            this.valueInline = valueInline;
        }
    }

    public List<MapEntryInfo> getMapEntries(long id, int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        String className = getClassName(instance);
        List<MapEntryInfo> allEntries = new ArrayList<>();

        if (className.equals("java.util.HashMap") || className.equals("java.util.LinkedHashMap")) {
            collectHashMapEntries(instance, allEntries);
        } else if (className.equals("clojure.lang.PersistentArrayMap")) {
            collectPersistentArrayMapEntries(instance, allEntries);
        } else if (className.equals("clojure.lang.PersistentHashMap")) {
            collectPersistentHashMapEntries(instance, allEntries);
        } else {
            throw new IllegalArgumentException("Instance " + id + " is not a supported map type, it is: " + className);
        }

        int safeTo = Math.min(to, allEntries.size());
        int safeFrom = Math.min(from, safeTo);
        return allEntries.subList(safeFrom, safeTo);
    }

    private MapEntryInfo makeMapEntry(Instance key, Instance val) {
        return new MapEntryInfo(
                key != null ? key.getInstanceId() : 0,
                key != null ? getClassName(key) : "null",
                resolveInlineValue(key),
                val != null ? val.getInstanceId() : 0,
                val != null ? getClassName(val) : "null",
                resolveInlineValue(val)
        );
    }

    private void collectHashMapEntries(Instance mapInstance, List<MapEntryInfo> entries) {
        Object tableObj = mapInstance.getValueOfField("table");
        if (!(tableObj instanceof ObjectArrayInstance)) return;
        ObjectArrayInstance table = (ObjectArrayInstance) tableObj;

        for (Instance bucket : (List<Instance>) table.getValues()) {
            Instance node = bucket;
            while (node != null) {
                Object keyObj = node.getValueOfField("key");
                Object valObj = node.getValueOfField("value");
                Instance key = (keyObj instanceof Instance) ? (Instance) keyObj : null;
                Instance val = (valObj instanceof Instance) ? (Instance) valObj : null;
                entries.add(makeMapEntry(key, val));
                Object nextObj = node.getValueOfField("next");
                node = (nextObj instanceof Instance) ? (Instance) nextObj : null;
            }
        }
    }

    private void collectPersistentArrayMapEntries(Instance pamInstance, List<MapEntryInfo> entries) {
        Object arrayObj = pamInstance.getValueOfField("array");
        if (!(arrayObj instanceof ObjectArrayInstance)) return;
        ObjectArrayInstance array = (ObjectArrayInstance) arrayObj;
        List<Instance> values = array.getValues();
        for (int i = 0; i + 1 < values.size(); i += 2) {
            Instance key = values.get(i);
            Instance val = values.get(i + 1);
            if (key == null && val == null) continue;
            entries.add(makeMapEntry(key, val));
        }
    }

    private void collectPersistentHashMapEntries(Instance phmInstance, List<MapEntryInfo> entries) {
        Object rootNode = phmInstance.getValueOfField("root");
        if (rootNode instanceof Instance) {
            walkINode((Instance) rootNode, entries);
        }
        Object hasNull = phmInstance.getValueOfField("hasNull");
        if (hasNull instanceof Boolean && (Boolean) hasNull) {
            Object nullValue = phmInstance.getValueOfField("nullValue");
            Instance val = (nullValue instanceof Instance) ? (Instance) nullValue : null;
            entries.add(makeMapEntry(null, val));
        }
    }

    private void walkINode(Instance node, List<MapEntryInfo> entries) {
        if (node == null) return;
        String nodeClass = getClassName(node);

        if (nodeClass.contains("BitmapIndexedNode")) {
            Object arrayObj = node.getValueOfField("array");
            if (arrayObj instanceof ObjectArrayInstance) {
                List<Instance> arr = ((ObjectArrayInstance) arrayObj).getValues();
                for (int i = 0; i + 1 < arr.size(); i += 2) {
                    Instance key = arr.get(i);
                    Instance valOrNode = arr.get(i + 1);
                    if (key != null) {
                        entries.add(makeMapEntry(key, valOrNode));
                    } else if (valOrNode != null) {
                        walkINode(valOrNode, entries);
                    }
                }
            }
        } else if (nodeClass.contains("ArrayNode")) {
            Object arrayObj = node.getValueOfField("array");
            if (arrayObj instanceof ObjectArrayInstance) {
                List<Instance> arr = ((ObjectArrayInstance) arrayObj).getValues();
                for (Instance child : arr) {
                    if (child != null) walkINode(child, entries);
                }
            }
        } else if (nodeClass.contains("HashCollisionNode")) {
            Object arrayObj = node.getValueOfField("array");
            if (arrayObj instanceof ObjectArrayInstance) {
                List<Instance> arr = ((ObjectArrayInstance) arrayObj).getValues();
                for (int i = 0; i + 1 < arr.size(); i += 2) {
                    entries.add(makeMapEntry(arr.get(i), arr.get(i + 1)));
                }
            }
        }
    }

    // === Task #10: Retained size breakdown ===

    public static class RetainedBreakdownEntry {
        public String className;
        public long totalSize;
        public long instanceCount;

        public RetainedBreakdownEntry(String className, long totalSize, long instanceCount) {
            this.className = className;
            this.totalSize = totalSize;
            this.instanceCount = instanceCount;
        }
    }

    public List<RetainedBreakdownEntry> getRetainedBreakdown(long id, int topN) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        // BFS to collect all retained objects
        Map<String, long[]> classStats = new HashMap<>(); // className -> [totalSize, count]
        List<Instance> queue = new ArrayList<>();
        java.util.Set<Long> visited = new java.util.HashSet<>();
        queue.add(instance);
        visited.add(instance.getInstanceId());

        int maxObjects = 100_000; // safety limit
        int processed = 0;

        while (!queue.isEmpty() && processed < maxObjects) {
            Instance current = queue.remove(0);
            processed++;
            String clsName = getClassName(current);
            long[] stats = classStats.computeIfAbsent(clsName, k -> new long[]{0, 0});
            stats[0] += current.getSize();
            stats[1]++;

            // Follow outgoing references
            for (Object fvObj : current.getFieldValues()) {
                if (fvObj instanceof ObjectFieldValue) {
                    Instance ref = ((ObjectFieldValue) fvObj).getInstance();
                    if (ref != null && visited.add(ref.getInstanceId())) {
                        queue.add(ref);
                    }
                }
            }
            // Also handle object arrays
            if (current instanceof ObjectArrayInstance) {
                for (Instance ref : (List<Instance>) ((ObjectArrayInstance) current).getValues()) {
                    if (ref != null && visited.add(ref.getInstanceId())) {
                        queue.add(ref);
                    }
                }
            }
        }

        return classStats.entrySet().stream()
                .map(e -> new RetainedBreakdownEntry(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .sorted(Comparator.comparingLong((RetainedBreakdownEntry e) -> e.totalSize).reversed())
                .limit(topN)
                .collect(Collectors.toList());
    }

    // === Task #11: Path finder ===

    public List<PathElement> findPath(long fromId, long toId, int maxDepth) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance fromInst = heap.getInstanceByID(fromId);
        Instance toInst = heap.getInstanceByID(toId);
        if (fromInst == null) throw new IllegalArgumentException("Instance not found: " + fromId);
        if (toInst == null) throw new IllegalArgumentException("Instance not found: " + toId);

        // BFS from 'from' to 'to'
        Map<Long, Long> parentMap = new HashMap<>();
        List<Instance> queue = new ArrayList<>();
        queue.add(fromInst);
        parentMap.put(fromId, -1L);

        while (!queue.isEmpty()) {
            Instance current = queue.remove(0);
            if (current.getInstanceId() == toId) {
                // Reconstruct path
                List<PathElement> path = new ArrayList<>();
                long curId = toId;
                while (curId != -1L) {
                    Instance inst = heap.getInstanceByID(curId);
                    path.add(0, new PathElement(curId, getClassName(inst)));
                    curId = parentMap.get(curId);
                }
                return path;
            }

            if (parentMap.size() > maxDepth * 1000) break; // safety limit

            for (Object fvObj : current.getFieldValues()) {
                if (fvObj instanceof ObjectFieldValue) {
                    Instance ref = ((ObjectFieldValue) fvObj).getInstance();
                    if (ref != null && !parentMap.containsKey(ref.getInstanceId())) {
                        parentMap.put(ref.getInstanceId(), current.getInstanceId());
                        queue.add(ref);
                    }
                }
            }
            if (current instanceof ObjectArrayInstance) {
                for (Instance ref : (List<Instance>) ((ObjectArrayInstance) current).getValues()) {
                    if (ref != null && !parentMap.containsKey(ref.getInstanceId())) {
                        parentMap.put(ref.getInstanceId(), current.getInstanceId());
                        queue.add(ref);
                    }
                }
            }
        }

        return List.of(); // no path found
    }

    // === Task #12: Dominator tree ===

    public static class DominatorNode {
        public long instanceId;
        public String className;
        public long retainedSize;
        public long shallowSize;
        public List<DominatorNode> children;

        public DominatorNode(long instanceId, String className, long retainedSize, long shallowSize, List<DominatorNode> children) {
            this.instanceId = instanceId;
            this.className = className;
            this.retainedSize = retainedSize;
            this.shallowSize = shallowSize;
            this.children = children;
        }
    }

    public DominatorNode getDominatorTree(long id, int maxDepth, int maxChildren) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);
        return buildDominatorNode(instance, maxDepth, maxChildren, 0);
    }

    private DominatorNode buildDominatorNode(Instance instance, int maxDepth, int maxChildren, int currentDepth) {
        List<DominatorNode> children = new ArrayList<>();
        if (currentDepth < maxDepth) {
            // Collect direct references as "dominated" children, sorted by retained size
            List<Instance> refs = new ArrayList<>();
            for (Object fvObj : instance.getFieldValues()) {
                if (fvObj instanceof ObjectFieldValue) {
                    Instance ref = ((ObjectFieldValue) fvObj).getInstance();
                    if (ref != null) refs.add(ref);
                }
            }
            if (instance instanceof ObjectArrayInstance) {
                for (Instance ref : (List<Instance>) ((ObjectArrayInstance) instance).getValues()) {
                    if (ref != null) refs.add(ref);
                }
            }
            refs.sort(Comparator.comparingLong(Instance::getRetainedSize).reversed());
            int limit = Math.min(maxChildren, refs.size());
            for (int i = 0; i < limit; i++) {
                children.add(buildDominatorNode(refs.get(i), maxDepth, maxChildren, currentDepth + 1));
            }
        }
        return new DominatorNode(
                instance.getInstanceId(),
                getClassName(instance),
                instance.getRetainedSize(),
                instance.getSize(),
                children
        );
    }

    // Expose heap for OQL ID resolution (Task #3)
    public Heap getHeap() {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        return heap;
    }

    private String convertToNetBeansOql(String query) {
        query = query.trim();
        if (query.toLowerCase().startsWith("select")) {
            String lowerQuery = query.toLowerCase();
            int fromIndex = lowerQuery.indexOf("from");
            if (fromIndex > 0) {
                int classStart = fromIndex + 5;
                int whereIndex = lowerQuery.indexOf("where");
                int aliasEnd = whereIndex > 0 ? whereIndex : query.length();
                
                String className = query.substring(classStart, aliasEnd).trim();
                String alias = "o";
                String whereClause = "";
                
                String beforeFrom = query.substring(6, fromIndex).trim();
                if (beforeFrom.equals("*") || beforeFrom.isEmpty()) {
                    if (whereIndex > 0) {
                        whereClause = query.substring(whereIndex + 5).trim();
                        return "heap.forEachObject(function(" + alias + ") { if (" + whereClause + ") { print('" + alias + "'); } }, '" + className + "')";
                    }
                    return "heap.forEachObject(function(" + alias + ") { print('" + alias + "'); }, '" + className + "')";
                }
            }
        }
        return query;
    }
}
