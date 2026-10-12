package umbra.cam.ui

import android.content.ComponentCallbacks2
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import umbra.cam.ui.theme.UmbraCamTheme
import java.lang.ref.WeakReference

class MainActivity : ComponentActivity(), XposedServiceHelper.OnServiceListener {
    private val isModuleActive = mutableStateOf(false)
    private val serviceFramework = mutableStateOf("")
    private var xposedService: XposedService? = null
    
    // Delegation logic moved to ScopeManager for better modularity
    private val scopeManager = ScopeManager()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XposedServiceHelper.registerListener(WeakActivityServiceListener(this))

        setContent {
            UmbraCamTheme {
                MainScreen(
                    isModuleActive = isModuleActive.value,
                    serviceFramework = serviceFramework.value,
                    scopedApps = scopeManager.scopedApps,
                    onApplyScopeSelection = { selected ->
                        scopeManager.applyScopeSelection(this, xposedService, packageName, selected)
                    }
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
        scopeManager.updateFromService(service, packageName)
    }

    override fun onServiceDied(service: XposedService) {
        xposedService = null
        isModuleActive.value = false
        serviceFramework.value = ""
        scopeManager.clearApps()
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
