package com.lagradost.cloudstream3.plugins

import android.content.Context
import android.content.res.Resources

/**
 * Shim for CloudStream's app-module `Plugin`, the base class most CloudStream plugins extend. Plugins compile against
 * the whole CloudStream app, so Celastrina provides this one class from it under the same name and signatures.
 *
 * Celastrina calls [load] with the application context. It never shows a plugin's own settings screen
 * ([openSettings]), and never gives a plugin [resources]: plugins that need either fail when they use them.
 */
abstract class Plugin : BasePlugin() {

    /** Called when the plugin is loaded; registers its providers. Plugins that don't override it use [load]. */
    @Throws(Throwable::class)
    open fun load(context: Context) {
        load()
    }

    /** The plugin's own resources, for plugins built with `requiresResources`. Celastrina leaves it null. */
    var resources: Resources? = null

    /** The plugin's settings screen. Celastrina never opens it. */
    var openSettings: ((context: Context) -> Unit)? = null
}
