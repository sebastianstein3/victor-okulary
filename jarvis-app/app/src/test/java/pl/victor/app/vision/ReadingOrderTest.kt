package pl.victor.app.vision

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bloki z ramkami - tak jak oddaje je ML Kit. */
private fun block(text: String, left: Int, top: Int, width: Int, height: Int) =
    OCRBlock(text = text, confidence = 1f, boundingBox = Rect(left, top, left + width, top + height))

class ReadingOrderTest {

    @Test
    fun `naglowek idzie pierwszy, nawet gdy stoi nizej`() {
        // Tabliczka: drobny nadruk u góry, wielki napis pod nim.
        val blocks = listOf(
            block("Uwaga, drobny druk nad spodem", 0, 0, 400, 10),
            block("WYJŚCIE", 0, 40, 300, 60),
            block("piętro niżej", 0, 120, 200, 12)
        )
        val out = ReadingOrder.arrange(blocks, "nieużywane")
        assertTrue("nagłówek nie jest pierwszy: $out", out.startsWith("WYJŚCIE"))
    }

    @Test
    fun `reszta nie znika - to przestawienie, nie skracanie`() {
        val blocks = listOf(
            block("Uwaga, drobny druk nad spodem", 0, 0, 400, 10),
            block("WYJŚCIE", 0, 40, 300, 60),
            block("piętro niżej", 0, 120, 200, 12)
        )
        val out = ReadingOrder.arrange(blocks, "nieużywane")
        assertTrue(out.contains("drobny druk"))
        assertTrue(out.contains("piętro niżej"))
    }

    @Test
    fun `reszta idzie w kolejnosci czytania, z gory na dol`() {
        val blocks = listOf(
            block("trzeci", 0, 200, 100, 10),
            block("NAGŁÓWEK", 0, 0, 300, 50),
            block("drugi", 0, 100, 100, 10)
        )
        val out = ReadingOrder.arrange(blocks, "x")
        assertTrue(out.indexOf("drugi") < out.indexOf("trzeci"))
    }

    @Test
    fun `jednolity tekst zostaje bez zmian`() {
        // Strona zwykłego tekstu: najwyższy blok jest wyższy o kilka procent.
        // Przestawianie go na przód byłoby losowym mieszaniem zdań.
        val blocks = listOf(
            block("Pierwsze zdanie.", 0, 0, 300, 20),
            block("Drugie zdanie.", 0, 30, 300, 21),
            block("Trzecie zdanie.", 0, 60, 300, 20)
        )
        assertEquals("oryginał", ReadingOrder.arrange(blocks, "oryginał"))
    }

    @Test
    fun `bez ramek oddaje tekst bez zmian`() {
        val blocks = listOf(
            OCRBlock("A", 1f, null),
            OCRBlock("B", 1f, null)
        )
        assertEquals("oryginał", ReadingOrder.arrange(blocks, "oryginał"))
    }

    @Test
    fun `jeden blok to nie jest nic do porzadkowania`() {
        assertEquals("oryginał", ReadingOrder.arrange(listOf(block("A", 0, 0, 10, 10)), "oryginał"))
    }

    @Test
    fun `pusta lista nie wywala sie`() {
        assertEquals("oryginał", ReadingOrder.arrange(emptyList(), "oryginał"))
    }

    @Test
    fun `naglowek dostaje kropke, zeby nie zlal sie z reszta`() {
        val blocks = listOf(
            block("WYJŚCIE", 0, 40, 300, 60),
            block("piętro niżej", 0, 120, 200, 12)
        )
        val out = ReadingOrder.arrange(blocks, "x")
        assertEquals("WYJŚCIE. piętro niżej", out)
    }
}
