package com.lvjian.app.notify

/** Reject a notification action created by a different app than the notification source. */
object NotificationTapPolicy {
    fun canReusePendingIntent(sourcePackage: String, creatorPackage: String?): Boolean =
        sourcePackage.isNotBlank() && sourcePackage == creatorPackage
}
