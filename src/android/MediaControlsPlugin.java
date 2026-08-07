package com.outsystems.plugins.mediacontrols;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.media.app.NotificationCompat.MediaStyle;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.URL;

/**
 * Lock-screen / notification "Now Playing" controls for OutSystems 11 Mobile apps.
 * Backed by a MediaSessionCompat + a MediaStyle notification, which is what both
 * the Android lock screen and Wear/Auto surfaces read from.
 */
public class MediaControlsPlugin extends CordovaPlugin {

    private static final String CHANNEL_ID = "media_controls_channel";
    private static final String NOTIFICATION_ICON_RES_NAME = "ic_media_notification";
    private static final int NOTIFICATION_ID = 7001;
    // Rapid successive updates (e.g. a track change that fires a pause then a
    // play event within milliseconds of each other) get coalesced into a single
    // notification rebuild, so the buttons/icon don't visibly flicker between
    // intermediate states.
    private static final long NOTIFICATION_DEBOUNCE_MS = 150;

    private MediaSessionCompat mediaSession;
    private CallbackContext actionCallbackContext;
    private Bitmap currentArtwork;
    private boolean hasNext = true;
    private boolean hasPrevious = true;
    private final Handler notificationHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingSessionUpdateRunnable;
    private int notificationIconResId = 0;

    // Latest desired state, applied to the MediaSession + notification together
    // in a single debounced flush (see scheduleSessionUpdate/flushSessionUpdate).
    // This matters because the OS's own lock-screen/notification-shade media
    // widget (Android 13+) reacts directly to mediaSession.setMetadata()/
    // setPlaybackState() calls - NOT to our NotificationManagerCompat.notify()
    // call. A track change typically fires updateNowPlaying (new metadata +
    // state) followed shortly by updatePlaybackState (from the new track's
    // 'play' event) - two separate immediate pushes to the OS, each one a
    // trigger for the system to reflow its button layout, which is what reads
    // as a "flicker". Debouncing just notify() never addressed this, since
    // that widget doesn't listen to notify() at all - only coalescing the
    // actual session pushes does.
    private String pendingTitle = "";
    private String pendingArtist = "";
    private String pendingAlbum = "";
    private long pendingDurationMs = 0;
    private boolean pendingIsPlaying = true;
    private long pendingPositionMs = 0;

    // What was actually last pushed via mediaSession.setPlaybackState(). Android's
    // redesigned media widget (13+) appears to redraw its button row on every
    // setPlaybackState() call, regardless of whether the values actually changed
    // from before - so a plain mid-playlist track skip (isPlaying/hasNext/
    // hasPrevious all identical to the previous track) was still triggering a
    // visible reflow. Tracking what was last pushed lets flushSessionUpdate()
    // skip the call entirely when nothing meaningful actually changed.
    private Boolean lastPushedIsPlaying = null;
    private Boolean lastPushedHasNext = null;
    private Boolean lastPushedHasPrevious = null;

    @Override
    protected void pluginInitialize() {
        createNotificationChannel();

        mediaSession = new MediaSessionCompat(cordova.getActivity(), "OutSystemsMediaControls");
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                sendAction("play", null);
            }

            @Override
            public void onPause() {
                sendAction("pause", null);
            }

            @Override
            public void onSkipToNext() {
                sendAction("next", null);
            }

            @Override
            public void onSkipToPrevious() {
                sendAction("previous", null);
            }

            @Override
            public void onSeekTo(long positionMs) {
                sendAction("seek", positionMs / 1000.0);
            }

