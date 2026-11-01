package app.wayfinder

import android.app.Application
import org.maplibre.android.MapLibre

class WayfinderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        MapLibre.getInstance(this)
    }
}
