package com.outsystems.plugins.mediacontrols;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

/**
 * Foreground service that owns the "Now Playing" notification while media is
 * active. This is the piece MediaControlsPlugin was missing: posting a
 * MediaStyle notification via NotificationManagerCompat.notify() alone gives
 * the OS a notification to show, but does NOT register a foreground service.
 * Without a real foreground service, Android has no reason to exempt this
 * app's WebView from background JS-timer throttling / Doze network deferral,
 * which is what was surfacing as hls.js fragLoadTimeout after backgrounding.
 *
 * MediaControlsPlugin builds the notification exactly as before and hands it
 * to this service via startForegroundService(); this service just calls
 * startForeground() with it (and again on every update, which is the
 * supported way to refresh a foreground notification's content) and holds a
 * partial wake lock for as long as it's running, so the CPU doesn't fully
 * sleep mid-playback even if the screen is off.
 *
 * A capped wake lock timeout (WAKE_LOCK_SAFETY_TIMEOUT_MS) is used instead of
 * an indefinite acquire() as a safety net - if ACTION_STOP is never received
 * for some reason (app killed, crash, etc.), the lock still self-releases
 * instead of silently draining the battery forever.
 */
public class MediaPlaybackService extends Service {

    public static final String ACTION_START_OR_UPDATE =
            "com.outsystems.plugins.mediacontrols.action.START_OR_UPDATE";
    public static final String ACTION_STOP =
            "com.outsystems.plugins.mediacontrols.action.STOP";
    public static final String EXTRA_NOTIFICATION = "notification";
    public static final String EXTRA_NOTIFICATION_ID = "notificationId";

    private static final String WAKE_LOCK_TAG = "MediaControls:PlaybackWakeLock";
    // Safety cap only - normal teardown happens via ACTION_STOP as soon as
    // playback pauses/stops/hides, well before this would ever fire.
    private static final long WAKE_LOCK_SAFETY_TIMEOUT_MS = 6L * 60L * 60L * 1000L; // 6 hours

    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG);
            wakeLock.setReferenceCounted(false);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopPlaybackForeground();
            return START_NOT_STICKY;
        }

        Notification notification = intent.getParcelableExtra(EXTRA_NOTIFICATION);
        int notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, 1);

        if (notification != null) {
            startForegroundCompat(notificationId, notification);
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_SAFETY_TIMEOUT_MS);
            }
        }

        // START_STICKY: if the OS still kills the process under memory
        // pressure despite the foreground state, ask it to recreate the
        // service (without redelivering the last intent/notification) rather
        // than dropping it permanently.
        return START_STICKY;
    }

    private void startForegroundCompat(int notificationId, Notification notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(notificationId, notification);
        }
    }

    private void stopPlaybackForeground() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service - only ever started.
    }
}
