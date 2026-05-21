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

        public FieldInfo(String name, String value, Long objectInstanceId) {
            this.name = name;
            this.value = value;
            this.objectInstanceId = objectInstanceId;
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
            
            if (fv instanceof ObjectFieldValue) {
                ObjectFieldValue ofv = (ObjectFieldValue) fv;
                Instance refInstance = ofv.getInstance();
                if (refInstance != null) {
                    objectInstanceId = refInstance.getInstanceId();
                }
            }
            
            fields.add(new FieldInfo(fieldName, valueStr, objectInstanceId));
        }
        
        return new InstanceInfo(
                instance.getInstanceId(),
                getClassName(instance),
                instance.getSize(),
                instance.getRetainedSize(),
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
        public String value;

        public ArrayElementInfo(int index, String className, long instanceId, String value) {
            this.index = index;
            this.className = className;
            this.instanceId = instanceId;
            this.value = value;
        }
    }

    public ArrayElementInfo getArrayElement(long id, int index) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        if (instance instanceof ObjectArrayInstance) {
            ObjectArrayInstance arr = (ObjectArrayInstance) instance;
            if (index < 0 || index >= arr.getLength()) {
                throw new IndexOutOfBoundsException("Index " + index + " out of bounds for array of length " + arr.getLength());
            }
            List<Instance> values = arr.getValues();
            Instance element = values.get(index);
            if (element == null) {
                return new ArrayElementInfo(index, "null", 0, "null");
            }
            return new ArrayElementInfo(index, getClassName(element), element.getInstanceId(), null);
        }

        // ArrayList: read elementData array, then index into it
        String className = getClassName(instance);
        if (className.equals("java.util.ArrayList")) {
            Object elementData = instance.getValueOfField("elementData");
            if (elementData instanceof ObjectArrayInstance) {
                ObjectArrayInstance arr = (ObjectArrayInstance) elementData;
                Object sizeObj = instance.getValueOfField("size");
                int size = (sizeObj instanceof Number) ? ((Number) sizeObj).intValue() : arr.getLength();
                if (index < 0 || index >= size) {
                    throw new IndexOutOfBoundsException("Index " + index + " out of bounds for ArrayList of size " + size);
                }
                List<Instance> values = arr.getValues();
                Instance element = values.get(index);
                if (element == null) {
                    return new ArrayElementInfo(index, "null", 0, "null");
                }
                return new ArrayElementInfo(index, getClassName(element), element.getInstanceId(), null);
            }
        }

        throw new IllegalArgumentException("Instance " + id + " is not an array or ArrayList, it is: " + className);
    }

    public List<ArrayElementInfo> getArrayElements(long id, int from, int to) {
        if (heap == null) throw new IllegalStateException("Heap not loaded");
        Instance instance = heap.getInstanceByID(id);
        if (instance == null) throw new IllegalArgumentException("Instance not found: " + id);

        List<Instance> values;
        int length;

        if (instance instanceof ObjectArrayInstance) {
            ObjectArrayInstance arr = (ObjectArrayInstance) instance;
            values = arr.getValues();
            length = arr.getLength();
        } else if (getClassName(instance).equals("java.util.ArrayList")) {
            Object elementData = instance.getValueOfField("elementData");
            if (!(elementData instanceof ObjectArrayInstance)) {
                throw new IllegalArgumentException("ArrayList elementData is not an object array");
            }
            ObjectArrayInstance arr = (ObjectArrayInstance) elementData;
            values = arr.getValues();
            Object sizeObj = instance.getValueOfField("size");
            length = (sizeObj instanceof Number) ? ((Number) sizeObj).intValue() : arr.getLength();
        } else {
            throw new IllegalArgumentException("Instance " + id + " is not an array or ArrayList, it is: " + getClassName(instance));
        }

        int safeFrom = Math.max(0, Math.min(from, length));
        int safeTo = Math.max(safeFrom, Math.min(to, length));
        List<ArrayElementInfo> result = new ArrayList<>();
        for (int i = safeFrom; i < safeTo; i++) {
            Instance element = values.get(i);
            if (element == null) {
                result.add(new ArrayElementInfo(i, "null", 0, "null"));
            } else {
                result.add(new ArrayElementInfo(i, getClassName(element), element.getInstanceId(), null));
            }
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
        byte[] bytes = new byte[values.size()];
        for (int i = 0; i < values.size(); i++) {
            bytes[i] = Byte.parseByte(values.get(i));
        }

        // Java 9+ compact strings: coder=0 is Latin-1, coder=1 is UTF-16
        Object coderObj = instance.getValueOfField("coder");
        if (coderObj instanceof Number) {
            int coder = ((Number) coderObj).intValue();
            if (coder == 1) {
                return new String(bytes, StandardCharsets.UTF_16);
            }
        }
        // coder=0 or pre-Java-9 (char[] stored as bytes): Latin-1
        return new String(bytes, StandardCharsets.ISO_8859_1);
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
            result.add(new InstanceInfo(
                    inst.getInstanceId(),
                    getClassName(inst),
                    inst.getSize(),
                    inst.getRetainedSize(),
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
        while (current != null && path.size() < maxDepth) {
            path.add(new PathElement(current.getInstanceId(), getClassName(current)));
            Instance next = current.getNearestGCRootPointer();
            if (next == null || next.getInstanceId() == current.getInstanceId()) break;
            current = next;
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
        public String keyString; // decoded if key is a String
        public long valueId;
        public String valueClass;

        public MapEntryInfo(long keyId, String keyClass, String keyString, long valueId, String valueClass) {
            this.keyId = keyId;
            this.keyClass = keyClass;
            this.keyString = keyString;
            this.valueId = valueId;
            this.valueClass = valueClass;
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

    private void collectHashMapEntries(Instance mapInstance, List<MapEntryInfo> entries) {
        Object tableObj = mapInstance.getValueOfField("table");
        if (!(tableObj instanceof ObjectArrayInstance)) return;
        ObjectArrayInstance table = (ObjectArrayInstance) tableObj;

        for (Instance bucket : (List<Instance>) table.getValues()) {
            Instance node = bucket;
            while (node != null) {
                Instance key = (Instance) node.getValueOfField("key");
                Instance val = (Instance) node.getValueOfField("value");
                String keyStr = tryDecodeString(key);
                entries.add(new MapEntryInfo(
                        key != null ? key.getInstanceId() : 0,
                        key != null ? getClassName(key) : "null",
                        keyStr,
                        val != null ? val.getInstanceId() : 0,
                        val != null ? getClassName(val) : "null"
                ));
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
        // PersistentArrayMap stores [key0, val0, key1, val1, ...]
        for (int i = 0; i + 1 < values.size(); i += 2) {
            Instance key = values.get(i);
            Instance val = values.get(i + 1);
            if (key == null && val == null) continue;
            String keyStr = tryDecodeString(key);
            entries.add(new MapEntryInfo(
                    key != null ? key.getInstanceId() : 0,
                    key != null ? getClassName(key) : "null",
                    keyStr,
                    val != null ? val.getInstanceId() : 0,
                    val != null ? getClassName(val) : "null"
            ));
        }
    }

    private void collectPersistentHashMapEntries(Instance phmInstance, List<MapEntryInfo> entries) {
        Object rootNode = phmInstance.getValueOfField("root");
        if (rootNode instanceof Instance) {
            walkINode((Instance) rootNode, entries);
        }
        // Also check for a null-key entry
        Object hasNull = phmInstance.getValueOfField("hasNull");
        if (hasNull instanceof Boolean && (Boolean) hasNull) {
            Object nullValue = phmInstance.getValueOfField("nullValue");
            Instance val = (nullValue instanceof Instance) ? (Instance) nullValue : null;
            entries.add(new MapEntryInfo(0, "null", null,
                    val != null ? val.getInstanceId() : 0,
                    val != null ? getClassName(val) : "null"));
        }
    }

    private void walkINode(Instance node, List<MapEntryInfo> entries) {
        if (node == null) return;
        String nodeClass = getClassName(node);

        if (nodeClass.contains("BitmapIndexedNode")) {
            Object arrayObj = node.getValueOfField("array");
            if (arrayObj instanceof ObjectArrayInstance) {
                List<Instance> arr = ((ObjectArrayInstance) arrayObj).getValues();
                // array is [key0, valOrNode0, key1, valOrNode1, ...]
                for (int i = 0; i + 1 < arr.size(); i += 2) {
                    Instance key = arr.get(i);
                    Instance valOrNode = arr.get(i + 1);
                    if (key != null) {
                        // It's a key-value pair
                        String keyStr = tryDecodeString(key);
                        entries.add(new MapEntryInfo(
                                key.getInstanceId(), getClassName(key), keyStr,
                                valOrNode != null ? valOrNode.getInstanceId() : 0,
                                valOrNode != null ? getClassName(valOrNode) : "null"));
                    } else if (valOrNode != null) {
                        // It's a sub-node
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
                    Instance key = arr.get(i);
                    Instance val = arr.get(i + 1);
                    if (key != null) {
                        String keyStr = tryDecodeString(key);
                        entries.add(new MapEntryInfo(
                                key.getInstanceId(), getClassName(key), keyStr,
                                val != null ? val.getInstanceId() : 0,
                                val != null ? getClassName(val) : "null"));
                    }
                }
            }
        }
    }

    private String tryDecodeString(Instance instance) {
        if (instance == null) return null;
        try {
            if (getClassName(instance).equals("java.lang.String")) {
                return getStringValue(instance.getInstanceId());
            }
        } catch (Exception e) {
            // Not a string or can't decode
        }
        return null;
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
