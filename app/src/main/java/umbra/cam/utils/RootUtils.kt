package umbra.cam.utils

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/**
 * Handles operations that require Root access or package management interactions.
 */
object RootUtils {
    /**
     * Force stops an application using Root access.
     */
    suspend fun forceStopApp(packageName: String): Boolean =
        withContext(Dispatchers.IO) {
            executeSuCommands(listOf("am force-stop $packageName"))
        }

    /**
     * Force stops multiple applications in a single Root shell session.
     */
    suspend fun forceStopApps(packageNames: Collection<String>): Boolean =
        withContext(Dispatchers.IO) {
            val commands = packageNames
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { "am force-stop $it" }
            if (commands.isEmpty()) return@withContext false
            executeSuCommands(commands)
        }

    /**
     * Launches an application using standard intent. Does not require root.
     */
    fun launchApp(
        context: Context,
        packageName: String,
    ): Boolean =
        try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }

    /**
     * Restarts an application by force stopping it (via Root) and then launching it again.
     */
    suspend fun restartApp(
        context: Context,
        packageName: String,
    ): Boolean {
        val stopped = forceStopApp(packageName)
        delay(600) // Give the OS some time to cleanly kill the process
        val launched = launchApp(context, packageName)
        // Consider it a success if we either successfully stopped it or successfully launched it
        return stopped || launched
    }

    /**
     * Executes a list of shell commands sequentially within a single superuser session.
     */
    private fun executeSuCommands(commands: List<String>): Boolean {
        var process: Process? = null
        var os: DataOutputStream? = null
        return try {
            process = Runtime.getRuntime().exec("su")
            os = DataOutputStream(process.outputStream)
            for (command in commands) {
                os.writeBytes("$command\n")
            }
            os.writeBytes("exit\n")
            os.flush()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        } finally {
            try {
                os?.close()
            } catch (_: Exception) {
            }
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }
}
