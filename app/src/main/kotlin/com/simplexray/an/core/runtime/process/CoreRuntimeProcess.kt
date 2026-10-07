package com.simplexray.an.core.runtime.process

import java.io.File

object CoreRuntimeProcess {
    fun builder(executable: File, assets: File, workingDirectory: File = assets): ProcessBuilder =
        ProcessBuilder(executable.path, "run", "-c", "stdin:")
            .directory(workingDirectory)
            .redirectErrorStream(true)
            .apply {
                environment()["XRAY_LOCATION_ASSET"] = assets.path
                environment().remove("XRAY_MPH_CACHE")
            }
}
