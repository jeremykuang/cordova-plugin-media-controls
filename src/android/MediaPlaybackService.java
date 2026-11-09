package com.outsystems.plugins.mediacontrols;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Foreground service that owns the "Now Playing" notification while media is
 * active. This is the piece MediaControlsPlugin was missing: posting a
 * MediaStyle notification via NotificationManagerCompat.notify() alone gives
 * the OS a notification to show, but does NOT register a foreground service.
 * Without a real foreground service, Android has no reason to exempt this
 * app's WebView from background JS-timer throttling / Doze network deferral,
 * which is what was surfacing as hls.js fragLoadTimeout after backgrounding.
 *
 * IMPORTANT: the built Notification (which embeds the full album-art Bitmap
 * via setLargeIcon) is handed to this service through a static in-process
 * field (pendingNotification), NOT through an Intent extra. Intent extras are
 * marshalled through a Binder transaction even for same-process calls to
 * startForegroundService() - and a Notification carrying a real bitmap can
 * easily exceed Binder's ~1MB transaction limit, throwing
 * TransactionTooLargeException and crashing the process. Since this service
 * always runs in the same process as the plugin, the static field hands over
 * a plain object reference with no serialization at all.
 *
 * This service only runs while media is actually playing. MediaControlsPlugin
 * demotes it back to a plain (non-foreground) notification via stop() as soon
 * as playback pauses, rather than keeping a "mediaPlayback"-typed foreground
 * service alive while nothing is playing - both because that indefinitely
 * holds the wake lock for no reason, and because Android 14+ can reject
 * starting/updating a "mediaPlayback" foreground service that isn't tied to
 * genuinely active playback.
 *
 * A capped wake lock timeout (WAKE_LOCK_SAFETY_TIMEOUT_MS) is used instead of
 * an indefinite acquire() as a safety net - if stop() is never called for some
 * reason (app killed, crash, etc.), the lock still self-releases instead of
 * silently draining the battery forever.
 */
public class MediaPlaybackService extends Service {

    private static final String ACTION_START_OR_UPDATE =
            "com.outsystems.plugins.mediacontrols.action.START_OR_UPDATE";
    private static final String ACTION_STOP =
            "com.outsystems.plugins.mediacontrols.action.STOP";

    private static final String WAKE_LOCK_TAG = "MediaControls:PlaybackWakeLock";
    // Safety cap only - normal teardown happens via stop() as soon as
    // playback pauses/stops/hides, well before this would ever fire.
    private static final long WAKE_LOCK_SAFETY_TIMEOUT_MS = 6L * 60L * 60L * 1000L; // 6 hours

    // In-process handoff - see class javadoc for why this isn't an Intent extra.
    private static volatile Notification pendingNotification;
    private static volatile int pendingNotificationId = 1;

    private PowerManager.WakeLock wakeLock;

    /** Starts (or refreshes) the foreground notification. Call only while actually playing. */
    public static void start(Context context, int notificationId, Notification notification) {
        pendingNotification = notification;
        pendingNotificationId = notificationId;

        Intent intent = new Intent(context, MediaPlaybackService.class);
        intent.setAction(ACTION_START_OR_UPDATE);
        ContextCompat.startForegroundService(context, intent);
    }

    /** Stops the foreground service and releases the wake lock. Call on pause/stop/hide. */
    public static void stop(Context context) {
        Intent intent = new Intent(context, MediaPlaybackService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

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

        Notification notification = pendingNotification;
        int notificationId = pendingNotificationId;

        if (notification != null) {
            startForegroundCompat(notificationId, notification);
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire(WAKE_LOCK_SAFETY_TIMEOUT_MS);
            }
        }

        // START_STICKY: if the OS still kills the process under memory
        // pressure despite the foreground state, ask it to recreate the
        // service (without redelivering the last intent) rather than
        // dropping it permanently. Note pendingNotification is a static field,
        // so a process restart loses it anyway (expected - if the process was
        // killed, the JS/media session state is gone too and the app needs to
        // rebuild everything from scratch on next launch regardless).
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
