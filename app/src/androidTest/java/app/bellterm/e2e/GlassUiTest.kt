package app.bellterm.e2e

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import app.bellterm.BelltermApp
import app.bellterm.R
import app.bellterm.data.ThemeMode
import app.bellterm.ui.MainActivity
import java.io.File
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * End-to-end checks of the glass UI on a real device, driven through the
 * accessibility tree as a user would. Every test leaves the app's data as it
 * found it. The connection test runs only when the instrumentation names a
 * host whose authorized_keys already trusts this device's key:
 *
 *     adb shell am instrument -w -e host 192.168.0.100 -e port 2200 -e user me \
 *         app.bellterm.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Screenshots land in the app's external files, under e2e/.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GlassUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val context: Context = instrumentation.targetContext
    private val app get() = context.applicationContext as BelltermApp
    private val shots by lazy { File(context.getExternalFilesDir(null), "e2e").apply { mkdirs() } }

    @Before
    fun launch() {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        need(By.res("action:keys"), "home screen")
        device.waitForIdle()
    }

    /** Whatever a test created, even one that failed midway, goes away with it. */
    @After
    fun cleanUp() {
        instrumentation.runOnMainSync {
            app.nodes.nodes.value.filter { it.name.startsWith("e2e-") }.forEach { node ->
                app.disconnect(node.id)
                app.nodes.delete(node)
            }
        }
    }

    @Test
    fun t1_homeShowsGlassChrome() {
        need(By.res("action:more"))
        need(By.text(string(R.string.app_name)))
        assertTrue(device.hasObject(By.res("add_node")))
        shot("01_home")
    }

    @Test
    fun t2_addEditDeleteNode() {
        need(By.res("add_node")).click()
        need(By.res("field:name"))
        assertFalse("save starts disabled", need(By.res("save")).isEnabled)
        shot("02_editor_empty")

        type("field:name", NODE)
        type("field:address", "e2e.invalid")
        type("field:user", "tester")
        type("field:port", "2222")
        assertTrue("save enables once the form is valid", waitEnabled(By.res("save")))
        shot("03_editor_filled")
        need(By.res("save")).click()

        need(By.res("node:$NODE"), "new node card")
        assertTrue(device.hasObject(By.text("tester@e2e.invalid:2222")))
        shot("04_home_with_node")

        need(By.res("node_more:$NODE")).click()
        need(By.text(string(R.string.edit))).click()
        assertEquals(NODE, need(By.res("field:name")).text)
        type("field:name", RENAMED)
        need(By.res("save")).click()
        need(By.res("node:$RENAMED"), "renamed node card")
        assertFalse(device.hasObject(By.res("node:$NODE")))

        deleteNode(RENAMED)
        assertTrue(device.wait(Until.gone(By.res("node:$RENAMED")), TIMEOUT))
    }

    @Test
    fun t3_searchFiltersNodes() {
        assumeTrue("search needs a node", app.nodes.nodes.value.isNotEmpty())
        need(By.res("action:search")).click()
        need(By.res("search_field")).text = "zzz-no-such-node"
        need(By.text(string(R.string.no_matches)))
        shot("05_search_empty")
        need(By.res("action:search")).click()
        assertTrue(device.wait(Until.gone(By.res("search_field")), TIMEOUT))
        assertTrue(device.hasObject(By.res(Pattern.compile("node:.*"))))
    }

    @Test
    fun t4_settingsSwitchesTheme() {
        val before = app.settings.settings.value.themeMode
        openMenu(R.string.settings)
        need(By.res("settings"))
        shot("06_settings")
        need(By.res("segment:${string(R.string.theme_light)}")).click()
        assertTrue("theme becomes light", waitFor { app.settings.settings.value.themeMode == ThemeMode.Light })
        device.waitForIdle()
        shot("07_settings_light")
        need(By.res("segment:${string(R.string.theme_dark)}")).click()
        assertTrue("theme becomes dark", waitFor { app.settings.settings.value.themeMode == ThemeMode.Dark })
        app.settings.update { it.copy(themeMode = before) }
        need(By.res("back")).click()
        need(By.res("action:keys"))
    }

    @Test
    fun t5_keysShowDeviceKey() {
        need(By.res("action:keys")).click()
        val key = need(By.res("public_key"))
        assertTrue("device key is Ed25519", key.text.startsWith("ssh-ed25519 "))
        need(By.res("copy_public_key")).click()
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertTrue("public key copied", waitFor { onMain { clipboard.primaryClip?.getItemAt(0)?.text?.toString() } == key.text })
        // From Android 13 the system confirms the copy; Bellterm only does on older releases.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) need(By.text(string(R.string.public_key_copied)), "copied snackbar")
        shot("08_keys")
        need(By.res("back")).click()
        need(By.res("action:keys"))
    }

    @Test
    fun t6_knownHostsAndImport() {
        openMenu(R.string.known_hosts)
        need(By.res("known_hosts"))
        shot("09_known_hosts")
        need(By.res("back")).click()
        openMenu(R.string.import_ssh_config)
        need(By.text(string(R.string.import_help)))
        shot("10_import")
        need(By.res("back")).click()
        need(By.res("action:keys"))
    }

    @Test
    fun t7_connectsToHerdrHost() {
        val args = InstrumentationRegistry.getArguments()
        val host = args.getString("host")
        assumeTrue("no host given; skipping the live connection", host != null)
        val port = args.getString("port") ?: "22"
        val user = args.getString("user") ?: "root"

        need(By.res("add_node")).click()
        type("field:name", HOST_NODE)
        type("field:address", host!!)
        type("field:user", user)
        type("field:port", port)
        assertTrue(waitEnabled(By.res("save")))
        need(By.res("save")).click()
        need(By.res("node:$HOST_NODE")).click()

        val settled = device.wait(
            Until.hasObject(By.res(Pattern.compile("session:state:Connected|session:state_card"))),
            CONNECT_TIMEOUT,
        )
        assertTrue("connection settles", settled)
        if (device.hasObject(By.res("session:state_card"))) {
            shot("11_state_card")
            // Only an unknown host key may be trusted here, and only the one the caller vouched for.
            val expected = args.getString("fingerprint")
            assumeTrue("host key prompt without an expected fingerprint", expected != null)
            assertTrue(device.hasObject(By.textContains(expected!!)))
            need(By.res("session:primary_action")).click()
            need(By.res("session:state:Connected"), timeout = CONNECT_TIMEOUT)
        }
        need(By.res("session:title"))
        need(By.res("session:tabs"))
        need(By.res("extra_keys"))
        device.waitForIdle()
        shot("12_session")

        need(By.res("session:drawer")).click()
        need(By.res("session:drawer_sheet"))
        need(By.res(Pattern.compile("workspace:.*")), "a workspace in the drawer")
        shot("13_drawer")
        device.pressBack()
        assertTrue(device.wait(Until.gone(By.res("session:drawer_sheet")), TIMEOUT))

        need(By.res("session:more")).click()
        need(By.text(string(R.string.split_right)))
        shot("14_session_menu")
        device.pressBack()

        // A long press on a tab offers rename and close; both dialogs open and are cancelled.
        val tab = need(By.res(Pattern.compile("tab:.*")), "a tab pill")
        val label = tab.resourceName.substringAfter("tab:")
        tab.longClick()
        need(By.text(string(R.string.close_tab)))
        shot("15_tab_menu")
        need(By.text(string(R.string.rename_tab))).click()
        assertEquals(label, need(By.res("prompt_field")).text)
        need(By.text(string(android.R.string.cancel))).click()
        need(By.res(Pattern.compile("tab:.*"))).longClick()
        need(By.text(string(R.string.close_tab))).click()
        need(By.text(string(R.string.close_tab_title, label)))
        shot("16_close_tab_confirm")
        need(By.text(string(android.R.string.cancel))).click()
        assertTrue("the tab is still there", device.wait(Until.hasObject(By.res("tab:$label")), TIMEOUT))

        // The new-tab dialog opens and is dismissed; the user's session is never changed.
        need(By.res("session:new_tab")).click()
        need(By.text(string(R.string.tab_name)))
        shot("17_new_tab_dialog")
        need(By.text(string(android.R.string.cancel))).click()

        device.pressBack()
        need(By.res("node:$HOST_NODE"))
        // The card's status pill starts with "Attached", before the counts.
        need(By.textStartsWith(string(R.string.attached_summary, 0, 0).substringBefore("·").trim()))
        shot("18_home_attached")
        deleteNode(HOST_NODE)
        assertTrue(device.wait(Until.gone(By.res("node:$HOST_NODE")), TIMEOUT))
        assertTrue("deleting the node disconnects it", waitFor { app.connections.value.isEmpty() })
    }

    private fun deleteNode(name: String) {
        need(By.res("node_more:$name")).click()
        need(By.text(string(R.string.delete))).click()
        need(By.text(string(R.string.delete_node_title, name)))
        need(By.text(string(R.string.delete))).click()
    }

    private fun openMenu(item: Int) {
        need(By.res("action:more")).click()
        need(By.text(string(item))).click()
    }

    private fun type(tag: String, value: String) {
        need(By.res(tag)).text = value
    }

    private fun string(id: Int, vararg args: Any): String = context.getString(id, *args)

    private fun need(selector: BySelector, what: String = selector.toString(), timeout: Long = TIMEOUT): UiObject2 {
        val found = device.wait(Until.findObject(selector), timeout)
        assertNotNull("not found: $what", found)
        return found!!
    }

    private fun <T> onMain(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitEnabled(selector: BySelector): Boolean = waitFor { device.findObject(selector)?.isEnabled == true }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + TIMEOUT
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    private fun shot(name: String) {
        Thread.sleep(SETTLE)
        device.takeScreenshot(File(shots, "$name.png"))
    }

    private companion object {
        const val NODE = "e2e-node"
        const val RENAMED = "e2e-renamed"
        const val HOST_NODE = "e2e-host"
        const val TIMEOUT = 8_000L
        const val CONNECT_TIMEOUT = 30_000L
        const val SETTLE = 600L
    }
}
