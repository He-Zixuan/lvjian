package com.lvjian.collectionprobe;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Bundle;

/** Local-only notification source for exercising the real Android collection pipeline. */
public class ProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("probe", "Collection test", NotificationManager.IMPORTANCE_LOW));
        String text = getIntent().getStringExtra("text");
        if (text != null) {
            manager.notify(1, new Notification.Builder(this, "probe")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Collection Probe")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .build());
        }
        finish();
    }
}
