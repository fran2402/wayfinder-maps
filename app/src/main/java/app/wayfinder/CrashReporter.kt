package app.wayfinder

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Saves the stack trace of an uncaught crash so the next launch can show it and the
 * user can copy or share it.
 */
object CrashReporter {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                val info = "Wayfinder ${BuildConfig.VERSION_NAME} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
                    "${Build.MANUFACTURER} ${Build.MODEL}\nThread: ${thread.name}\n\n"
                File(app.filesDir, FILE).writeText(info + trace)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Returns and clears the last crash report, if any. */
    fun takeLastCrash(context: Context): String? {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull().also { f.delete() }
    }
}
