package me.kzheart.klib.script.kether.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ObjectStreamClass;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class KetherCoreJavaContractTest {
    @Test
    void readerProtectedFieldsAndVirtualHooksRemainAvailable() {
        try (SimpleQuestService service = new SimpleQuestService(true)) {
            class Reader extends SimpleReader {
                int parsed;
                Reader(QuestService<?> runtime, BlockReader blocks) { super(runtime, blocks, Collections.emptyList()); }
                @Override protected void beforeParse() { parsed++; }
                int rawIndex() { return index; }
                char[] rawContent() { return content; }
            }
            Reader reader = new Reader(service, new BlockReader("*answer".toCharArray(), service, Collections.emptyList()));
            assertNotNull(reader.nextAction());
            assertEquals(1, reader.parsed);
            assertEquals(7, reader.rawIndex());
            assertArrayEquals("*answer".toCharArray(), reader.rawContent());
        }
    }

    @Test
    void parserBacktrackingOwnsItsCursorAndFoldPreservesExecutionOrder() {
        try (SimpleQuestService service = new SimpleQuestService()) {
            Parser<Integer> integer = Parser.of(QuestReader::nextInt);
            Parser<Integer> failing = Parser.of(reader -> {
                reader.nextToken();
                throw new IllegalArgumentException("try next alternative");
            });
            Parser<Integer> sum = failing.orElse(integer).fold(integer, Integer::sum);
            service.getRegistry().registerAction("sum", Parser.build(sum.map(Parser.Action::point)));
            assertEquals(42, service.newContext(service.load("backtrack", "def main = { sum 20 22 }")).runActions().join());
        }
    }

    @Test
    void cancellationLinkPreservesFailureAndExceptionSerializationId() {
        CompletableFuture<Object> output = new CompletableFuture<>();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        QuestFuture<Object> value = new QuestFuture<>(ParsedAction.noop(), pending);
        QuestFuture.complete(output).accept(value);
        output.cancel(false);
        assertTrue(pending.isCancelled());
        assertEquals(1L, ObjectStreamClass.lookup(QuestCloseException.class).getSerialVersionUID());
        assertNull(new QuestCloseException().getMessage());
    }
}
