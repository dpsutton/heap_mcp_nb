package com.onpositive.analyzer;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

/**
 * Embeds a Clojure runtime for scripted heap analysis.
 * Loads a stdlib (heap-analyzer.core) that wraps HeapDumpService
 * methods as Clojure functions.
 */
public class ClojureEvaluator {

    private final HeapDumpService service;
    private boolean initialized = false;

    // Cached Clojure functions
    private IFn evalFn;
    private IFn readStringFn;
    private IFn prStrFn;
    private IFn pushBindingsFn;
    private IFn popBindingsFn;
    private IFn hashMapFn;
    private Object nsVar;
    private Object heapNs;

    public ClojureEvaluator(HeapDumpService service) {
        this.service = service;
    }

    private synchronized void ensureInitialized() {
        if (initialized) return;

        IFn require = Clojure.var("clojure.core", "require");
        IFn intern = Clojure.var("clojure.core", "intern");
        IFn createNs = Clojure.var("clojure.core", "create-ns");
        IFn theNs = Clojure.var("clojure.core", "the-ns");
        IFn alterVarRoot = Clojure.var("clojure.core", "alter-var-root");
        IFn constantly = Clojure.var("clojure.core", "constantly");

        // Create the namespace and intern *service* before loading
        createNs.invoke(Clojure.read("heap-analyzer.core"));
        intern.invoke(
                Clojure.read("heap-analyzer.core"),
                Clojure.read("*service*"),
                service);

        // Load the stdlib
        require.invoke(Clojure.read("heap-analyzer.core"));

        // Ensure *service* root binding is set
        alterVarRoot.invoke(
                Clojure.var("heap-analyzer.core", "*service*"),
                constantly.invoke(service));

        // Cache functions for eval
        this.evalFn = Clojure.var("clojure.core", "eval");
        this.readStringFn = Clojure.var("clojure.core", "read-string");
        this.prStrFn = Clojure.var("clojure.core", "pr-str");
        this.pushBindingsFn = Clojure.var("clojure.core", "push-thread-bindings");
        this.popBindingsFn = Clojure.var("clojure.core", "pop-thread-bindings");
        this.hashMapFn = Clojure.var("clojure.core", "hash-map");
        this.nsVar = Clojure.var("clojure.core", "*ns*");
        this.heapNs = theNs.invoke(Clojure.read("heap-analyzer.core"));

        initialized = true;
    }

    /**
     * Evaluate a Clojure expression string.
     * The expression has access to all heap-analyzer.core functions.
     */
    public String eval(String code) {
        ensureInitialized();

        // Push thread bindings so *ns* is set to heap-analyzer.core
        pushBindingsFn.invoke(hashMapFn.invoke(nsVar, heapNs));
        try {
            Object form = readStringFn.invoke(code);
            Object result = evalFn.invoke(form);
            if (result == null) return "nil";
            return (String) prStrFn.invoke(result);
        } catch (Exception e) {
            throw new RuntimeException("Clojure eval error: " + e.getMessage(), e);
        } finally {
            popBindingsFn.invoke();
        }
    }
}
