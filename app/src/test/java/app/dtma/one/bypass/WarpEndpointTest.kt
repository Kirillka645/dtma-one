package app.dtma.one.bypass

import org.junit.Assert.assertEquals
import org.junit.Test

class WarpEndpointTest {

    @Test
    fun hostWithPortPreferred() {
        assertEquals(
            "engage.cloudflareclient.com:2408",
            WarpEndpoint.resolve("engage.cloudflareclient.com:2408", "162.159.192.1:0"),
        )
    }

    @Test
    fun v4WithZeroPortUses2408() {
        assertEquals(
            "162.159.192.1:2408",
            WarpEndpoint.resolve("", "162.159.192.1:0"),
        )
    }

    @Test
    fun v4WithRealPortKept() {
        assertEquals(
            "162.159.192.1:2408",
            WarpEndpoint.resolve("", "162.159.192.1:2408"),
        )
    }

    @Test
    fun v4BareIpGets2408() {
        assertEquals(
            "162.159.192.1:2408",
            WarpEndpoint.resolve("", "162.159.192.1"),
        )
    }

    @Test
    fun hostWithoutPortGets2408() {
        assertEquals(
            "engage.cloudflareclient.com:2408",
            WarpEndpoint.resolve("engage.cloudflareclient.com", ""),
        )
    }
}
