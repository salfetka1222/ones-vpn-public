package app.onesvpn

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions

object NativeEngine {
    private var initialized = false

    @Synchronized private fun initialize(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        val base = app.filesDir.resolve("libbox")
        val work = base.resolve("work")
        val temp = app.cacheDir.resolve("libbox")
        require(work.isDirectory || work.mkdirs()) { "NATIVE_WORK_DIRECTORY" }
        require(temp.isDirectory || temp.mkdirs()) { "NATIVE_TEMP_DIRECTORY" }
        // 1.14.4 unconditionally writes a plaintext diagnostic configuration.json.
        // Its WriteFile error is ignored. A directory at that exact path blocks the write,
        // including during crashes/startup, without changing encrypted profile storage.
        val snapshot = base.resolve("configuration.json")
        if (snapshot.isFile) check(snapshot.delete()) { "NATIVE_SNAPSHOT_CLEANUP" }
        check(snapshot.isDirectory || snapshot.mkdir()) { "NATIVE_SNAPSHOT_GUARD" }
        Libbox.setup(SetupOptions().apply {
            basePath = base.absolutePath
            workingPath = work.absolutePath
            tempPath = temp.absolutePath
            appVersion = BuildConfig.VERSION_CODE.toString()
            appMarketingVersion = BuildConfig.VERSION_NAME
            crashReportSource = "onesvpn"
            fixAndroidStack = true
            // Forward error logs to the allowlist classifier, never print native strings.
            debug = true
            logMaxLines = 1
        })
        check(Libbox.version() == "1.14.4") { "NATIVE_VERSION_MISMATCH" }
        initialized = true
    }

    @Synchronized fun validate(context: Context, profile: VpnProfile): Result<Unit> = runCatching {
        initialize(context)
        Libbox.checkConfig(SingBoxConfig.buildTunConfig(profile).toString())
    }
}
