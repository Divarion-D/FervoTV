package app.iptvplayer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * UI-022: interface text lives in resources, not in Kotlin. Fails on a string literal with Cyrillic in the app's
 * code (comments don't count): such text would stay Russian in the English interface.
 */
class NoUiTextInCodeTest {
    @Test
    fun noCyrillicStringLiterals() {
        val found = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { f ->
            literals(f.readText()).filter { (_, s) -> s.any { it in 'А'..'я' || it == 'Ё' || it == 'ё' } }
                .map { (line, s) -> "${f.path}:$line: $s" }
        }.toList()
        assertEquals("Move these to res/values*/strings.xml", emptyList<String>(), found)
    }

    @Test
    fun scannerSkipsCommentsAndFindsLiterals() {
        val code = "val a = \"http://x\" // \"коммент\"\n/* \"блок\" */ val b = \"текст \\\"в\\\" кавычках\"\nval c = \"\"\"сырой\"\"\""
        assertEquals(listOf(1 to "http://x", 2 to "текст \\\"в\\\" кавычках", 3 to "сырой"), literals(code))
    }

    /** (line, text) of every string literal; skips // and /* */ comments and char literals. */
    private fun literals(code: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        var line = 1
        fun at(s: String) = code.startsWith(s, i)
        while (i < code.length) {
            when {
                code[i] == '\n' -> { line++; i++ }
                at("//") -> while (i < code.length && code[i] != '\n') i++
                at("/*") -> {
                    val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                    line += code.substring(i, end).count { it == '\n' }
                    i = end
                }
                at("\"\"\"") -> {
                    val end = code.indexOf("\"\"\"", i + 3).let { if (it < 0) code.length else it }
                    out += line to code.substring(i + 3, end)
                    line += code.substring(i, end).count { it == '\n' }
                    i = end + 3
                }
                code[i] == '"' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '"' && code[j] != '\n') j += if (code[j] == '\\') 2 else 1
                    out += line to code.substring(i + 1, minOf(j, code.length))
                    i = j + 1
                }
                code[i] == '\'' -> {
                    var j = i + 1
                    while (j < code.length && code[j] != '\'') j += if (code[j] == '\\') 2 else 1
                    i = j + 1
                }
                else -> i++
            }
        }
        return out
    }
}
