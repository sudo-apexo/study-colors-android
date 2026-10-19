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


  /* ---- One-tap updates (Settings > About) ---- */
  var upBtn, upMsg, upState = "idle";
  function setUp(state, text, label, disabled) {
    upState = state;
    if (upMsg) upMsg.textContent = text;
    if (upBtn) { upBtn.textContent = label; upBtn.disabled = !!disabled; }
  }
  window.__studyUpdate = function (u) {
    var v = "Version " + (B.appVersionName() || "") + " (build " + B.appBuild() + "). ";
    if (u.state === "checking") setUp("checking", v + "Checking for updates\u2026", "Checking\u2026", true);
    else if (u.state === "current") setUp("current", v + "You have the newest version.", "Check for updates", false);
    else if (u.state === "available") setUp("available", v + "A new version is ready.", "Update now", false);
    else if (u.state === "downloading") setUp("downloading", v + "Downloading\u2026 " + (u.percent || 0) + "%", "Downloading\u2026", true);
    else if (u.state === "ready") setUp("available", v + "Downloaded. Tap Update now, then Install.", "Update now", false);
    else if (u.state === "permission") setUp("available", v + "Allow installs from Study Colors on the screen that opened, then come back and tap Update now.", "Update now", false);
    else if (u.state === "installing") setUp("available", v + "Tap Install on the Android screen.", "Update now", false);
    else setUp("idle", v + "Could not reach GitHub. Check your connection.", "Check for updates", false);
  };
  function addUpdateRow() {
    var about = document.getElementById("aboutLine");
    if (!about || document.getElementById("updBtn")) return;
    upMsg = document.createElement("p");
    upMsg.className = "acct-msg";
    about.parentNode.insertBefore(upMsg, about);
    var wrap = document.createElement("div");
    wrap.className = "set-btns";
    upBtn = document.createElement("button");
    upBtn.type = "button";
    upBtn.id = "updBtn";
    upBtn.className = "btn filled state";
    wrap.appendChild(upBtn);
    about.parentNode.insertBefore(wrap, upMsg);
    upBtn.addEventListener("click", function () {
      if (upState === "available") B.installUpdate(); else B.checkUpdate();
    });
    window.__studyUpdate({ state: "idle" });
    upMsg.textContent = "Version " + (B.appVersionName() || "") + " (build " + B.appBuild() + ").";
    B.checkUpdate();
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
      attributeFilter: ["data-theme", "data-accent"]
    });
    if (window.matchMedia) {
      var dark = window.matchMedia("(prefers-color-scheme: dark)");
      if (dark.addEventListener) dark.addEventListener("change", sendBars);
    }
    sendBars();
    addUpdateRow();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }
})();
