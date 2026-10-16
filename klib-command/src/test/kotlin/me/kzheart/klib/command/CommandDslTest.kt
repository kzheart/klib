package me.kzheart.klib.command
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class CommandDslTest {
    @Test fun `typed nested arguments use original parser and identity`() {
        val spec = CommandSpecImpl.command("demo")
        val amount = Arguments.integer("amount", 1, 64)
        var actual = 0
        CommandDsl(spec).apply {
            literal("give") { argument(amount) { executes { value -> actual = value + get(amount) } } }
        }
        val dispatcher = CommandDispatcher(spec, BukkitPlayerResolver.INSTANCE, { _, _ -> }, DefaultCommandMessages.INSTANCE)
        dispatcher.execute(TestSenders.console().sender(), arrayOf("give", "7"))
        assertEquals(14, actual)
        for (value in listOf("0", "65", "x", "2147483648")) {
            dispatcher.execute(TestSenders.console().sender(), arrayOf("give", value))
            assertEquals(14, actual)
        }
    }
}
