var exec = require('cordova/exec');

var PLUGIN_NAME = 'MediaControls';

var MediaControls = {

    /**
     * Show / refresh the lock-screen and notification Now Playing controls.
     *
     * @param {Object} info
     *   info.title        {String}  track title
     *   info.artist       {String}  artist name
     *   info.albumTitle   {String}  optional album name
     *   info.artworkUrl   {String}  optional http(s) URL or local file:// URL for cover art
     *   info.duration     {Number}  optional, track length in seconds
     *   info.isPlaying    {Boolean} current playback state
     *   info.hasNext      {Boolean} optional, enables/disables the "next" control (default true)
     *   info.hasPrevious  {Boolean} optional, enables/disables the "previous" control (default true)
     * @param {Function} successCallback
     * @param {Function} errorCallback
     */
    updateNowPlaying: function (info, successCallback, errorCallback) {
        exec(successCallback, errorCallback, PLUGIN_NAME, 'updateNowPlaying', [info || {}]);
    },

    /**
     * Cheaper update used for progress-bar ticks: only touches play/pause state
     * and elapsed time, without resending title/artist/artwork.
     *
     * @param {Boolean} isPlaying
     * @param {Number} elapsedSeconds
     */
    updatePlaybackState: function (isPlaying, elapsedSeconds, successCallback, errorCallback) {
        exec(successCallback, errorCallback, PLUGIN_NAME, 'updatePlaybackState', [!!isPlaying, elapsedSeconds || 0]);
    },

    /**
     * Remove the Now Playing controls (call when playback fully stops / app data changes).
     */
    hide: function (successCallback, errorCallback) {
        exec(successCallback, errorCallback, PLUGIN_NAME, 'hide', []);
    },

    /**
     * Register a listener that fires whenever the user taps a lock-screen /
     * notification control. Call this once, e.g. on app start.
     *
     * @param {Function} callback  callback(action, value)
     *   action is one of: 'play' | 'pause' | 'next' | 'previous' | 'seek' | 'stop'
     *   value  is only populated for 'seek', in seconds
     */
    onAction: function (callback) {
        exec(function (result) {
            if (callback) {
                callback(result.action, result.value);
            }
        }, function () { /* swallow - listener errors shouldn't break future events */ },
        PLUGIN_NAME, 'registerActionListener', []);
    }
};

module.exports = MediaControls;
