# cordova-plugin-media-controls (Android) + Web MediaSession (iOS)

Lock-screen / notification "Now Playing" media controls (play/pause/next/previous/seek)
for OutSystems 11 Mobile apps playing audio through an HTML5 `<audio>` element.

**Why two mechanisms:** Android's system WebView does not implement the standard
[Media Session Web API](https://developer.mozilla.org/en-US/docs/Web/API/Media_Session_API)
(a long-standing Chromium gap), while iOS's WKWebView does. So:

- **Android** → this Cordova plugin (`MediaSessionCompat` + a `MediaStyle` notification)
- **iOS** → `navigator.mediaSession`, called directly from JavaScript — no native plugin needed

Both are wired into OutSystems the same way: a Client Action with a JavaScript node that
branches on `cordova.platformId`.

## 1. Add the plugin to your OutSystems app (Android only)

1. Zip the `cordova-plugin-media-controls` folder.
2. In Service Studio: **Logic > Extensibility Configurations** → add/edit a configuration
   pointing at this zip (or an npm/git reference).
3. Publish and rebuild natively (Android build only needs this; iOS doesn't need the
   plugin at all, but it's harmless to include — the `platforms` list in `plugin.xml`
   only contains `android`, so Cordova will simply skip it on iOS builds).

## 2. Client Action: RegisterMediaControlsListener

Call this **once**, in your Layout's `OnReady` (not per-screen) so it survives navigation
between screens.

```javascript
var audio = document.getElementById('myAudioPlayer'); // your <audio> element's id

if (cordova.platformId === 'ios' && 'mediaSession' in navigator) {
    if (!window.mediaControlsListenerRegistered) {
        window.mediaControlsListenerRegistered = true;

        // Stored on window so UpdateNowPlaying/UpdatePlaybackState can re-attach
        // or detach the SAME handler (needed to enable/disable next/previous
        // without redefining the handler body in multiple places).
        window.mcHandlers = {
            play: function () {
                audio.play();
                document.dispatchEvent(new CustomEvent('MediaControlsAction', { detail: { action: 'play' } }));
            },
            pause: function () {
                audio.pause();
                document.dispatchEvent(new CustomEvent('MediaControlsAction', { detail: { action: 'pause' } }));
            },
            next: function () {
                document.dispatchEvent(new CustomEvent('MediaControlsAction', { detail: { action: 'next' } }));
            },
            previous: function () {
                document.dispatchEvent(new CustomEvent('MediaControlsAction', { detail: { action: 'previous' } }));
            },
            seek: function (details) {
                audio.currentTime = details.seekTime;
                document.dispatchEvent(new CustomEvent('MediaControlsAction', { detail: { action: 'seek', value: details.seekTime } }));
            }
        };

        navigator.mediaSession.setActionHandler('play', window.mcHandlers.play);
        navigator.mediaSession.setActionHandler('pause', window.mcHandlers.pause);
        navigator.mediaSession.setActionHandler('seekto', window.mcHandlers.seek);
        // next/previous start enabled by default; UpdateNowPlaying / UpdatePlaybackState
        // can pass null instead to disable them (see below).
        navigator.mediaSession.setActionHandler('nexttrack', window.mcHandlers.next);
        navigator.mediaSession.setActionHandler('previoustrack', window.mcHandlers.previous);
    }
} else if (cordova.platformId === 'android') {
    if (typeof cordova !== 'undefined' && cordova.plugins && cordova.plugins.MediaControls) {
        if (!window.mediaControlsListenerRegistered) {
            window.mediaControlsListenerRegistered = true;

            cordova.plugins.MediaControls.onAction(function (action, value) {
                document.dispatchEvent(new CustomEvent('MediaControlsAction', {
                    detail: { action: action, value: value }
                }));
            });
        }
    } else {
        document.addEventListener('deviceready', function () {
            if (!window.mediaControlsListenerRegistered && cordova.plugins && cordova.plugins.MediaControls) {
                window.mediaControlsListenerRegistered = true;
                cordova.plugins.MediaControls.onAction(function (action, value) {
                    document.dispatchEvent(new CustomEvent('MediaControlsAction', {
                        detail: { action: action, value: value }
                    }));
                });
            }
        });
    }
}
```

## 3. Client Action: UpdateNowPlaying

Input parameters: `Title` (Text), `Artist` (Text), `AlbumTitle` (Text), `ArtworkUrl` (Text,
must be an absolute `https://` URL), `Duration` (Integer, seconds), `IsPlaying` (Boolean),
`HasNext` (Boolean), `HasPrevious` (Boolean).

```javascript
if (cordova.platformId === 'ios' && 'mediaSession' in navigator) {
    navigator.mediaSession.metadata = new MediaMetadata({
        title: $parameters.Title,
        artist: $parameters.Artist,
        album: $parameters.AlbumTitle,
        artwork: [
            { src: $parameters.ArtworkUrl, sizes: '96x96',   type: 'image/jpeg' },
            { src: $parameters.ArtworkUrl, sizes: '256x256', type: 'image/jpeg' },
            { src: $parameters.ArtworkUrl, sizes: '512x512', type: 'image/jpeg' }
        ]
    });
    navigator.mediaSession.playbackState = $parameters.IsPlaying ? 'playing' : 'paused';

    // Re-attach the SAME handler (from window.mcHandlers) to enable, or pass null to disable.
    navigator.mediaSession.setActionHandler('nexttrack', $parameters.HasNext ? window.mcHandlers.next : null);
    navigator.mediaSession.setActionHandler('previoustrack', $parameters.HasPrevious ? window.mcHandlers.previous : null);
} else if (cordova.platformId === 'android') {
    cordova.plugins.MediaControls.updateNowPlaying({
        title: $parameters.Title,
        artist: $parameters.Artist,
        albumTitle: $parameters.AlbumTitle,
        artworkUrl: $parameters.ArtworkUrl,
        duration: $parameters.Duration,
        isPlaying: $parameters.IsPlaying,
        hasNext: $parameters.HasNext,
        hasPrevious: $parameters.HasPrevious
    });
}
```

## 4. Client Action: UpdatePlaybackState

Input parameters: `IsPlaying` (Boolean), `ElapsedSeconds` (Decimal), `HasNext` (Boolean),
`HasPrevious` (Boolean). Use this whenever play/pause state changes AND whenever the
playlist position changes (e.g. you've reached the first or last track), without
resending title/artist/artwork.

```javascript
var audio = document.getElementById('myAudioPlayer');

if (cordova.platformId === 'ios' && 'mediaSession' in navigator) {
    navigator.mediaSession.playbackState = $parameters.IsPlaying ? 'playing' : 'paused';

    navigator.mediaSession.setActionHandler('nexttrack', $parameters.HasNext ? window.mcHandlers.next : null);
    navigator.mediaSession.setActionHandler('previoustrack', $parameters.HasPrevious ? window.mcHandlers.previous : null);

    // Optional but recommended: keeps the lock-screen scrubber accurate
    if (audio.duration && isFinite(audio.duration)) {
        navigator.mediaSession.setPositionState({
            duration: audio.duration,
            playbackRate: audio.playbackRate,
            position: $parameters.ElapsedSeconds
        });
    }
} else if (cordova.platformId === 'android') {
    // Pass HasNext/HasPrevious as actual booleans to change them, or leave the
    // OutSystems parameters unbound/null to keep whatever was last set.
    cordova.plugins.MediaControls.updatePlaybackState(
        $parameters.IsPlaying,
        $parameters.ElapsedSeconds,
        $parameters.HasNext,
        $parameters.HasPrevious
    );
}
```

**Note on the Android call:** `hasNext`/`hasPrevious` are optional third/fourth
arguments — pass `null` (or leave an OutSystems Boolean parameter unbound, which
serializes as `null`) if you only want to update play/pause + elapsed time without
touching button visibility. The native plugin remembers the last value you set and
only overwrites it when you explicitly pass `true`/`false`.


## 5. Reacting to control taps

Same on both platforms — listen for the `MediaControlsAction` custom event on whichever
screen handles playback (guard against duplicate registration if the screen can be
re-entered):

```javascript
if (!window.mediaControlsHandler) {
    window.mediaControlsHandler = function (e) {
        $actions.HandleMediaControlAction(e.detail.action, e.detail.value);
    };
    document.addEventListener('MediaControlsAction', window.mediaControlsHandler);
}
```

`HandleMediaControlAction` takes `Action` (Text) and `Value` (Decimal, only populated for
`seek`), and branches on `Action` to control your `<audio>` element.

## Notes

- **The card only appears once real audio is playing** on both platforms — this is
  OS-level behavior, not something either mechanism can override. Calling
  `UpdateNowPlaying` before playback starts sets the metadata ahead of time, but the
  lock-screen card itself won't show until `audio.play()` actually produces sound.
- **Artwork on iOS**: provide at least the three sizes shown above with explicit `sizes`
  and `type` — a single untyped/unsized entry often silently fails to display.
- **Artwork on Android**: must be an absolute `https://` URL or a local file path; check
  Logcat for the plugin's `[MediaControls]`-prefixed log lines if it's not showing up,
  for exactly why the fetch failed.
- **Testing**: a native build is required for Android — this can't be verified in a web
  preview. iOS's `navigator.mediaSession` can actually be exercised in Safari's remote
  Web Inspector against the running WKWebView, which is a faster feedback loop.
