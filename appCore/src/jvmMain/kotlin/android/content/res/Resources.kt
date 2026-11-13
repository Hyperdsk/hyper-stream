package android.content.res

/**
 * Desktop stand-in for android.content.res.Resources. Only an extension's assets are available
 * (see [AssetManager]); compiled resources (strings, layouts) are not loaded on desktop.
 */
open class Resources(private val assets: AssetManager = AssetManager(null)) {
    open fun getAssets(): AssetManager = assets
}
