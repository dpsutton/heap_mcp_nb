package com.onpositive.analyzer;

/**
 * Quick smoke test for Clojure eval against a real heap dump.
 * Run manually — not a unit test.
 */
public class ClojureEvalTest {

    public static void main(String[] args) throws Exception {
        String path = "/Users/dan/projects/java/heap-dump-mcp/repro/java_pid13688.hprof";
        HeapDumpService svc = new HeapDumpService();
        svc.loadHeap(path);
        ClojureEvaluator eval = new ClojureEvaluator(svc);

        System.out.println("=== summary ===");
        System.out.println(eval.eval("(summary)"));

        System.out.println("\n=== biggest 5 ===");
        System.out.println(eval.eval("(biggest 5)"));

        System.out.println("\n=== top 5 strings by retained size ===");
        System.out.println(eval.eval(
            "(->> (instances \"java.lang.String\" 0 100) " +
            "     (sort-by :retained >) " +
            "     (take 5) " +
            "     (mapv #(assoc % :value (string (:id %)))))"
        ));

        System.out.println("\n=== fields of biggest object ===");
        System.out.println(eval.eval(
            "(let [top (first (biggest 1))] (fields (:id top)))"
        ));

        System.out.println("\n=== threads with most frames ===");
        System.out.println(eval.eval(
            "(->> (threads) (sort-by :frames >) (take 5))"
        ));

        System.out.println("\n=== gc root of biggest object ===");
        System.out.println(eval.eval(
            "(let [top (first (biggest 1))] (gc-root (:id top)))"
        ));

        System.out.println("\n=== HashMap entries (first map found) ===");
        System.out.println(eval.eval(
            "(let [hm (first (instances \"java.util.HashMap\" 0 1))] " +
            "  (entries (:id hm) 0 5))"
        ));

        System.out.println("\n=== describe biggest object ===");
        System.out.println(eval.eval(
            "(describe (:id (first (biggest 1))))"
        ));

        System.out.println("\n=== Done ===");
    }
}
