package me.kzheart.klib.command
/** Startup ownership of unqualified command labels; never grants another plugin's namespace. */
enum class CommandRegistrationPolicy { REJECT, REPLACE_UNQUALIFIED }
