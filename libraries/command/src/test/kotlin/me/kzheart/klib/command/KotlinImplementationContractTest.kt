package me.kzheart.klib.command

import me.kzheart.klib.command.api.CommandHelpStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class KotlinImplementationContractTest {
    @Test fun `nullable Java parser results remain valid factory input and produce argument failures`() {
        val parser = ArgumentParser<String> { null }
        val argument = Arguments.custom("value", parser, null)
        val root = CommandSpecImpl.command("demo")
        root.argument(argument).executes { }
        val result = CommandDispatcher(root).execute(TestSenders.console().sender(), arrayOf("missing"))
        assertEquals(CommandResult.Status.INVALID_ARGUMENT, result.status())
        assertThrows(ArgumentException::class.java) { argument.parse("missing", BukkitPlayerResolver.INSTANCE) }
    }

    @Test fun `optional null defaults preserve the Java argument identity contract`() {
        val optional = Arguments.optional(Arguments.string("value"), null)
        var invoked = false
        val root = CommandSpecImpl.command("demo")
        root.argument(optional).executes { context ->
            invoked = true
            assertNull(context.get(optional))
        }
        assertEquals(CommandResult.Status.SUCCESS, CommandDispatcher(root).execute(TestSenders.console().sender(), emptyArray()).status())
        assertEquals(true, invoked)
    }

    @Test fun `help style copies isolate subsequent builder changes and retain classic nullable templates`() {
        val classic = CommandHelpStyle.preset(CommandHelpStyle.Preset.CLASSIC)
        val builder = classic.toBuilder()
        val snapshot = builder.pageSize(4).build()
        builder.pageSize(9).header("changed")
        assertEquals(4, snapshot.pageSize())
        assertNull(snapshot.header())
        assertNull(snapshot.previous())
        assertNull(snapshot.next())
        assertEquals(7, classic.pageSize())
    }
}
