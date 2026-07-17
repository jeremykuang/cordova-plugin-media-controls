package com.outsystems.plugins.mediacontrols;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
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
    private static final int NOTIFICATION_ID = 7001;

    private MediaSessionCompat mediaSession;
    private CallbackContext actionCallbackContext;
    private Bitmap currentArtwork;

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
            final boolean hasNext = info.optBoolean("hasNext", true);
            final boolean hasPrevious = info.optBoolean("hasPrevious", true);
            final String artworkUrl = info.optString("artworkUrl", null);

            loadArtwork(artworkUrl, bitmap -> {
                currentArtwork = bitmap;

                MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder()
                        .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                        .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                        .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs);
                if (bitmap != null) {
                    metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, bitmap);
                }
                mediaSession.setMetadata(metadataBuilder.build());

                setPlaybackState(isPlaying, 0, hasNext, hasPrevious);
                showNotification(title, artist, isPlaying);

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
            setPlaybackState(isPlaying, (long) (elapsedSeconds * 1000), true, true);
            showNotification(null, null, isPlaying);
            callbackContext.success();
        } catch (JSONException e) {
            callbackContext.error("Invalid arguments: " + e.getMessage());
        }
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

    private void showNotification(String title, String artist, boolean isPlaying) {
        MediaMetadataCompat metadata = mediaSession.getController().getMetadata();
        String notifTitle = title != null ? title
                : (metadata != null ? metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) : "");
        String notifArtist = artist != null ? artist
                : (metadata != null ? metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) : "");

        Context context = cordova.getActivity();
        int playPauseIcon = isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(context.getApplicationInfo().icon)
                .setContentTitle(notifTitle)
                .setContentText(notifArtist)
                .setLargeIcon(currentArtwork)
                .setOnlyAlertOnce(true)
                .setOngoing(isPlaying)
                .addAction(android.R.drawable.ic_media_previous, "Previous",
                        mediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
                .addAction(playPauseIcon, "Play/Pause",
                        mediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                .addAction(android.R.drawable.ic_media_next, "Next",
                        mediaButtonPendingIntent(KeyEvent.KEYCODE_MEDIA_NEXT))
                .setStyle(new MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2));

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build());
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
