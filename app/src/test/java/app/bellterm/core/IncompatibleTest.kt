package app.bellterm.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class IncompatibleTest {
    @Test
    fun anOutdatedDaemonNamesItsVersionAndTheMinimum() {
        val state = Connection.State.Incompatible.parse(
            JSONObject("""{"type":"version_mismatch","outdated":"daemon","server_version":"0.8.0","min_version":"0.9.0"}"""),
        )
        assertEquals(Connection.State.Incompatible(daemonOutdated = true, serverVersion = "0.8.0", minVersion = "0.9.0"), state)
    }

    @Test
    fun anOutdatedAppWithAnUnknownServerVersion() {
        val state = Connection.State.Incompatible.parse(
            JSONObject("""{"type":"version_mismatch","outdated":"app","server_version":null,"min_version":"0.9.0"}"""),
        )
        assertEquals(Connection.State.Incompatible(daemonOutdated = false, serverVersion = null, minVersion = "0.9.0"), state)
    }
}
