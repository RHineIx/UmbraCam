package umbra.cam.ui

import android.content.ComponentCallbacks2
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import umbra.cam.R
import umbra.cam.ui.theme.UmbraCamTheme
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity(), XposedServiceHelper.OnServiceListener {
    private val isModuleActive = mutableStateOf(false)
    private val serviceFramework = mutableStateOf("")
    private val scopedApps = mutableStateOf<List<String>>(emptyList())
    private var xposedService: XposedService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XposedServiceHelper.registerListener(WeakActivityServiceListener(this))

        setContent {
            UmbraCamTheme {
                MainScreen(
                    isModuleActive = isModuleActive.value,
                    serviceFramework = serviceFramework.value,
                    scopedApps = scopedApps.value,
                    onApplyScopeSelection = ::applyScopeSelection,
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) AppIconCache.clear()
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        isModuleActive.value = true
        serviceFramework.value = service.frameworkName.orEmpty()
        scopedApps.value = runCatching { service.scope.filterNot { it == packageName } }.getOrDefault(emptyList())
    }

    override fun onServiceDied(service: XposedService) {
        xposedService = null
        isModuleActive.value = false
        serviceFramework.value = ""
        scopedApps.value = emptyList()
    }

    private fun applyScopeSelection(selected: Set<String>) {
        val service = xposedService
        if (service == null) {
            Toast.makeText(this, R.string.toast_scope_service_disconnected, Toast.LENGTH_SHORT).show()
            return
        }

        val current = scopedApps.value.toSet()
        val added = (selected - current).toList().sorted()
        val removed = (current - selected).toList().sorted()
        try {
            if (removed.isNotEmpty()) {
                service.removeScope(removed)
                scopedApps.value = (current - removed.toSet()).toList().sorted()
            }
            if (added.isNotEmpty()) {
                requestScope(service, added)
            } else if (removed.isNotEmpty()) {
                Toast.makeText(
                    this,
                    resources.getQuantityString(R.plurals.toast_scope_removed, removed.size, removed.size),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        } catch (error: Throwable) {
            Toast.makeText(
                this,
                getString(R.string.toast_scope_update_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun requestScope(service: XposedService, packages: List<String>) {
        try {
            service.requestScope(packages, object : XposedService.OnScopeEventListener {
                override fun onScopeRequestApproved(approved: List<String>) {
                    runOnUiThread {
                        scopedApps.value = (scopedApps.value + approved)
                            .filterNot { it == packageName }
                            .distinct()
                            .sorted()
                        Toast.makeText(
                            this@MainActivity,
                            resources.getQuantityString(R.plurals.toast_scope_updated, approved.size, approved.size),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }

                override fun onScopeRequestFailed(message: String) {
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            getString(R.string.toast_scope_request_failed, message),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            })
        } catch (error: Throwable) {
            Toast.makeText(
                this,
                getString(R.string.toast_scope_request_failed, error.message.orEmpty()),
                Toast.LENGTH_LONG,
            ).show()
        }
    }
}

private class WeakActivityServiceListener(activity: MainActivity) : XposedServiceHelper.OnServiceListener {
    private val activityReference = WeakReference(activity)

    override fun onServiceBind(service: XposedService) {
        activityReference.get()?.onServiceBind(service)
    }

    override fun onServiceDied(service: XposedService) {
        activityReference.get()?.onServiceDied(service)
    }
}
