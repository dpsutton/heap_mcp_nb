package com.onpositive.analyzer;

import org.netbeans.lib.profiler.heap.HeapSummary;

import java.util.List;

/**
 * Quick smoke test against a real heap dump.
 * Not a unit test — run manually.
 */
public class LiveHeapTest {

    public static void main(String[] args) throws Exception {
        String path = "/Users/dan/projects/java/heap-dump-mcp/repro/java_pid13688.hprof";
        HeapDumpService svc = new HeapDumpService();

        System.out.println("=== Loading heap ===");
        HeapSummary summary = svc.loadHeap(path);
        System.out.printf("Instances: %d, Size: %d bytes (%.1f MB)%n",
                summary.getTotalLiveInstances(), summary.getTotalLiveBytes(),
                summary.getTotalLiveBytes() / 1_000_000.0);

        System.out.println("\n=== Top 10 classes by instance count ===");
        for (HeapDumpService.ClassStats cs : svc.getClassesByMaxInstancesCount(0, 10)) {
            System.out.printf("  %-60s count=%d size=%d%n", cs.className, cs.instanceCount, cs.size);
        }

        System.out.println("\n=== Top 10 classes by size ===");
        for (HeapDumpService.ClassStats cs : svc.getClassesByMaxInstancesSize(0, 10)) {
            System.out.printf("  %-60s count=%d size=%d%n", cs.className, cs.instanceCount, cs.size);
        }

        System.out.println("\n=== Biggest objects (retained) ===");
        var biggest = svc.getBiggestObjectsByRetainedSize(10);
        for (var inst : biggest) {
            System.out.printf("  ID=%d class=%s retained=%d%n",
                    inst.getInstanceId(), inst.getJavaClass().getName(), inst.getRetainedSize());
        }

        if (!biggest.isEmpty()) {
            long topId = biggest.get(0).getInstanceId();
            System.out.printf("%n=== Instance details for top object (ID=%d) ===%n", topId);
            HeapDumpService.InstanceInfo info = svc.getInstanceById(topId);
            System.out.printf("  Class: %s, Size: %d, Retained: %d%n", info.className, info.size, info.retainedSize);
            for (HeapDumpService.FieldInfo f : info.fields) {
                System.out.printf("  field: %s = %s%s%n", f.name, f.value,
                        f.objectInstanceId != null ? " (ref ID=" + f.objectInstanceId + ")" : "");
            }

            System.out.printf("%n=== Retained breakdown for top object ===%n");
            for (HeapDumpService.RetainedBreakdownEntry e : svc.getRetainedBreakdown(topId, 15)) {
                System.out.printf("  %-60s size=%d count=%d%n", e.className, e.totalSize, e.instanceCount);
            }

            System.out.printf("%n=== GC root path for top object ===%n");
            HeapDumpService.GCRootPathInfo rootInfo = svc.getGCRootFor(topId);
            for (int i = 0; i < rootInfo.path.size(); i++) {
                HeapDumpService.PathElement pe = rootInfo.path.get(i);
                System.out.printf("  %s[%d] %s%n", "  ".repeat(i), pe.instanceId, pe.className);
            }
            if (rootInfo.rootKind != null) System.out.println("  Root kind: " + rootInfo.rootKind);
            if (rootInfo.threadName != null) System.out.println("  Thread: " + rootInfo.threadName);
            if (rootInfo.stackTrace != null) {
                System.out.println("  Stack:");
                for (StackTraceElement frame : rootInfo.stackTrace) {
                    System.out.println("    at " + frame);
                }
            }
        }

        System.out.println("\n=== Threads ===");
        List<HeapDumpService.ThreadInfo> threads = svc.getThreads();
        System.out.printf("Found %d threads%n", threads.size());
        for (HeapDumpService.ThreadInfo t : threads) {
            System.out.printf("  \"%s\" (ID=%d) %s%n", t.threadName,
                    t.instanceId,
                    t.stackTrace != null ? t.stackTrace.length + " frames" : "no stack");
        }

        // Try to find and decode a String
        System.out.println("\n=== String decoding test ===");
        var stringInstances = svc.getInstancesByClass("java.lang.String", 0, 5);
        for (HeapDumpService.InstanceInfo si : stringInstances) {
            try {
                String decoded = svc.getStringValue(si.instanceId);
                String preview = decoded.length() > 80 ? decoded.substring(0, 80) + "..." : decoded;
                System.out.printf("  ID=%d -> \"%s\"%n", si.instanceId, preview);
            } catch (Exception e) {
                System.out.printf("  ID=%d -> ERROR: %s%n", si.instanceId, e.getMessage());
            }
        }

        // Try HashMap reading
        System.out.println("\n=== HashMap test ===");
        var hashMaps = svc.getInstancesByClass("java.util.HashMap", 0, 3);
        for (HeapDumpService.InstanceInfo hm : hashMaps) {
            try {
                var entries = svc.getMapEntries(hm.instanceId, 0, 5);
                System.out.printf("  HashMap ID=%d (%d entries shown):%n", hm.instanceId, entries.size());
                for (HeapDumpService.MapEntryInfo e : entries) {
                    String keyDisplay = e.keyInline != null ? "\"" + e.keyInline + "\"" : e.keyClass;
                    System.out.printf("    %s -> %s (ID=%d)%n", keyDisplay, e.valueClass, e.valueId);
                }
            } catch (Exception e) {
                System.out.printf("  HashMap ID=%d -> ERROR: %s%n", hm.instanceId, e.getMessage());
            }
        }

        System.out.println("\n=== Done ===");
    }
}
