package me.kzheart.klib.command
import me.kzheart.klib.lang.RichText
class HelpPage(private val page: Int, private val totalPages: Int, private val content: RichText) {
    fun page(): Int = page
    fun totalPages(): Int = totalPages
    fun content(): RichText = content
}
