# cordova-plugin-media-controls

Lock-screen and notification "Now Playing" media controls (play/pause/next/previous/seek)
for OutSystems 11 Mobile apps, built as a standard Cordova plugin.

- **Android**: `MediaSessionCompat` + a `MediaStyle` notification (shows on the lock screen,
  notification shade, and Android Auto/Wear if applicable).
- **iOS**: `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter` (shows on the Lock Screen,
  Control Center, and CarPlay).

## 1. Add the plugin to your OutSystems app

In Service Studio:

1. Open your Mobile app's module.
2. Go to **Logic > Extensibility Configurations** and add a new configuration
   (or edit an existing one).
3. Point it at this plugin folder (zip the `cordova-plugin-media-controls` folder,
   or publish it to a private npm registry / git repo and reference it by URL/id —
   OutSystems 11 supports both a local zip upload and an npm-style plugin id).
4. Publish the app and rebuild it in OutSystems Cloud/Now (native builds are required —
   this can't be tested in the web preview since it touches native code).

## 2. Call it from OutSystems Client Actions

OutSystems doesn't call Cordova plugin JS directly from OutSystems logic — you wrap it in a
**Client Action** with a **JavaScript** node. Example client action `UpdateNowPlaying`:

Input parameters: `Title` (Text), `Artist` (Text), `AlbumTitle` (Text), `ArtworkUrl` (Text),
`Duration` (Integer), `IsPlaying` (Boolean)

JavaScript node body:
```javascript
cordova.plugins.MediaControls.updateNowPlaying({
    title: $parameters.Title,
    artist: $parameters.Artist,
    albumTitle: $parameters.AlbumTitle,
    artworkUrl: $parameters.ArtworkUrl,
    duration: $parameters.Duration,
    isPlaying: $parameters.IsPlaying
});
```

Do the same for `HideNowPlaying` (`cordova.plugins.MediaControls.hide()`) and
`UpdatePlaybackState` (`cordova.plugins.MediaControls.updatePlaybackState(isPlaying, elapsed)`).

## 3. Listen for lock-screen button taps

Register the listener once, e.g. in a client action called from the app's `OnReady` handler:

```javascript
cordova.plugins.MediaControls.onAction(function (action, value) {
    // Forward into OutSystems: set a client variable + fire a client action,
    // or dispatch a custom event that a screen-level JS node listens for.
    var evt = new CustomEvent('MediaControlsAction', { detail: { action: action, value: value } });
    document.dispatchEvent(evt);
});
```

Then, on the screen(s) that need to react (e.g. your player screen), add a JavaScript node
in `OnReady` that listens for `MediaControlsAction` and calls an OutSystems client action
(e.g. `HandlePlayerAction`) with the action name, which you can then branch on
(Play / Pause / Next / Previous / Seek / Stop) in OutSystems logic to control your actual
`<audio>`/`<video>` element or player SDK.

## Notes / things to adjust for your app

- **Android**: the notification icon defaults to your app's launcher icon. If you want a
  dedicated monochrome icon, replace `context.getApplicationInfo().icon` in
  `MediaControlsPlugin.java` with a drawable resource id.
- **Artwork**: both platforms accept an `http(s)://` URL or a local `file://` path (e.g. a
  cached cover art file). Remote images are fetched synchronously on a background thread —
  for large images you may want to pre-resize before passing them in.
- **Foreground service (Android)**: this implementation posts a normal notification tied to
  the media session, which is the same approach used by most Cordova media-control plugins.
  If your app needs to guarantee playback survives aggressive OS memory pressure, you may
  want to additionally wrap your audio playback in a foreground `Service` — that's outside
  the scope of this plugin since it depends on how you're actually playing audio.
- **Testing**: you must build to a real device or a native emulator/simulator — Now Playing
  controls don't render in `chrome://inspect` browser previews.