            @Override
            public void onStop() {
                sendAction("stop", null);
            }
        });

        mediaSession.setActive(true);
    }

    @Override
    public boolean execute(String action, JSONArray args, CallbackContext callbackContext) {
        switch (action) {
            case "updateNowPlaying":
                cordova.getActivity().runOnUiThread(() -> updateNowPlaying(args, callbackContext));
                return true;
            case "updatePlaybackState":
                cordova.getActivity().runOnUiThread(() -> updatePlaybackState(args, callbackContext));
                return true;
            case "hide":
                cordova.getActivity().runOnUiThread(() -> hide(callbackContext));
                return true;
            case "registerActionListener":
                this.actionCallbackContext = callbackContext;
                PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
                result.setKeepCallback(true);
                callbackContext.sendPluginResult(result);
                return true;
            default:
                return false;
        }
    }

    private void updateNowPlaying(JSONArray args, CallbackContext callbackContext) {
        try {
            JSONObject info = args.getJSONObject(0);
            final String title = info.optString("title", "");
            final String artist = info.optString("artist", "");
            final String album = info.optString("albumTitle", "");
            final long durationMs = info.optLong("duration", 0) * 1000;
            final boolean isPlaying = info.optBoolean("isPlaying", true);
            this.hasNext = info.optBoolean("hasNext", true);
            this.hasPrevious = info.optBoolean("hasPrevious", true);
            final String artworkUrl = info.optString("artworkUrl", null);

            loadArtwork(artworkUrl, bitmap -> {
                currentArtwork = bitmap;

                pendingTitle = title;
                pendingArtist = artist;
                pendingAlbum = album;
                pendingDurationMs = durationMs;
                pendingIsPlaying = isPlaying;
                pendingPositionMs = 0;

                scheduleSessionUpdate();
                callbackContext.success();
            });
        } catch (JSONException e) {
            callbackContext.error("Invalid arguments: " + e.getMessage());
        }
    }

    private void updatePlaybackState(JSONArray args, CallbackContext callbackContext) {
        try {
            boolean isPlaying = args.getBoolean(0);
            double elapsedSeconds = args.optDouble(1, 0);

            // hasNext/hasPrevious are optional here - only overwrite the stored
            // value when the caller explicitly passed a boolean (not null/absent),
            // so routine play/pause ticks don't accidentally re-enable a button
            // that was deliberately disabled (e.g. "previous" on the first track).
            if (args.length() > 2 && !args.isNull(2)) {
                hasNext = args.getBoolean(2);
            }
            if (args.length() > 3 && !args.isNull(3)) {
                hasPrevious = args.getBoolean(3);
            }

            pendingIsPlaying = isPlaying;
            pendingPositionMs = (long) (elapsedSeconds * 1000);

            scheduleSessionUpdate();
            callbackContext.success();
        } catch (JSONException e) {
            callbackContext.error("Invalid arguments: " + e.getMessage());
        }
    }

    /**
     * Debounces the actual push to the MediaSession (setMetadata + setPlaybackState)
     * and the notification render together, as a single unit. Any call to
     * updateNowPlaying/updatePlaybackState within the debounce window of a previous
     * call cancels that previous flush and reschedules using the latest pending
     * values - so a rapid sequence like "new track metadata, then a moment later
     * the new track's play-state" collapses into exactly one push to the OS,
     * instead of two back-to-back reflows.
     */
    private void scheduleSessionUpdate() {
        if (pendingSessionUpdateRunnable != null) {
            notificationHandler.removeCallbacks(pendingSessionUpdateRunnable);
        }
        pendingSessionUpdateRunnable = this::flushSessionUpdate;
        notificationHandler.postDelayed(pendingSessionUpdateRunnable, NOTIFICATION_DEBOUNCE_MS);
    }

    private void flushSessionUpdate() {
        MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, pendingTitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, pendingArtist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, pendingAlbum)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, pendingDurationMs);
        if (currentArtwork != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentArtwork);
        }
        mediaSession.setMetadata(metadataBuilder.build());

        boolean stateActuallyChanged = lastPushedIsPlaying == null
                || !lastPushedIsPlaying.equals(pendingIsPlaying)
                || !Boolean.valueOf(hasNext).equals(lastPushedHasNext)
                || !Boolean.valueOf(hasPrevious).equals(lastPushedHasPrevious);

        if (stateActuallyChanged) {
            setPlaybackState(pendingIsPlaying, pendingPositionMs, hasNext, hasPrevious);
            lastPushedIsPlaying = pendingIsPlaying;
            lastPushedHasNext = hasNext;
            lastPushedHasPrevious = hasPrevious;
        }
        // When nothing in isPlaying/hasNext/hasPrevious actually changed (the
        // common case for a plain mid-playlist track skip), setPlaybackState()
        // is skipped entirely - only the metadata (title/artist/artwork) updates,
        // which doesn't drive the OS's button-row layout and so doesn't trigger
        // the reflow/"flicker" that a redundant setPlaybackState() call would.

        Notification notification = buildNotification(pendingTitle, pendingArtist, pendingIsPlaying);
        startMediaService(cordova.getActivity(), notification);
    }

    private void setPlaybackState(boolean isPlaying, long positionMs, boolean hasNext, boolean hasPrevious) {
        long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE
                | PlaybackStateCompat.ACTION_SEEK_TO
                | PlaybackStateCompat.ACTION_STOP;
        if (hasNext) actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        if (hasPrevious) actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;

        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                        isPlaying ? PlaybackStateCompat.STATE_PLAYING : PlaybackStateCompat.STATE_PAUSED,
                        positionMs,
                        isPlaying ? 1.0f : 0f)
                .build();
        mediaSession.setPlaybackState(state);
    }

    private Notification buildNotification(String title, String artist, boolean isPlaying) {
        MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
        String notifTitle = title != null ? title
                : (metadata != null ? metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) : "");
        String notifArtist = artist != null ? artist
                : (metadata != null ? metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) : "");

        Context context = cordova.getActivity();
        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(resolveNotificationIcon(context))
                .setContentTitle(notifTitle)
                .setContentText(notifArtist)
                .setLargeIcon(currentArtwork)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentIntent(context))
                .setOngoing(isPlaying);

        // Build actions dynamically so compact-view indices always line up with
        // whichever buttons are actually present. Note: on Android 13+, the
        // system's redesigned media template ignores these custom action icons
        // for the primary play/pause/skip slots entirely and derives what to
        // show directly from the actions bitmask set in setPlaybackState() -
        // these addAction calls mainly matter for pre-13 devices and as a
        // fallback/compact-view action list.
        java.util.List<Integer> compactIndices = new java.util.ArrayList<>();
        int index = 0;

        if (hasPrevious) {
            builder.addAction(android.R.drawable.ic_media_previous, "Previous",
                    mediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS));
            compactIndices.add(index++);
        }

        builder.addAction(playPauseIcon, "Play/Pause",
                mediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
        compactIndices.add(index++);

        if (hasNext) {
            builder.addAction(android.R.drawable.ic_media_next, "Next",
                    mediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_NEXT));
            compactIndices.add(index++);
        }

        int[] compactArray = new int[compactIndices.size()];
        for (int i = 0; i < compactArray.length; i++) {
            compactArray[i] = compactIndices.get(i);
        }

        builder.setStyle(new MediaStyle()
                .setMediaSession(mediaSession.getSessionToken())
                .setShowActionsInCompactView(compactArray));

        return builder.build();
    }

    /**
     * Hands the notification to MediaPlaybackService, which owns the actual
     * startForeground() call (and the wake lock that goes with it). This is
     * called on every debounced update, not just the first - re-calling
     * startForegroundService()/startForeground() with a fresh Notification is
     * the supported way to refresh a running foreground notification's
     * content in place, so there's no need to track "already started"
     * separately from "notification changed".
     */
    private void startMediaService(Context context, Notification notification) {
        Intent intent = new Intent(context, MediaPlaybackService.class);
        intent.setAction(MediaPlaybackService.ACTION_START_OR_UPDATE);
        intent.putExtra(MediaPlaybackService.EXTRA_NOTIFICATION, notification);
        intent.putExtra(MediaPlaybackService.EXTRA_NOTIFICATION_ID, NOTIFICATION_ID);
        ContextCompat.startForegroundService(context, intent);
    }

    private void stopMediaService(Context context) {
        Intent intent = new Intent(context, MediaPlaybackService.class);
        intent.setAction(MediaPlaybackService.ACTION_STOP);
        context.startService(intent);
    }

    /**
     * PendingIntent for tapping the notification body itself (not the action
     * buttons) - brings the existing app instance to the foreground, matching
     * standard media-app behavior (Spotify, YouTube Music, etc.) rather than
     * launching a fresh instance of the activity.
     */
    private PendingIntent contentIntent(Context context) {
        Intent intent = new Intent(context, cordova.getActivity().getClass());
        intent.setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getActivity(context, 0, intent, flags);
    }

    private PendingIntent mediaButtonPendingIntent(int keyCode) {
        Context context = cordova.getActivity();
        Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        intent.setPackage(context.getPackageName());
        intent.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));

        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return PendingIntent.getBroadcast(context, keyCode, intent, flags);
    }

    private void hide(CallbackContext callbackContext) {
        if (pendingSessionUpdateRunnable != null) {
            notificationHandler.removeCallbacks(pendingSessionUpdateRunnable);
            pendingSessionUpdateRunnable = null;
        }
        stopMediaService(cordova.getActivity());
        // Belt-and-braces: stopMediaService already tears down the foreground
        // notification via stopForeground(STOP_FOREGROUND_REMOVE), but this
        // covers the edge case where the service was never actually started
        // (e.g. hide() called without any prior updateNowPlaying/
        // updatePlaybackState call in this session).
        NotificationManagerCompat.from(cordova.getActivity()).cancel(NOTIFICATION_ID);
        mediaSession.setActive(false);
        callbackContext.success();
    }

    private void sendAction(String action, Double value) {
        if (actionCallbackContext == null) return;
        try {
            JSONObject result = new JSONObject();
            result.put("action", action);
            if (value != null) {
                result.put("value", value);
            }
            PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, result);
            pluginResult.setKeepCallback(true);
            actionCallbackContext.sendPluginResult(pluginResult);
        } catch (JSONException ignored) {
        }
    }

    /**
     * Looks up the plugin's own res/drawable/ic_media_notification.xml (added via
     * plugin.xml's resource-file entry). Cordova plugins can't reference generated
     * R classes directly, so this is resolved at runtime via getIdentifier() and
     * cached. Falls back to the app's own launcher icon if, for any reason, the
     * drawable wasn't packaged (e.g. an older build before this was added).
     */
    private int resolveNotificationIcon(Context context) {
        if (notificationIconResId != 0) {
            return notificationIconResId;
        }
        int resId = context.getResources().getIdentifier(
                NOTIFICATION_ICON_RES_NAME, "drawable", context.getPackageName());
        notificationIconResId = (resId != 0) ? resId : context.getApplicationInfo().icon;
        return notificationIconResId;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager)
                    cordova.getActivity().getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Media Playback", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Now playing media controls");
            nm.createNotificationChannel(channel);
        }
    }

    private interface ArtworkCallback {
        void onLoaded(Bitmap bitmap);
    }

    private void loadArtwork(String url, ArtworkCallback callback) {
        if (url == null || url.isEmpty()) {
            callback.onLoaded(null);
            return;
        }
        cordova.getThreadPool().execute(() -> {
            Bitmap bitmap = null;
            try {
                if (url.startsWith("http")) {
                    InputStream in = new URL(url).openStream();
                    bitmap = BitmapFactory.decodeStream(in);
                } else {
                    String path = url.startsWith("file://") ? Uri.parse(url).getPath() : url;
                    bitmap = BitmapFactory.decodeFile(path);
                }
            } catch (Exception ignored) {
                // Fall back to no artwork rather than failing the whole update
            }
            final Bitmap finalBitmap = bitmap;
            cordova.getActivity().runOnUiThread(() -> callback.onLoaded(finalBitmap));
        });
    }
}
