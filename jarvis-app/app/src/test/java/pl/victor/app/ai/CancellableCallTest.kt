package pl.victor.app.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Przerwana tura ma przerwać też zapytanie HTTP - patrz [executeCancellable].
 *
 * Sztuczne połączenie wisi w execute(), dopóki ktoś go nie zamknie - dokładnie
 * tak, jak wisi odczyt odpowiedzi modelu, która jeszcze się generuje.
 */
class CancellableCallTest {

    private class WiszącePołączenie : Call {
        private val zamknięte = CountDownLatch(1)
        @Volatile var anulowane = false
        override fun request(): Request = Request.Builder().url("http://localhost/").build()
        override fun execute(): Response {
            zamknięte.await(10, TimeUnit.SECONDS)
            throw IOException("Canceled")
        }
        override fun enqueue(responseCallback: Callback) = throw UnsupportedOperationException()
        override fun cancel() { anulowane = true; zamknięte.countDown() }
        override fun isExecuted(): Boolean = true
        override fun isCanceled(): Boolean = anulowane
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = this
    }

    @Test
    fun `limit czasu przerywa wiszace zapytanie`() = runBlocking {
        val połączenie = WiszącePołączenie()
        val start = System.currentTimeMillis()
        val wynik = withTimeoutOrNull(300) {
            kotlinx.coroutines.withContext(Dispatchers.IO) {
                połączenie.executeCancellable { "odpowiedź" }
            }
        }
        val ms = System.currentTimeMillis() - start
        assertNull(wynik)
        assertTrue("połączenie ma zostać zamknięte", połączenie.anulowane)
        assertTrue("przerwane po $ms ms - za długo", ms < 2_000)
    }

    @Test
    fun `anulowanie tury daje anulowanie, nie blad dostawcy`() = runBlocking {
        val połączenie = WiszącePołączenie()
        val zadanie = async(Dispatchers.IO) { połączenie.executeCancellable { "odpowiedź" } }
        delay(200)
        zadanie.cancel()
        val błąd = runCatching { zadanie.await() }.exceptionOrNull()
        assertTrue("oczekiwano anulowania, jest: $błąd", błąd is CancellationException)
        assertTrue(połączenie.anulowane)
    }
}
