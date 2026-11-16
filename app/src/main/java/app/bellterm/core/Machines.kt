package app.bellterm.core

import org.json.JSONArray

/** An SSH machine Herdr on a host saved with `herdr machine add`. */
data class Machine(val label: String, val target: String, val session: String)

object Machines {
    /** What [Native.listMachines] returns; the core already dropped disabled machines. */
    fun parse(json: String): List<Machine> {
        val array = JSONArray(json)
        return List(array.length()) {
            val o = array.getJSONObject(it)
            val target = o.getString("target")
            Machine(label = o.optString("label").ifBlank { target }, target = target, session = o.optString("session").ifBlank { "default" })
        }
    }
}
