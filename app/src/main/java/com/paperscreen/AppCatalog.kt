package com.paperscreen

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import java.text.Collator
import java.util.concurrent.Executors

/**
 * The launchable apps for the home screen, kept current as apps are installed, updated or
 * removed. Loads labels off the main thread; [onChanged] is called on the main thread.
 *
 * Seeing other apps needs the `<queries>` entry for launcher activities in the manifest
 * (package visibility), not a permission.
 */
class AppCatalog(context: Context, private val onChanged: (List<App>) -> Unit) {

    class App(val label: String, val component: ComponentName, val user: UserHandle)

    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private var generation = 0

    var apps: List<App> = emptyList()
        private set

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            reload()
    }

    fun start() {
        launcherApps.registerCallback(callback, main)
        reload()
    }

    fun stop() {
        launcherApps.unregisterCallback(callback)
        loader.shutdownNow()
    }

    fun find(flattened: String?): App? {
        val component = flattened?.let(ComponentName::unflattenFromString) ?: return null
        return apps.firstOrNull { it.component == component }
    }

    fun reload() {
        val current = ++generation
        loader.execute {
            val collator = Collator.getInstance().apply { strength = Collator.PRIMARY }
            // Only activities with a launcher entry are listed: PaperScreen's settings, not
            // the home screen itself.
            val loaded = launcherApps.getActivityList(null, Process.myUserHandle())
                .map { App(it.label.toString(), it.componentName, it.user) }
                .sortedWith { a, b -> collator.compare(a.label, b.label) }
            main.post {
                if (current != generation) return@post
                apps = loaded
                onChanged(loaded)
            }
        }
    }
}
