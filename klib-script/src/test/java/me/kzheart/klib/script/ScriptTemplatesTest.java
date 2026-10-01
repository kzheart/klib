package me.kzheart.klib.script;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class ScriptTemplatesTest {
    @Test void evaluatesInnermostFirstAndUsesTheResultInTheOuterExpression() {
        List<String> seen = new ArrayList<String>();
        String text = ScriptTemplates.renderImmediate("A{{ outer {{ inner }} }}B{{ last }}", source -> {
            seen.add(source);
            return CompletableFuture.completedFuture(source.trim().equals("inner") ? "value" : source.trim());
        });
        assertEquals("Aouter valueBlast", text);
        assertEquals(Arrays.asList(" inner ", " outer value ", " last "), seen);
    }

    @Test void unescapesDelimitersWithoutEvaluatingEscapedSegments() {
        assertEquals("{{ literal }} and X", ScriptTemplates.renderImmediate("\\{{ literal \\}} and {{ real }}",
                source -> { assertEquals(" real ", source); return CompletableFuture.completedFuture("X"); }));
        assertEquals("{{ open", ScriptTemplates.renderImmediate("\\{{ open", source -> { fail(); return null; }));
    }

    @Test void formatsEscapedNestedDelimitersBeforePassingAnExpressionToTheEvaluator() {
        assertEquals("done", ScriptTemplates.renderImmediate("{{ outer \\{{ inner \\}} }}", source -> {
            assertEquals(" outer {{ inner }} ", source); return CompletableFuture.completedFuture("done");
        }));
    }

    @Test void incompleteAndNullValuesRenderNullWithoutWaitingOrCancelling() {
        CompletableFuture<Object> pending = new CompletableFuture<Object>();
        assertEquals("null/null", ScriptTemplates.renderImmediate("{{ pending }}/{{ empty }}", source ->
                source.trim().equals("pending") ? pending : CompletableFuture.completedFuture(null)));
        assertFalse(pending.isDone()); pending.complete("later"); assertEquals("later", pending.join());
    }

    @Test void failuresPropagateAndUnmatchedDelimitersKeepTheOriginalScanningPolicy() {
        CompletableFuture<Object> failure = new CompletableFuture<Object>();
        failure.completeExceptionally(new IllegalStateException("bad"));
        assertThrows(CompletionException.class, () -> ScriptTemplates.renderImmediate("{{ fail }}", source -> failure));
        assertEquals("}} before {{ good }}", ScriptTemplates.renderImmediate("}} before {{ good }}", source -> {
            fail("unmatched first closing delimiter stops original scanner"); return null;
        }));
    }

    @Test void canUseAnActualKlibEngineWithoutChangingInlineSemantics() {
        KetherScriptEngine engine = new KetherScriptEngine(new StatementRegistry());
        ScriptContext context = ScriptContext.builder().variable("value", 7).build();
        assertEquals("value=7", ScriptTemplates.renderImmediate("value={{ &value }}", script -> engine.eval(script, context)));
        assertEquals("value=7", engine.eval("inline *\"value={{ &value }}\"", context).toCompletableFuture().join());
    }
}
