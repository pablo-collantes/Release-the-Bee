package io.github.pablocollantes.releasethebee

import android.app.Application
import io.github.pablocollantes.releasethebee.service.AppStatus

/** Process start: the log file, before anything writes to it. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStatus.init(this)
    }
}
