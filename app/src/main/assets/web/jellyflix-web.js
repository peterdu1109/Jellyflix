/*
 * Jellyflix additions to the server's own web interface. Runs at document start, after the app has defined
 *   window.__JELLYFLIX__ = { tv, accent: "#rrggbb", nativePlayer, deviceProfile, deviceId, deviceName, appVersion }
 * Kept dependency-free and conservative: every part is optional and fails silently, so a server or web client
 * version that differs from the ones this was written against simply gets the stock interface.
 *
 *  1. Focus ring  - the TV layout only scales the focused card by ~7%, which is nearly invisible on real artwork.
 *  2. Seek preview - the web client shows a preview while scrubbing by using a whole sprite sheet of thumbnails
 *                    ("trickplay tile") as a CSS background. The first time a sheet is hovered it must be downloaded
 *                    AND decoded, so the preview lags. We fetch the sheets as soon as playback starts.
 */
(function () {
  'use strict';
  if (window.__jellyflixInstalled) return;
  window.__jellyflixInstalled = true;

  var cfg = window.__JELLYFLIX__ || {};
  var debug = (window.__jellyflixDebug = { tilesRequested: 0, tilesLoaded: 0, item: null, width: null, tiles: 0 });

  // ---------------------------------------------------------------- 1: focus ring
  var accent = /^#[0-9a-f]{6}$/i.test(cfg.accent || '') ? cfg.accent : '#00a4dc';
  var css = '';
  if (cfg.tv) {
    css +=
      // Posters/thumbnails: ring on the artwork, dark gap so it reads on any image, brighter title.
      'html.layout-tv .card:focus .cardScalable,html.layout-tv .card:focus-visible .cardScalable{' +
      'outline:5px solid ' + accent + '!important;outline-offset:2px!important;box-shadow:0 0 0 3px rgba(0,0,0,.7)!important}' +
      'html.layout-tv .card:focus .cardText,html.layout-tv .card:focus-visible .cardText{color:#fff!important}' +
      // Everything else that can take focus (buttons, tabs, list rows, inputs).
      'html.layout-tv button:focus-visible:not(.card),html.layout-tv a:focus-visible,html.layout-tv input:focus-visible,' +
      'html.layout-tv select:focus-visible,html.layout-tv .listItem:focus-visible,' +
      'html.layout-tv [tabindex]:focus-visible:not(.card):not(.scrollSlider):not(.itemsContainer){' +
      'outline:4px solid ' + accent + '!important;outline-offset:2px!important}';
  }
  // A document-start script runs before the page has ANY element (no <html>, no <head>), so the style is added
  // the moment the parser creates one.
  function injectStyle(text) {
    var style = document.createElement('style');
    style.id = 'jellyflix-tuning';
    style.textContent = text;
    function put() {
      var parent = document.head || document.documentElement;
      if (!parent) return false;
      parent.appendChild(style);
      return true;
    }
    if (put()) return;
    var observer = new MutationObserver(function () { if (put()) observer.disconnect(); });
    observer.observe(document, { childList: true, subtree: true });
  }
  if (css) injectStyle(css);

  // ---------------------------------------------------------------- 2: seek preview prefetch
  var active = null; // { id, msid, info, cols, images: {index: Image}, cancelled }

  function pickWidth(widths, target) {
    // Same rule as the web client (playback-video): the largest width <= target, else the smallest available.
    var m;
    for (var i = 0; i < widths.length; i++) {
      var w = widths[i];
      if (!m || (w < m && m > target) || (w > m && w <= target)) m = w;
    }
    return m;
  }

  function parseSource(src) {
    var m = /\/videos\/([0-9a-f]{32})\//i.exec(src || '');
    if (!m) return null;
    var q = /[?&]MediaSourceId=([^&]+)/i.exec(src);
    return { id: m[1], msid: q ? decodeURIComponent(q[1]) : m[1] };
  }

  function tileUrl(ac, s, info, index) {
    // Must be byte-identical to the URL the web client builds (same parameter order), or the cache can't be shared.
    return ac.getUrl('Videos/' + s.id + '/Trickplay/' + info.Width + '/' + index + '.jpg', { ApiKey: ac.accessToken(), MediaSourceId: s.msid });
  }

  function start(video) {
    var ac = window.ApiClient;
    var s = parseSource(video.currentSrc || video.src);
    if (!ac || !ac.getJSON || !s) return;
    if (active && active.id === s.id && active.msid === s.msid) return;
    if (active) active.cancelled = true;
    var state = (active = { id: s.id, msid: s.msid, info: null, images: {}, cancelled: false });

    ac.getJSON(ac.getUrl('Users/' + ac.getCurrentUserId() + '/Items', { Ids: s.id, Fields: 'Trickplay' })).then(function (r) {
      if (state.cancelled) return;
      var item = r && r.Items && r.Items[0];
      var bySource = item && item.Trickplay && item.Trickplay[s.msid];
      if (!bySource) return; // the server has no trickplay images for this video: nothing to speed up
      var widths = Object.keys(bySource).map(Number);
      var target = window.screen.width * (window.devicePixelRatio || 1) * 0.2;
      var width = pickWidth(widths, target);
      var info = bySource[width];
      if (!info || !info.Interval || !info.TileWidth || !info.TileHeight) return;
      state.info = info;
      var perTile = info.TileWidth * info.TileHeight;
      var count = Math.ceil((info.ThumbnailCount || 0) / perTile);
      debug.item = s.id; debug.width = width; debug.tiles = count;
      if (count > 0) prefetch(ac, state, info, count, video);
    }, function () {});
  }

  function currentTile(video, info) {
    var ms = (video.currentTime || 0) * 1000;
    return Math.floor(Math.floor(ms / info.Interval) / (info.TileWidth * info.TileHeight));
  }

  function prefetch(ac, state, info, count, video) {
    // Nearest to where the viewer is first (skipping around is most likely close by), then outwards.
    var cur = Math.min(Math.max(currentTile(video, info), 0), count - 1);
    var order = [cur];
    for (var d = 1; order.length < count; d++) {
      if (cur + d < count) order.push(cur + d);
      if (cur - d >= 0) order.push(cur - d);
    }
    var next = 0;
    function one() {
      if (state.cancelled || next >= order.length) return;
      var index = order[next++];
      var img = new Image();
      img.decoding = 'async';
      try { img.fetchPriority = 'low'; } catch (e) {} // keep the video stream ahead of this
      debug.tilesRequested++;
      var done = function () { debug.tilesLoaded++; one(); };
      img.onload = done; img.onerror = one;
      img.src = tileUrl(ac, state, info, index);
      // Holding the Image keeps the downloaded sheet in the page's memory cache, which the web client's CSS
      // background reuses by URL. Only the sheet around the playhead is decoded ahead of time (a decoded
      // 3200x1800 sheet is ~23 MB, too much to keep for every sheet on a TV).
      state.images[index] = img;
      if (index === order[0] && img.decode) img.decode().catch(function () {});
    }
    one(); // one at a time: the bytes compete with the video itself
  }

  function onMeta(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    // Wait a moment so the stream itself gets the bandwidth first, and skip when the user asked to save data.
    var conn = navigator.connection;
    if (conn && conn.saveData) return;
    setTimeout(function () { try { start(v); } catch (err) {} }, 3000);
  }
  // Media events don't bubble, but they can be captured on the document.
  document.addEventListener('loadedmetadata', onMeta, true);

  // ---------------------------------------------------------------- 3: hardware video player
  //
  // The web client decides HOW to play (direct play or a server transcode) from the device profile its player plugin
  // reports, then hands the resulting stream URL to that plugin. We register a plugin whose profile comes from the
  // device's real decoders (MediaCodecList, built natively) and whose playback is done by the app's ExoPlayer.
  //   - the device can decode it  -> direct play, decoded locally
  //   - it can't                  -> the server transcodes, the app plays the HLS stream
  //   - local playback fails      -> we raise an error; the web client itself retries with a forced server transcode
  // The web client keeps doing all server reporting (start / progress / stop), queueing and "next episode", so it
  // behaves exactly as with its own player. The bridge to the app is JellyflixNative.postMessage(json); the app
  // answers through window.__jellyflixNative.onEvent(event).
  var bridge = function () { var b = window.JellyflixNative; return b && typeof b.postMessage === 'function' ? b : null; };
  if (cfg.nativePlayer) {
    var instance = null;

    var send = function (cmd, data) {
      try {
        var msg = { cmd: cmd };
        for (var k in data) if (Object.prototype.hasOwnProperty.call(data, k)) msg[k] = data[k];
        var b = bridge();
        if (b) b.postMessage(JSON.stringify(msg));
      } catch (e) {}
    };

    // Same feature list the web client computes itself for a browser (apphost.js), because once NativeShell exists
    // the client asks US instead. Only `exit` is added.
    var ua = (navigator.userAgent || '').toLowerCase();
    var features = ['plugins', 'exit', 'externallinks', 'externalpremium', 'externallinkdisplay', 'htmlaudioautoplay', 'htmlvideoautoplay',
      'displaylanguage', 'otherapppromotions', 'displaymode', 'targetblank', 'screensaver', 'subtitleburnsettings'];
    if (navigator.share) features.push('sharing');
    if (!cfg.tv) { features.push('filedownload', 'fileinput', 'remotecontrol', 'fullscreenchange'); } else { features.push('physicalvolumecontrol'); }
    if (ua.indexOf('mobile') !== -1) features.push('physicalvolumecontrol');
    features.push('remotevideo', 'subtitleappearancesettings');

    window.NativeShell = {
      getPlugins: function () { return ['JellyflixPlayer']; },
      AppHost: {
        init: function () { return Promise.resolve({ deviceId: cfg.deviceId, deviceName: cfg.deviceName }); },
        deviceId: function () { return cfg.deviceId; },
        deviceName: function () { return cfg.deviceName; },
        appName: function () { return 'Jellyflix'; },
        appVersion: function () { return cfg.appVersion || '0'; },
        supports: function (command) { return features.indexOf(String(command).toLowerCase()) !== -1; },
        // "modern" is what the web client answers on its own outside a native shell (it is the app layout, not the screen layout).
        getDefaultLayout: function () { return cfg.tv ? 'tv' : 'modern'; },
        getDeviceProfile: function (profileBuilder) { return profileBuilder({}); },
        exit: function () { send('quit'); }
      },
      enableFullscreen: function () {},
      disableFullscreen: function () {}
    };

    var copyProfile = function (profile, noDirectPlay) {
      var p = JSON.parse(JSON.stringify(profile));
      if (noDirectPlay) p.DirectPlayProfiles = [];
      return p;
    };

    window.JellyflixPlayer = function () {
      return Promise.resolve(class JellyflixPlayer {
        constructor(deps) {
          this.events = deps.events;
          this.appHost = deps.appHost;
          this.playbackManager = deps.playbackManager;
          this.name = 'Jellyflix Player';
          this.type = 'mediaplayer';
          this.id = 'jellyflixplayer';
          this.priority = 0;            // before the browser's own player (priority 1)
          this.isLocalPlayer = true;
          this.isExternalPlayer = true; // e.g. no screen-orientation lock: the app owns the screen
          this.useFullSubtitleUrls = true;
          this._stream = null;
          this._pos = 0;
          this._dur = 0;
          this._paused = false;
          this._volume = 100;
          this._muted = false;
          this._startResolve = null;
          instance = this;
        }
        canPlayMediaType(mediaType) { return String(mediaType || '').toLowerCase() === 'video'; }
        // Live TV stays on the web player: opening a tuner stream twice would hold two tuners.
        canPlayItem(item) {
          // No bridge (older WebView, or the app's player switched off): the web client keeps using its own player.
          return !!bridge() && !!item && item.MediaType === 'Video' && item.Type !== 'TvChannel' && item.Type !== 'Program' && !item.IsLive;
        }
        supportsPlayMethod() { return true; }
        supports() { return false; }
        getDeviceProfile(item, options) {
          if (!cfg.deviceProfile) return this.appHost.getDeviceProfile(item, options);
          return Promise.resolve(copyProfile(cfg.deviceProfile, !!(options && options.isRetry)));
        }
        currentSrc() { return this._stream && this._stream.url; }
        play(options) {
          var self = this;
          var ms = options.mediaSource || {};
          this._stream = options;
          this._pos = (options.playerStartPositionTicks || 0) / 10000;
          this._dur = (ms.RunTimeTicks || (options.item && options.item.RunTimeTicks) || 0) / 10000;
          this._paused = false;
          send('play', {
            itemId: options.item && options.item.Id, url: options.url, playMethod: options.playMethod,
            startTicks: options.playerStartPositionTicks || 0, playSessionId: options.playSessionId || null,
            liveStreamId: options.liveStreamId || null, mediaSource: ms,
            audioIndex: ms.DefaultAudioStreamIndex == null ? null : ms.DefaultAudioStreamIndex,
            subtitleIndex: ms.DefaultSubtitleStreamIndex == null ? -1 : ms.DefaultSubtitleStreamIndex
          });
          // Resolved when the app really started (or after a safety delay so the UI can never hang on a silent bridge).
          return new Promise(function (resolve) {
            self._startResolve = resolve;
            setTimeout(function () { if (self._startResolve === resolve) { self._startResolve = null; resolve(); } }, 8000);
          });
        }
        stop() {
          var had = !!this._stream;
          send('stop');
          this._stream = null;
          if (had) this.events.trigger(this, 'stopped', [{ src: null }]);
          return Promise.resolve();
        }
        destroy() {}
        pause() { send('pause'); this._paused = true; this.events.trigger(this, 'pause'); }
        unpause() { send('unpause'); this._paused = false; this.events.trigger(this, 'unpause'); }
        paused() { return this._paused; }
        isPlaying() { return !!this._stream && !this._paused; }
        currentTime(val) {
          if (val != null) { send('seek', { ms: val }); this._pos = val; return undefined; }
          return this._pos;
        }
        duration() { return this._dur || null; }
        seekable() { return true; }
        getBufferedRanges() { return []; }
        getPlaybackRate() { return 1; }
        setPlaybackRate() {}
        // Volume is the system's (TV remote / phone buttons): report a fixed, unmuted level.
        getVolume() { return this._volume; }
        setVolume(v) { this._volume = v; }
        volume(v) { if (v == null) return this._volume; this._volume = v; return undefined; }
        isMuted() { return this._muted; }
        setMute(m) { this._muted = !!m; }
        canSetAudioStreamIndex() { return !!this._stream && this._stream.playMethod !== 'Transcode'; }
        setAudioStreamIndex(index) { send('audio', { index: index }); }
        setSubtitleStreamIndex(index) { send('subtitle', { index: index == null ? -1 : index }); }
        setSecondarySubtitleStreamIndex() {}
        getSubtitleOffset() { return 0; }
        // Events coming from the app.
        onNativeEvent(e) {
          var pm = this.playbackManager;
          switch (e.type) {
            case 'started':
              if (this._startResolve) { var r = this._startResolve; this._startResolve = null; r(); }
              break;
            case 'time':
              this._pos = e.ms;
              if (e.dur) this._dur = e.dur;
              if (!this._stream) break;
              if (this._paused !== !!e.paused) { this._paused = !!e.paused; this.events.trigger(this, this._paused ? 'pause' : 'unpause'); }
              else this.events.trigger(this, 'timeupdate');
              break;
            case 'ended':     // the video finished: the web client plays the next item itself if the user allows it
              if (this._stream) { this._stream = null; this.events.trigger(this, 'stopped', [{ src: null }]); }
              break;
            case 'userstop':  // the viewer left the app's player: a real "stop", so nothing must auto-play afterwards
              if (this._stream) { this._pos = e.ms || this._pos; pm.stop(this); }
              break;
            case 'error': {
              var streamInfo = this._stream;
              // The web client answers by asking the server for a transcoded stream and calling play() again.
              this.events.trigger(this, 'error', [{ type: e.errorType || 'mediadecodeerror', streamInfo: streamInfo }]);
              break;
            }
            case 'audio': pm.setAudioStreamIndex(e.index, this); break;
            case 'subtitle': pm.setSubtitleStreamIndex(e.index, this); break;
            case 'bitrate': pm.setMaxStreamingBitrate({ enableAutomaticBitrateDetection: !e.bitrate, maxBitrate: e.bitrate || undefined }, this); break;
          }
        }
      });
    };

    window.__jellyflixNative = { onEvent: function (e) { try { if (instance) instance.onNativeEvent(e); } catch (err) { console.error('[jellyflix]', err); } } };
    debug.nativePlayer = true;
  }
})();
