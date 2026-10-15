/*
 * Connects the Study Colors page to Android. Only does anything inside the Android app,
 * where the app adds window.AndroidBridge. The page itself (index.html) stays the same as
 * the desktop version, apart from the one line that loads this file.
 */
(function () {
  "use strict";
  var B = window.AndroidBridge;
  if (!B) return;

  /* ---- Exam reminders: the page uses the standard Notification API ---- */
  function AndroidNotification(title, options) {
    B.showNotification(String(title), options && options.body ? String(options.body) : "");
  }
  Object.defineProperty(AndroidNotification, "permission", {
    get: function () { return B.notifPermission(); }
  });
  AndroidNotification.requestPermission = function () {
    return new Promise(function (resolve) {
      window.__studyNotifResult = function (state) { resolve(state); };
      B.requestNotifPermission();
    });
  };
  window.Notification = AndroidNotification;

  /* ---- Export backup: the page makes a file link; hand the file to Android instead ---- */
  var blobs = {};
  var createObjectURL = URL.createObjectURL;
  URL.createObjectURL = function (blob) {
    var url = createObjectURL.call(URL, blob);
    blobs[url] = blob;
    return url;
  };
  var anchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var blob = blobs[this.href];
    if (this.download && blob) {
      var name = this.download;
      var reader = new FileReader();
      reader.onload = function () { B.saveFile(name, String(reader.result)); };
      reader.readAsText(blob);
      return;
    }
    return anchorClick.apply(this, arguments);
  };

  /* ---- Phone tweaks ---- */
  var style = document.createElement("style");
  style.textContent = "#helpBtn{display:none!important}*{-webkit-tap-highlight-color:transparent}";
  document.head.appendChild(style);

  function fixHint() {
    var hint = document.getElementById("hint");
    if (!hint) return;
    var text = hint.textContent;
    var old = " Drag tiles to reorder.";
    if (text.indexOf(old) !== -1) {
      hint.textContent = text.replace(old, " To reorder, open a subject.");
    }
  }

  /* ---- Status bar and navigation bar take the page color, in light and dark ---- */
  function sendBars() {
    if (!document.body) return;
    var m = /rgba?\((\d+),\s*(\d+),\s*(\d+)/.exec(getComputedStyle(document.body).backgroundColor);
    if (m) B.setBars(Math.round(+m[1]), Math.round(+m[2]), Math.round(+m[3]));
  }

  function start() {
    var hint = document.getElementById("hint");
    if (hint) {
      new MutationObserver(fixHint).observe(hint, { childList: true, characterData: true, subtree: true });
      fixHint();
    }
    new MutationObserver(sendBars).observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme"]
    });
    if (window.matchMedia) {
      var dark = window.matchMedia("(prefers-color-scheme: dark)");
      if (dark.addEventListener) dark.addEventListener("change", sendBars);
    }
    sendBars();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
