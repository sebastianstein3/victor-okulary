package pl.victor.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class WebContentFetcherGuardTest {
    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `adresy prywatne sa blokowane`() {
        listOf("127.0.0.1", "192.168.1.1", "10.0.0.5", "172.16.3.4", "169.254.1.1", "100.64.1.1", "::1", "fd00::1")
            .forEach { assertTrue(it, WebContentFetcher.isPrivateAddress(ip(it))) }
    }

    @Test
    fun `publiczne przechodza`() {
        listOf("8.8.8.8", "93.184.216.34", "2606:4700:4700::1111")
            .forEach { assertFalse(it, WebContentFetcher.isPrivateAddress(ip(it))) }
    }
}
