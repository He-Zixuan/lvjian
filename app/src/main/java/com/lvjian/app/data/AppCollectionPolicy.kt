package com.lvjian.app.data

object AppCollectionPolicy {
    fun setInstalledApps(
        installedApps: List<Pair<String, String>>,
        existingRows: List<MonitoredAppEntity>,
        enabled: Boolean
    ): List<MonitoredAppEntity> {
        val existingByPackage = existingRows.associateBy { it.packageName }
        return installedApps.distinctBy { it.first }.map { (packageName, appName) ->
            val current = existingByPackage[packageName]
            when {
                current == null -> MonitoredAppEntity(
                    packageName = packageName,
                    appName = appName,
                    enabled = enabled,
                    preset = false
                )
                enabled -> current.copy(
                    appName = appName.ifBlank { current.appName },
                    enabled = true
                )
                else -> current.copy(
                    appName = appName.ifBlank { current.appName },
                    enabled = false,
                    takeover = TakeoverState.OFF,
                    takeoverChannelOk = false
                )
            }
        }
    }
}
