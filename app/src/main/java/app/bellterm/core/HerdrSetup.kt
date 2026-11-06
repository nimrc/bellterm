package app.bellterm.core

import org.json.JSONObject

/** How installing or updating Herdr on a host ended, from [Native.setupHerdr]. */
data class HerdrSetup(val exitStatus: Int?, val output: String) {
    val succeeded: Boolean get() = exitStatus == 0

    companion object {
        fun parse(json: String): HerdrSetup = JSONObject(json).let {
            HerdrSetup(
                exitStatus = if (it.isNull("exit_status")) null else it.getInt("exit_status"),
                output = it.optString("output"),
            )
        }
    }
}
