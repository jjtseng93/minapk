package com.drjohn.test1;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.*;
import android.media.*;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.system.Os;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Base64;
import android.util.Log;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.*;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

public class MainActivity extends Activity {

    private static final String PAYLOAD_ASSET = "buninu.tgz";
    private static final String PAYLOAD_STAMP = "buninu.stamp";

    // ---- sz (ZMODEM download) ----
    // jsgotty's Zmodem.Browser.save_to_disk(pieces, name) builds a Blob +
    // blob: URL + synthetic <a download> click -- WebView has no Java-side
    // way to read a blob: URL's bytes back out (DownloadListener only sees
    // the blob: string, never the content), so the bytes have to be handed
    // over from inside the page's own JS instead. Re-run per onPageFinished
    // since a real navigation wipes window.Zmodem along with the rest of the
    // page's JS globals -- the window.Zmodem existence check guards the
    // (likely, future) case where the WebView navigates to a page that
    // never loads jsgotty's bundle at all.
    private static final String SZ_SAVE_TO_DISK_PATCH_JS =
        "(function(){\n"
        + "if(typeof window.Zmodem==='undefined'||!window.Zmodem.Browser||typeof window.Zmodem.Browser.save_to_disk!=='function')return;\n"
        + "if(typeof window.AndroidSz==='undefined')return;\n"
        + "if(window.Zmodem.Browser.__buninuPatched)return;\n"
        + "window.Zmodem.Browser.__buninuPatched=true;\n"
        // gotty.js tries window.showSaveFilePicker first when it exists as a
        // function, and its try/catch does NOT fall back to save_to_disk on
        // failure -- it just aborts. WebView's own showSaveFilePicker exists
        // but is broken ("user aborted a request"), so it has to be removed
        // entirely to force gotty.js down the save_to_disk branch below.
        + "try{delete window.showSaveFilePicker;}catch(e){}\n"
        + "window.showSaveFilePicker=undefined;\n"
        + "window.Zmodem.Browser.save_to_disk=function(pieces,name){\n"
        + "new Blob(pieces).arrayBuffer().then(function(buf){\n"
        + "var bytes=new Uint8Array(buf);\n"
        + "var binary='';\n"
        + "var chunk=32768;\n"
        + "for(var i=0;i<bytes.length;i+=chunk){binary+=String.fromCharCode.apply(null,bytes.subarray(i,i+chunk));}\n"
        + "window.AndroidSz.saveFile(name,btoa(binary));\n"
        + "});\n"
        + "};\n"
        + "})();";

    // jsgotty's own static/index.html already defines a page-local
    // alert_advanced(text, asHtml, closeOnClick) global (near-identical to
    // ../ancinew's libds/webconsole.js version this was first ported from)
    // -- call that directly instead of redefining the same dialog logic
    // here, so it stays in sync with jsgotty's own copy automatically.
    // getTerminalText() (gotty.js) reads straight from xterm.js's own
    // buffer model rather than the rendered DOM -- necessary because jsgotty
    // starts with --webgl by default, and a canvas/WebGL renderer paints
    // glyphs onto a <canvas>, not real DOM text nodes, so a .xterm-screen
    // innerText query would come back empty or unreliable under that
    // renderer. Both existence checks guard the case where this page never
    // loaded jsgotty's bundle at all.
    private static final String ALERT_ADVANCED_JS =
        "(function(){\n"
        + "if(typeof window.alert_advanced!=='function')return;\n"
        + "var text=typeof window.getTerminalText==='function'"
        + "?window.getTerminalText():'(getTerminalText not found)';\n"
        + "window.alert_advanced(text);\n"
        + "})();";

    // Ported from ../hello's _toggleEruda(). webView has no native on-device
    // devtools UI (only remote chrome://inspect debugging), so this injects
    // eruda (github.com/liriliri/eruda) from a CDN on first use, then just
    // shows/hides it on repeat use instead of re-injecting. Needs network.
    private static final String ERUDA_TOGGLE_JS =
        "(function(){\n"
        + "function toggle(){\n"
        + "if(window.__erudaShown){eruda.hide();window.__erudaShown=false;}\n"
        + "else{eruda.show();window.__erudaShown=true;}\n"
        + "}\n"
        + "if(window.eruda){toggle();return;}\n"
        + "var s=document.createElement('script');\n"
        + "s.src='https://cdn.jsdelivr.net/npm/eruda';\n"
        + "s.onload=function(){eruda.init();eruda.show();window.__erudaShown=true;};\n"
        + "s.onerror=function(){alert('eruda load failed (need network)');};\n"
        + "document.body.appendChild(s);\n"
        + "})();";

    // Zooming by rewriting the viewport meta tag's width, which is what
    // desktop Chrome's Ctrl+scroll actually does under the hood: it resizes
    // the CSS layout viewport. Halve the viewport width and every CSS pixel
    // covers twice as much screen, so text and images scale together *and*
    // responsive layouts genuinely re-lay-out at the new width -- rather
    // than being rescaled after the fact.
    //
    // None of the three native knobs manages all of that:
    //
    //   setTextZoom()      text only -- images keep their size, and content
    //                      painted onto a <canvas> (jsgotty's terminal is
    //                      exactly that) doesn't scale at all.
    //   zoomBy()           page-scale, same as a pinch gesture: everything
    //                      scales, but as a post-render rescale of the
    //                      surface, so there is no reflow and the page has
    //                      to be panned sideways to be read.
    //   setInitialScale()  the same page scale, and only consulted when a
    //                      page load starts, so nothing changes until the
    //                      next navigation.
    //
    // Zooming far enough out also subsumes a separate "request desktop
    // site" mode: a wide enough viewport is a desktop-width layout.
    // WebSettings.setUseWideViewPort(true) (see onCreate) is what makes
    // WebView honor the tag at all rather than always laying out at the
    // WebView's own width.
    //
    // The baseline is captured from the page's natural layout width the
    // first time this runs on a document, so zoom percentages stay relative
    // to however that page would have laid itself out. It lives on the page
    // and a navigation lands on a fresh document, hence re-applying from
    // onPageFinished -- which also makes zoom apply to external pages
    // opened through the menu's Go to URL.
    //
    // Kept per WebView rather than as one app-wide percentage: the console
    // and the app WebView show unrelated pages, so a zoom picked for one
    // must not be reported by the zoom dialog -- or re-applied on
    // navigation -- for the other.
    private final Map<WebView, Integer> webViewZoom = new HashMap<>();

    // 100% restores the page's own original viewport tag verbatim (removing
    // the tag entirely if it never had one) rather than writing out
    // width=<baseline>. A computed width is only an approximation of the
    // original: real pages ship things like "width=device-width,
    // initial-scale=1, viewport-fit=cover", and rewriting that as a bare
    // pixel width silently drops every directive but the width -- so 100%
    // would not actually be the unzoomed page.
    private static String zoomJs(int percent) {
        return "(function(){\n"
            + "var m=document.querySelector('meta[name=\"viewport\"]');\n"
            + "if(window.__buninuBaseViewport==null){\n"
            + "window.__buninuBaseViewport=document.documentElement.clientWidth||360;\n"
            + "window.__buninuBaseViewportContent=m?m.getAttribute('content'):null;\n"
            + "}\n"
            + "if(" + percent + "===100){\n"
            + "if(window.__buninuBaseViewportContent==null){if(m)m.parentNode.removeChild(m);}\n"
            + "else if(m)m.setAttribute('content',window.__buninuBaseViewportContent);\n"
            + "return;\n"
            + "}\n"
            + "if(!m){m=document.createElement('meta');m.name='viewport';"
            + "(document.head||document.documentElement).appendChild(m);}\n"
            + "m.setAttribute('content','width='"
            + "+Math.round(window.__buninuBaseViewport*100/" + percent + "));\n"
            + "})();";
    }

    // Guards the divide in zoomJs: anything at or below zero would make the
    // derived viewport width infinite or negative.
    private void applyZoom(int percent) {
        if (webView == null) return;
        int applied = percent > 0 ? percent : 100;
        webViewZoom.put(webView, Integer.valueOf(applied));
        webView.evaluateJavascript(zoomJs(applied), null);
    }

    private int zoomPercentOf(WebView view) {
        Integer percent = webViewZoom.get(view);
        return percent != null ? percent.intValue() : 100;
    }

    // ---- WebViews ----
    // id 0 is the console: the jsgotty terminal Buninu itself starts, and the
    // one every existing feature (extra-keys bar, volume menu, back key) was
    // written against. id 1 is the app WebView. Both are created in onCreate
    // and live for the whole life of the app -- nothing here is ever created
    // on demand or closed, so an id either exists from startup or does not
    // exist at all.
    //
    // webView keeps its original meaning of "the WebView the user is looking
    // at and typing into", now as a pointer into webViews rather than the
    // only one there is. That is what makes the extra-keys bar, the volume
    // menu and the back key follow the current WebView without any of them
    // knowing this registry exists: they all act on this one field.
    private static final int CONSOLE_WEBVIEW_ID = 0;
    private static final int APP_WEBVIEW_ID = 1;

    private WebView webView;
    private FrameLayout webContainer;
    private final Map<Integer, WebView> webViews = new LinkedHashMap<>();
    // Read from the bridge's per-connection threads (currWebView), written on
    // the main thread; every other access to the registry itself stays on the
    // main thread via runOnMainThreadSync.
    private volatile int currentWebViewId = CONSOLE_WEBVIEW_ID;

    // buninu.backToConsole in the payload's package.json -- the same file
    // buninu.command and buninu.exitAfterCmd live in -- decides what the back
    // key does on the app WebView once it has no page of its own left to go
    // back to: switch to the console (true, the default) or fall through to
    // the system's own back behavior and leave the app (false). Read once at
    // startup; see readBackToConsole for why nothing here can fail.
    private volatile boolean backToConsole = true;

    private boolean urlLoaded = false;
    private String lastExternalUrl;
    private long lastExternalUrlAt;

    // ---- WebChromeClient.onShowFileChooser (rz upload via <input type=file>) ----
    private static final int FILE_CHOOSER_REQUEST_CODE = 51423;
    private ValueCallback<Uri[]> filePathCallback;

    // ---- native-bridge: speak/ttsStatus ----
    // speak() never blocks the caller: TextToSpeech's own completion signal
    // (UtteranceProgressListener) is asynchronous, so the far side is
    // expected to poll ttsStatus(handle) itself rather than have this
    // connection-handling thread sit blocked for however long the utterance
    // takes to finish (unlike toast/clipboard, whose underlying Android calls
    // return immediately). ttsStatusMap is keyed by a per-call handle rather
    // than one global flag so concurrent speak() calls cannot clobber each
    // other's status.
    private TextToSpeech ttsEngine;
    private volatile boolean ttsReady = false;
    private final Object ttsInitLock = new Object();
    private final Map<String, String> ttsStatusMap = new ConcurrentHashMap<>();
    private final AtomicLong ttsHandleCounter = new AtomicLong();

    // ---- extra-keys control bar (Ctrl/Alt/Shift + IME receiver), ported from ../hello ----
    private static final int KEY_REPEAT_MS = 70;
    private static final Set<String> NO_EXTRA_MODIFIERS = Collections.emptySet();
    private static final Set<String> CTRL_MODIFIER = Collections.singleton("CTRL");
    private static final Set<String> SHIFT_MODIFIER = Collections.singleton("SHIFT");

    private FrameLayout extraKeysBar;
    private EditText imeReceiver;
    private Button ctrlButton;
    private Button altButton;
    private Button shiftButton;
    private final Set<String> keyModifiers = new LinkedHashSet<>();
    private final Handler keyRepeatHandler = new Handler(Looper.getMainLooper());
    private Runnable keyRepeatRunnable;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);

        // Background WebViews are INVISIBLE rather than GONE so they are still
        // laid out at full size: a page loading behind the front one then gets
        // a real viewport instead of a 0x0 one, which is the whole point of
        // openWebView() being a background load.
        webContainer = new FrameLayout(this);
        webView = createWebView(CONSOLE_WEBVIEW_ID);
        createWebView(APP_WEBVIEW_ID);

        extraKeysBar = buildExtraKeysBar();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(webContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(extraKeysBar, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        setContentView(root);
        applyWindowInsets(root);
        registerBackHandler();

        startBunProcess();
    }

    // Every WebView the app has is built here, so the app WebView behaves
    // exactly like the console one: same settings, same AndroidSz bridge for
    // sz downloads, same external-link handling, same file chooser for rz
    // uploads. Main thread only.
    private WebView createWebView(int id) {
        final WebView wv = new WebView(this);
        wv.getSettings().setJavaScriptEnabled(true);
        wv.getSettings().setDomStorageEnabled(true);
        // Pinch-to-zoom, independent of the menu's own viewport-width zoom.
        // DisplayZoomControls(false) keeps the gesture while suppressing the
        // floating +/- overlay widget it otherwise shows.
        wv.getSettings().setSupportZoom(true);
        wv.getSettings().setBuiltInZoomControls(true);
        wv.getSettings().setDisplayZoomControls(false);
        // Required for the menu's zoom to work at all: without this, WebView
        // ignores the viewport meta tag and always lays out at its own width.
        // Overview mode then fits a wide layout on screen instead of showing
        // only its top-left corner.
        wv.getSettings().setUseWideViewPort(true);
        wv.getSettings().setLoadWithOverviewMode(true);
        wv.addJavascriptInterface(new SzJsBridge(this), "AndroidSz");
        wv.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(SZ_SAVE_TO_DISK_PATCH_JS, null);
                // The viewport tag lives in the page, so a navigation resets
                // it -- re-apply whatever the menu last set.
                int percent = zoomPercentOf(view);
                if (percent != 100) view.evaluateJavascript(zoomJs(percent), null);
            }

            // Only intercepts navigations the WebView itself initiates (link taps,
            // JS location changes, redirects) — not our own loadUrl() call that
            // opens the terminal, so it can't hijack that on the way in.
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();

                // window.open() (xterm.js's link handlers use it) has no
                // WebChromeClient.onCreateWindow to host a new window, so
                // Chromium treats it as a blocked popup and fires this
                // callback a second time for its own internal sentinel URL,
                // not a real navigation. Without this check we'd startActivity
                // on "about:blank#blocked" too, which produces a second,
                // unresolvable app-chooser prompt.
                if ("about:blank#blocked".equals(url)) return true;

                // A server-side redirect fires this callback again for the
                // redirected-to URL. We already diverted the original tap to an
                // external app below, so only act on the first (non-redirect)
                // call -- otherwise the same tap launches a second app-chooser
                // for the redirect target.
                if (request.isRedirect()) return true;

                // xterm.js (bundled in jsgotty) runs two independent link
                // detectors -- WebLinksAddon (regex over plain text) and
                // OscLinkProvider (OSC 8 hyperlink escapes) -- and either can
                // call window.open() for a click. When a link matches both,
                // one tap fires shouldOverrideUrlLoading twice for the exact
                // same URL, opening two app-chooser prompts. Collapse repeats
                // of the same URL within a short window into one.
                long now = SystemClock.uptimeMillis();
                if (url.equals(lastExternalUrl) && now - lastExternalUrlAt < 1000) return true;
                lastExternalUrl = url;
                lastExternalUrlAt = now;

                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, request.getUrl()));
                } catch (Exception e) {
                    return false; // no app can handle it; let the WebView try instead
                }
                return true;
            }
        });
        wv.setWebChromeClient(new WebChromeClient() {
            // jsgotty's browser client uses a real <input type="file"> for rz
            // uploads; without a WebChromeClient the tap on it does nothing
            // at all (no picker, no error) because there's no host to route
            // the request to.
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                         FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;

                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }

                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }
        });
        webViews.put(Integer.valueOf(id), wv);
        webContainer.addView(wv, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        wv.setVisibility(id == currentWebViewId ? View.VISIBLE : View.INVISIBLE);
        return wv;
    }

    // -1 means "whichever WebView is in front right now" for every bridge
    // function that takes an id -- except showWebView, where switching to the
    // one already in front would be a no-op, so there it means "switch to the
    // next one" (see showWebView). Returns null for an id that does not
    // exist, since ids are fixed at startup rather than allocated on demand.
    private WebView resolveWebView(int id) {
        return webViews.get(Integer.valueOf(id < 0 ? currentWebViewId : id));
    }

    // Bring one WebView to the front and make it the one the extra-keys bar,
    // the volume menu and the back key act on. Nothing is closed or
    // destroyed: the one leaving the front keeps running exactly as it was,
    // it just stops being visible. Returns the id now in front, or -1 for an
    // unknown id. Main thread only.
    private int showWebView(int id) {
        int target = id < 0 ? nextWebViewId() : id;
        WebView view = webViews.get(Integer.valueOf(target));
        if (view == null) return -1;
        if (view != webView) {
            if (webView != null) webView.setVisibility(View.INVISIBLE);
            view.setVisibility(View.VISIBLE);
            view.bringToFront();
            webView = view;
            currentWebViewId = target;
            // An armed CTRL/ALT/SHIFT or a key/scroll repeat in flight
            // belongs to the WebView that is going away, not the one
            // arriving, so neither carries across the switch.
            stopKeyRepeat();
            clearKeyModifiers();
            // requestFocus only, deliberately without showSoftInput: a switch
            // is not the user asking to type, and the soft keyboard comes
            // back on the next extra-keys tap anyway.
            view.requestFocus();
        }
        return target;
    }

    // Ordered by id and wrapping around, so with the console and the app
    // WebView -- the only two that exist -- showWebView(-1) is simply a
    // toggle between them.
    private int nextWebViewId() {
        List<Integer> ids = new ArrayList<>(webViews.keySet());
        Collections.sort(ids);
        if (ids.isEmpty()) return currentWebViewId;
        int index = ids.indexOf(Integer.valueOf(currentWebViewId));
        return ids.get((index + 1) % ids.size()).intValue();
    }

    private String webViewLabel(int id) {
        if (id == CONSOLE_WEBVIEW_ID) return "console";
        if (id == APP_WEBVIEW_ID) return "app";
        return "webview";
    }

    // Apps targeting API 35+ are laid out edge-to-edge, and from API 36 on the
    // windowOptOutEdgeToEdgeEnforcement flag is ignored, so the navigation bar
    // would otherwise be drawn on top of the extra-keys bar. Take the insets
    // ourselves and pad the root instead of drawing underneath the system bars.
    private void applyWindowInsets(View root) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Without this the decor consumes the insets and the listener below
            // only ever sees zeros.
            getWindow().setDecorFitsSystemWindows(false);
        }
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left, top, right, bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    // Keep the bar above the keyboard too, without double-counting
                    // the navigation bar the IME already covers.
                    int ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = Math.max(bars.bottom, ime);
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });
        root.requestApplyInsets();
    }

    // Swallow volume-up and use it to open a small debug menu instead of
    // changing the media volume. Volume-down is left untouched.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) showDebugMenu();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void showDebugMenu() {
        // The switch entry names where it is about to go rather than opening a
        // picker: with the console and the app WebView as the only two, a
        // picker would be a second tap to choose the one item that isn't the
        // one already in front.
        int next = nextWebViewId();
        new AlertDialog.Builder(this)
            .setItems(new String[]{
                    "Toggle ctrl/alt/shift bar", "Eval in WebView", "Select Terminal Text",
                    "Back", "Forward", "Go to URL...", "Zoom...", "Eruda console",
                    "Background permissions...",
                    "Switch WebView \u2192 " + next + ": " + webViewLabel(next),
                },
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        switch (which) {
                            case 0: toggleExtraKeys(); break;
                            case 1: showEvalDialog(); break;
                            case 2:
                                if (webView != null) webView.evaluateJavascript(ALERT_ADVANCED_JS, null);
                                break;
                            case 3:
                                if (webView != null && webView.canGoBack()) webView.goBack();
                                break;
                            case 4:
                                if (webView != null && webView.canGoForward()) webView.goForward();
                                break;
                            case 5: showGotoUrlDialog(); break;
                            case 6: showZoomDialog(); break;
                            case 7:
                                if (webView != null) webView.evaluateJavascript(ERUDA_TOGGLE_JS, null);
                                break;
                            case 8: showBackgroundPermissionsDialog(); break;
                            case 9: showWebView(-1); break;
                        }
                    }
                })
            .show();
    }

    private void showGotoUrlDialog() {
        if (webView == null) return;
        final EditText input = new EditText(this);
        input.setHint("https://...");
        String current = webView.getUrl();
        if (current != null) {
            input.setText(current);
            input.setSelection(current.length());
        }
        new AlertDialog.Builder(this)
            .setTitle("Go to URL")
            .setView(input)
            .setPositiveButton("Go", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    // Always navigates, even back to the same URL -- loadUrl()
                    // is a fresh navigation/reload regardless of what's
                    // currently loaded, not a no-op guarded by URL equality.
                    String url = input.getText().toString().trim();
                    if (!url.isEmpty()) webView.loadUrl(url);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // The slider covers 50%-300% in 10% steps (../hello's range: min 0.5,
    // max 3.0, 25 divisions), which is the comfortable range to drag
    // through -- but the number field is deliberately not limited to it,
    // since typing an exact value is the whole reason it's there. Its own
    // bounds come from the actual constraint instead: a viewport meta width
    // is only valid from 1 to 10000 CSS px, and zoomJs() derives that width
    // as base*100/percent, so percentages far outside the slider's range
    // stay meaningful. Typing past the slider's ends just pins the slider
    // there while still applying the typed value.
    private void showZoomDialog() {
        if (webView == null) return;

        final int min = 50, max = 300, step = 10;
        final int typedMin = 5, typedMax = 2000;
        // Start from whatever zoom is already in effect rather than assuming
        // 100%, so reopening the dialog doesn't misreport the current state.
        final int current = zoomPercentOf(webView);
        final SeekBar seekBar = new SeekBar(this);
        seekBar.setMax((max - min) / step);
        final EditText number = new EditText(this);
        number.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        number.setText(String.valueOf(current));
        seekBar.setProgress((Math.max(min, Math.min(max, current)) - min) / step);

        final boolean[] syncing = { false };

        final Runnable applyFromNumber = new Runnable() {
            @Override public void run() {
                if (syncing[0]) return;
                int percent;
                try {
                    percent = Integer.parseInt(number.getText().toString().trim());
                } catch (NumberFormatException e) {
                    percent = 0; // empty field, or nothing parseable yet
                }
                // Editing passes through transient falsy states -- a cleared
                // field, or a leading "0" on the way to "50" -- and 0 would
                // make zoomJs divide by zero for an infinite viewport width.
                // Fall back to 100% for those rather than applying nonsense.
                if (percent == 0) percent = 100;
                else if (percent < typedMin || percent > typedMax) return;
                syncing[0] = true;
                seekBar.setProgress((Math.max(min, Math.min(max, percent)) - min) / step);
                syncing[0] = false;
                applyZoom(percent);
            }
        };

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser || syncing[0]) return;
                int percent = min + progress * step;
                syncing[0] = true;
                number.setText(String.valueOf(percent));
                syncing[0] = false;
                applyZoom(percent);
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        number.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { applyFromNumber.run(); }
        });

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(48, 24, 48, 0);
        number.setLayoutParams(new LinearLayout.LayoutParams(160, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(number);
        row.addView(seekBar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(row);
        Button reset = new Button(this);
        reset.setText("Reset to 100%");
        reset.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // Deliberately not syncing/applying by hand: writing the
                // field with the guard down runs the same TextWatcher path
                // typing does, so reset cannot drift from it.
                number.setText("100");
            }
        });
        column.addView(reset);

        new AlertDialog.Builder(this)
            .setTitle("Zoom")
            .setView(column)
            .setPositiveButton("Close", null)
            .show();
    }

    // ------------------------------------------------ background permissions

    private boolean hasNotifPermission() {
        return Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBatteryWhitelisted() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private static final int REQUEST_NOTIF_PERMISSION = 1001;

    // If the OS will no longer show the system dialog (denied before without
    // "don't ask again" still being retractable), fall back to the app's own
    // notification settings page so the user can still grant it from there.
    private void requestNotifPermission() {
        if (hasNotifPermission() || Build.VERSION.SDK_INT < 33) return;
        SharedPreferences prefs = getSharedPreferences("gt", MODE_PRIVATE);
        boolean asked = prefs.getBoolean("notif_asked", false);
        boolean canPrompt = shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS);
        if (!asked || canPrompt) {
            prefs.edit().putBoolean("notif_asked", true).apply();
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQUEST_NOTIF_PERMISSION);
        } else {
            Intent i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            try {
                startActivity(i);
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            }
        }
    }

    private void requestBatteryWhitelist() {
        if (isBatteryWhitelisted()) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "no battery optimization settings page found", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // Entirely optional/on-demand: nothing in minapk requires either
    // permission, this just makes them reachable for whoever wants the app
    // to keep running reliably in the background. Re-shows itself after
    // each tap so the status labels reflect what just happened.
    private void showBackgroundPermissionsDialog() {
        String notifLabel = "Notifications: " + (hasNotifPermission() ? "granted" : "tap to request");
        String batteryLabel = "Ignore battery optimization: " +
            (isBatteryWhitelisted() ? "granted" : "tap to request");
        new AlertDialog.Builder(this)
            .setTitle("Background permissions")
            .setItems(new String[]{ notifLabel, batteryLabel },
                new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) requestNotifPermission();
                        else requestBatteryWhitelist();
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // For live-poking the WebView's JS state (e.g. typeof window.Zmodem)
    // without a full rebuild cycle.
    private void showEvalDialog() {
        final EditText input = new EditText(this);
        input.setHint("JS expression");
        new AlertDialog.Builder(this)
            .setTitle("Eval in WebView")
            .setView(input)
            .setPositiveButton("Run", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    if (webView == null) return;
                    webView.evaluateJavascript(input.getText().toString(), new ValueCallback<String>() {
                        @Override public void onReceiveValue(String value) {
                            new AlertDialog.Builder(MainActivity.this)
                                .setTitle("Result")
                                .setMessage(String.valueOf(value))
                                .setPositiveButton("OK", null)
                                .show();
                        }
                    });
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // Tapping the WebView while a modifier chord is armed clears it, so a
    // stray CTRL/ALT/SHIFT toggle can't silently modify the next real tap.
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !keyModifiers.isEmpty() && webView != null) {
            Rect bounds = new Rect();
            if (webView.getGlobalVisibleRect(bounds) &&
                bounds.contains((int) event.getRawX(), (int) event.getRawY())) {
                clearKeyModifiers();
            }
        }
        return super.dispatchTouchEvent(event);
    }

    // Process-wide rather than per-Activity, because the app process outlives
    // an Activity that has finished: Android keeps it around as a cached
    // process, threads and child processes included. A relaunch then builds a
    // *new* MainActivity inside that same process, and these two are what it
    // collides with -- the bridge's name lives in Linux's abstract namespace,
    // which releases it only when the socket is closed or the process dies,
    // so binding it a second time fails with EADDRINUSE and the app comes back
    // up with no PKG_BRIDGE_SOCK at all. Keeping the references here is what
    // lets onDestroy (and the next start) clean up after the previous one.
    private static LocalServerSocket bridgeServer;
    private static java.lang.Process bunProcess;

    private void startBunProcess() {
        // Belt and braces for the case where onDestroy never ran (the system
        // killed the Activity without it, say): whatever is left over from a
        // previous Activity in this process goes away before a new one is
        // started, rather than ending up with two Buninu processes.
        stopBunProcess();

        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    File home = new File(getApplicationInfo().dataDir, "no_backup");
                    try {
                        home = ensureBuninuPayload();
                    } catch (Exception updateError) {
                        File existingInit = new File(home, "bin/init.js");
                        if (!existingInit.isFile()) throw updateError;
                        Log.w("BunPayload", "payload update failed; starting existing Buninu home", updateError);
                        writeErrorToTmp("payload update failed; starting existing Buninu home", updateError);
                        try {
                            linkNativeLibraries(home);
                        } catch (Exception linkError) {
                            Log.w("BunPayload", "native link refresh failed; continuing with existing links", linkError);
                            writeErrorToTmp("native link refresh failed; continuing with existing links", linkError);
                        }
                    }
                    // After the payload settles, so this reads whatever
                    // package.json the app is actually going to run with --
                    // including one a payload update just replaced.
                    readBackToConsole(home);

                    String nativeLibDir = getApplicationInfo().nativeLibraryDir;
                    File bunFile = new File(nativeLibDir, "libbun.so");
                    if (bunFile.exists()) {
                        bunFile.setExecutable(true, false);
                    }

                    String execPath = bunFile.exists() ? bunFile.getAbsolutePath() : "libbun.so";

                    File initFile = new File(home, "bin/init.js");
                    if (!initFile.isFile()) {
                        throw new FileNotFoundException("Buninu init not found: " + initFile);
                    }

                    // --no-orphans makes Bun (a) exit when this app process
                    // dies and (b) kill every descendant of its own on the way
                    // out. Both halves matter here: Process.destroy() below
                    // only ever reaches Bun itself, so without it jsgotty and
                    // the shell Bun spawned would be reparented and keep
                    // running after the user leaves, and an app process killed
                    // by the system would leave the whole tree behind with
                    // nothing left to ever clean it up.
                    ProcessBuilder pb = new ProcessBuilder(
                        execPath, "--no-orphans", initFile.getAbsolutePath());
                    pb.directory(home);

                    Map<String, String> env = pb.environment();
                    String oldPath = env.get("PATH");
                    env.put("PATH",
                        nativeLibDir +
                        (oldPath != null && !oldPath.isEmpty() ? ":" + oldPath : ":/system/bin") +
                        ":" + new File(home, "bin").getAbsolutePath());
                    env.put("HOME", home.getAbsolutePath());
                    env.put("TMPDIR", getCacheDir().getAbsolutePath());
                    env.put("SHELL", "/system/bin/sh");
                    env.put("BUNINU_HOME", home.getAbsolutePath());

                    File externalFilesDir = getExternalFilesDir(null);
                    File externalDataDir = externalFilesDir != null
                        ? externalFilesDir.getParentFile()
                        : null;
                    if (externalDataDir != null &&
                        (externalDataDir.isDirectory() || externalDataDir.mkdirs())) {
                        env.put("PKG_DDIR", externalDataDir.getAbsolutePath());
                    }

                    File[] mediaDirs = getExternalMediaDirs();
                    if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                        File mediaDir = mediaDirs[0];
                        if (mediaDir.isDirectory() || mediaDir.mkdirs()) {
                            env.put("PKG_MDIR", mediaDir.getAbsolutePath());
                        }
                    }

                    String bridgeEnvValue = startNativeBridge();
                    if (bridgeEnvValue != null) {
                        env.put("PKG_BRIDGE_SOCK", bridgeEnvValue);
                    }

                    pb.redirectErrorStream(true);
                    java.lang.Process process = pb.start();
                    bunProcess = process;

                    BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;

                    while ((line = reader.readLine()) != null) {
                        if (!urlLoaded && (line.contains("http://") || line.contains("https://"))) {
                            urlLoaded = true;
                            final String targetUrl = extractUrl(line);
                            runOnUiThread(new Runnable() {
                                @Override public void run() {
                                    // Explicitly the console WebView, not
                                    // whichever one happens to be in front:
                                    // this is jsgotty's own terminal URL.
                                    WebView console = webViews.get(Integer.valueOf(CONSOLE_WEBVIEW_ID));
                                    if (console != null) {
                                        console.loadUrl(targetUrl);
                                    }
                                }
                            });
                        }
                    }
                    int processStatus = process.waitFor();
                    if (processStatus != 0) {
                        writeErrorToTmp("Bun process exited with status " + processStatus, null);
                    }
                } catch (Exception e) {
                    Log.e("BunExec", "Error running bun process", e);
                    writeErrorToTmp("Error running Bun process", e);
                }
            }
        }).start();
    }

    // Dummy native bridge: listens on a Linux abstract-namespace unix socket
    // and answers every request the same way ("succeeded", regardless of
    // which function or arguments were actually sent), purely to verify the
    // transport itself -- LocalServerSocket, Bun's `unix` fetch option, and
    // native-bridge.js's percent-decoding of PKG_BRIDGE_SOCK -- works end to
    // end inside a packaged APK, before any real Toast/ClipboardManager
    // implementation is wired in.
    //
    // Returns the value to put in PKG_BRIDGE_SOCK, or null if the bridge
    // could not be started (Bun still starts normally either way; the app
    // just runs without a working native bridge, the same as it does today).
    //
    // LocalServerSocket(name) takes a plain name and puts it in the abstract
    // namespace itself (prefixing the NUL byte internally) -- that is a
    // socket-creation detail Android's own API already handles. The value
    // handed to Bun's side is a different thing: an env var cannot carry a
    // literal NUL byte, and rpc.mjs's unix-socket handling on the JS side
    // expects a percent-encoded path it runs through decodeURIComponent(), so
    // this returns "%00" followed by the same name, not the name itself.
    private String startNativeBridge() {
        final String name = getPackageName() + ".native-bridge";

        // A previous Activity in this same process may still hold the name
        // (see bridgeServer). Closing it first is what makes the bind below
        // succeed on a relaunch; without this it fails with EADDRINUSE and the
        // whole app comes back up without a bridge, which looks exactly like
        // "native-bridge stopped working after I left and reopened".
        closeBridgeServer();

        final LocalServerSocket server;
        try {
            server = new LocalServerSocket(name);
        } catch (IOException e) {
            Log.w("NativeBridge", "failed to start native bridge", e);
            writeErrorToTmp("native bridge failed to start; continuing without it", e);
            return null;
        }
        bridgeServer = server;

        new Thread(new Runnable() {
            @Override public void run() {
                while (true) {
                    try {
                        LocalSocket client = server.accept();
                        handleNativeBridgeConnection(client);
                    } catch (IOException e) {
                        Log.w("NativeBridge", "accept loop stopped", e);
                        break;
                    }
                }
            }
        }).start();

        return "%00" + name;
    }

    // One request per connection: reads a minimal HTTP/1.1 request (headers
    // up to the blank line, then exactly Content-Length body bytes) and
    // writes back a minimal HTTP/1.1 response. Bun's fetch() does not require
    // the connection to stay open afterward, so there is no keep-alive
    // handling here.
    //
    // rpc.mjs's own client-side dispatch (rpcraw in rpc.mjs) requires
    // _discover to answer with an object whose keys are the callable function
    // names, or it rejects every later call locally before ever reaching this
    // server; this pre-declares each function's name -- toast is short
    // enough on its own, clipboardRead/clipboardWrite also get the short
    // aliases getcb/setcb -- that native-bridge.js's own convenience
    // wrappers use. Unrecognized function names get back the same
    // "Unknown func ..." text rpc.mjs's own evalBack would produce, so
    // native-bridge.js's THROW_UNKNOWN_FUNC/nothrow detection (which matches
    // on that exact prefix) works the same whether the far side is this Java
    // bridge or another rpc.mjs backend.
    private void handleNativeBridgeConnection(final LocalSocket client) {
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    InputStream in = client.getInputStream();
                    OutputStream out = client.getOutputStream();

                    StringBuilder headerBuf = new StringBuilder();
                    int prev3 = -1, prev2 = -1, prev1 = -1, b;
                    while ((b = in.read()) != -1) {
                        headerBuf.append((char) b);
                        if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
                        prev3 = prev2; prev2 = prev1; prev1 = b;
                    }

                    int contentLength = 0;
                    for (String headerLine : headerBuf.toString().split("\r\n")) {
                        int colon = headerLine.indexOf(':');
                        if (colon < 0) continue;
                        if (headerLine.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                            contentLength = Integer.parseInt(headerLine.substring(colon + 1).trim());
                        }
                    }

                    byte[] bodyBytes = new byte[contentLength];
                    int read = 0;
                    while (read < contentLength) {
                        int n = in.read(bodyBytes, read, contentLength - read);
                        if (n < 0) break;
                        read += n;
                    }
                    String body = new String(bodyBytes, 0, read, "UTF-8");

                    String responseJson = dispatchNativeBridgeRequest(body);

                    byte[] responseBody = responseJson.getBytes("UTF-8");
                    String responseHead = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: " + responseBody.length + "\r\n" +
                        "Connection: close\r\n\r\n";
                    out.write(responseHead.getBytes("UTF-8"));
                    out.write(responseBody);
                    out.flush();
                } catch (Exception e) {
                    Log.w("NativeBridge", "connection handling failed", e);
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                }
            }
        }).start();
    }

    // Parses one rpc.mjs-protocol request body ([func, argv, envp], envp
    // unused) and returns the JSON text to send back. Toast and clipboard
    // access both require the main looper (Toast.show() needs one to attach
    // its view to; ClipboardManager is likewise only reliably usable from the
    // main thread), so both run through runOnMainThreadSync rather than
    // directly on this per-connection thread. speak() also hops to the main
    // thread this same way, but only to issue the speak() call itself --
    // unlike toast/clipboard, it does not wait for the underlying work
    // (actually finishing the utterance) to complete; see ttsStatus below.
    private String dispatchNativeBridgeRequest(String body) throws Exception {
        JSONArray request = new JSONArray(body);
        String func = request.optString(0, "");
        JSONArray argv = request.optJSONArray(1);
        if (argv == null) argv = new JSONArray();
        final JSONArray finalArgv = argv;

        if (func.equals("_discover")) {
            return "{"
                + "\"toast\":[\"text\",\"long\"],"
                + "\"clipboardRead\":[],"
                + "\"getcb\":[],"
                + "\"clipboardWrite\":[\"text\"],"
                + "\"setcb\":[\"text\"],"
                + "\"speak\":[\"text\",\"speed\",\"pitch\",\"flush\"],"
                + "\"ttsStatus\":[\"handle\"],"
                + "\"tts\":[\"handle\"],"
                + "\"dltext\":[\"name\",\"text\"],"
                + "\"dlbin\":[\"name\",\"base64\"],"
                + "\"xdgOpen\":[\"target\"],"
                + "\"openWebView\":[\"id\",\"url\"],"
                + "\"openwv\":[\"id\",\"url\"],"
                + "\"evalWebView\":[\"id\",\"js\"],"
                + "\"evalwv\":[\"id\",\"js\"],"
                + "\"showWebView\":[\"id\"],"
                + "\"showwv\":[\"id\"],"
                + "\"currWebView\":[],"
                + "\"currwv\":[]"
                + "}";
        }

        if (func.equals("toast")) {
            final String text = finalArgv.optString(0, "");
            final boolean isLong = finalArgv.optBoolean(1, false);
            runOnMainThreadSync(new Callable<Object>() {
                @Override public Object call() {
                    Toast.makeText(MainActivity.this, text,
                        isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
                    return null;
                }
            });
            return "true";
        }

        if (func.equals("clipboardRead") || func.equals("getcb")) {
            Object result = runOnMainThreadSync(new Callable<Object>() {
                @Override public Object call() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                        CharSequence text = cm.getPrimaryClip().getItemAt(0)
                            .coerceToText(MainActivity.this);
                        return text != null ? text.toString() : null;
                    }
                    return null;
                }
            });
            return result != null ? JSONObject.quote((String) result) : "null";
        }

        if (func.equals("clipboardWrite") || func.equals("setcb")) {
            final String text = finalArgv.optString(0, "");
            runOnMainThreadSync(new Callable<Object>() {
                @Override public Object call() {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("native-bridge", text));
                    }
                    return null;
                }
            });
            return "true";
        }

        if (func.equals("speak")) {
            ensureTtsReady();
            // Only text is required; speed/pitch/flush all default. 1.0 is
            // normal for both speed and pitch, same convention as
            // termux-tts-speak's -p/-r and jsmdcui's own TTS_PITCH/TTS_SPEED
            // env vars -- values pass straight through with no unit
            // conversion.
            final String text = finalArgv.optString(0, "");
            final float speed = (float) finalArgv.optDouble(1, 1.0);
            final float pitch = (float) finalArgv.optDouble(2, 1.0);
            final boolean flush = finalArgv.optBoolean(3, false);
            final String handle = "u" + ttsHandleCounter.incrementAndGet();
            ttsStatusMap.put(handle, "speaking");
            runOnMainThreadSync(new Callable<Object>() {
                @Override public Object call() {
                    ttsEngine.setPitch(pitch);
                    ttsEngine.setSpeechRate(speed);
                    ttsEngine.speak(text,
                        flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                        new Bundle(), handle);
                    return null;
                }
            });
            return JSONObject.quote(handle);
        }

        if (func.equals("ttsStatus") || func.equals("tts")) {
            final String handle = finalArgv.optString(0, "");
            String status = ttsStatusMap.get(handle);
            if (status == null) return JSONObject.quote("unknown");
            // Consume-once: once the caller has seen a terminal state, drop
            // the entry so ttsStatusMap does not grow without bound over the
            // life of the app. A second poll of the same handle after this
            // point correctly reports "unknown", not a stale "done".
            if (status.equals("done") || status.equals("error")) ttsStatusMap.remove(handle);
            return JSONObject.quote(status);
        }

        if (func.equals("dltext")) {
            // Throwaway probe for the sz (ZMODEM download) native bridge:
            // confirms writing into the public Downloads collection works
            // via MediaStore with zero manifest permissions, before wiring
            // up the real blob-bytes-over-JS-bridge path.
            String name = finalArgv.optString(0, "file.txt");
            String text = finalArgv.optString(1, "");
            try {
                Uri uri = writeToDownloads(name, text.getBytes("UTF-8"), "text/plain");
                return JSONObject.quote(uri.toString());
            } catch (IOException e) {
                return JSONObject.quote("error: " + e.getMessage());
            }
        }

        if (func.equals("dlbin")) {
            String name = finalArgv.optString(0, "file.bin");
            String base64 = finalArgv.optString(1, "");
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                Uri uri = writeToDownloads(name, data, "application/octet-stream");
                return JSONObject.quote(uri.toString());
            } catch (Exception e) {
                return JSONObject.quote("error: " + e.getMessage());
            }
        }

        if (func.equals("xdgOpen")) {
            final String target = finalArgv.optString(0, "");
            try {
                boolean launched = xdgOpen(target);
                return launched ? "true" : "false";
            } catch (Exception e) {
                return JSONObject.quote("error: " + e.getMessage());
            }
        }

        // ---- WebViews (see the registry near webView's declaration) ----
        // An id of -1 is "whichever WebView is in front" here and in
        // evalWebView; ids are fixed at startup (0 console, 1 app), so an
        // unknown one is an error rather than a request to create anything.
        // openWebView deliberately does not bring its target to the front:
        // loading into the app WebView while the console stays on screen is
        // the normal case, and showWebView is the only thing that ever
        // changes what the user is looking at.
        //
        // Each of the four also answers to a short openwv/evalwv/showwv/
        // currwv spelling, the same way clipboardRead/clipboardWrite answer
        // to getcb/setcb -- both spellings are listed in _discover above, so
        // either works from the CLI and from rpcraw, not only through
        // native-bridge.js's own wrappers.
        if (func.equals("openWebView") || func.equals("openwv")) {
            final int id = finalArgv.optInt(0, -1);
            final String url = finalArgv.optString(1, "");
            Object result = runOnMainThreadSync(new Callable<Object>() {
                @Override public Object call() {
                    WebView target = resolveWebView(id);
                    if (target == null) return null;
                    if (!url.isEmpty()) target.loadUrl(url);
                    return Integer.valueOf(id < 0 ? currentWebViewId : id);
                }
            });
            if (result == null) return JSONObject.quote("error: no such WebView: " + id);
            return String.valueOf(result);
        }

        if (func.equals("evalWebView") || func.equals("evalwv")) {
            return evalWebViewSync(finalArgv.optInt(0, -1), finalArgv.optString(1, ""));
        }

        if (func.equals("showWebView") || func.equals("showwv")) {
            final int id = finalArgv.optInt(0, -1);
            Object result = runOnMainThreadSync(new Callable<Object>() {
                @Override public Object call() {
                    return Integer.valueOf(showWebView(id));
                }
            });
            int shown = ((Integer) result).intValue();
            if (shown < 0) return JSONObject.quote("error: no such WebView: " + id);
            return String.valueOf(shown);
        }

        if (func.equals("currWebView") || func.equals("currwv")) {
            return String.valueOf(currentWebViewId);
        }

        return JSONObject.quote("Unknown func 未知函式: " + func + "\r\n" + finalArgv.toString());
    }

    // Every other bridge function finishes inside runOnMainThreadSync's own
    // Callable, so its return value is the answer. evaluateJavascript is not
    // like that: it starts an evaluation and delivers the result to a
    // callback on the main thread some time later, so what the Callable
    // returns is only "the evaluation was started". Hence a second latch,
    // waited on by this connection's own thread.
    //
    // Its timeout is deliberately shorter than native-bridge.js's own 5s call
    // timeout, so a page that never answers (JS busy-looping, a WebView that
    // never got a document) comes back as a real error naming the function,
    // rather than as the caller's generic timeout.
    private static final long EVAL_WEBVIEW_TIMEOUT_MS = 4000;

    private String evalWebViewSync(final int id, final String js) throws Exception {
        final String[] resultHolder = new String[1];
        final boolean[] found = { false };
        final CountDownLatch latch = new CountDownLatch(1);
        runOnMainThreadSync(new Callable<Object>() {
            @Override public Object call() {
                WebView target = resolveWebView(id);
                if (target == null) {
                    latch.countDown();
                    return null;
                }
                found[0] = true;
                target.evaluateJavascript(js, new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        resultHolder[0] = value;
                        latch.countDown();
                    }
                });
                return null;
            }
        });

        if (!found[0]) return JSONObject.quote("error: no such WebView: " + id);
        if (!latch.await(EVAL_WEBVIEW_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return JSONObject.quote(
                "error: evalWebView timed out after " + EVAL_WEBVIEW_TIMEOUT_MS + "ms");
        }

        // evaluateJavascript already hands back JSON text -- "null" when the
        // value has no JSON representation (undefined, a function, a thrown
        // exception) -- and JSON text is exactly what this protocol's
        // response body is, so it goes back verbatim. The caller therefore
        // gets the real value, not a string containing it.
        return resultHolder[0] != null ? resultHolder[0] : "null";
    }

    // A bare scheme:// prefix (http, https, mailto, market, ...) is handed
    // straight to ACTION_VIEW for whatever app claims it -- same as tapping
    // a link in the WebView (see shouldOverrideUrlLoading above). Anything
    // else is treated as a path and must resolve, after following symlinks/
    // .., to somewhere under Buninu's home; BuninuFileProvider below is the
    // only way to hand another app a readable Uri for a file that otherwise
    // lives in this app's private storage, since a raw file:// Uri triggers
    // FileUriExposedException on modern Android and content:// requires a
    // provider to back it.
    private boolean xdgOpen(String target) throws Exception {
        Uri uri;
        String mimeType = null;
        if (target.matches("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*")) {
            uri = Uri.parse(target);
        } else {
            File homeDir = new File(getApplicationInfo().dataDir, "no_backup").getCanonicalFile();
            // A relative path has to be resolved against Buninu's home, not
            // this process's own cwd -- new File(target) alone would use
            // this Java process's cwd, which has nothing to do with where
            // the Bun side (whose cwd is genuinely home) resolved it from.
            File rawFile = new File(target);
            File file = (rawFile.isAbsolute() ? rawFile : new File(homeDir, target)).getCanonicalFile();
            String homePrefix = homeDir.getPath() + File.separator;
            if (!file.getPath().equals(homeDir.getPath()) && !file.getPath().startsWith(homePrefix)) {
                throw new SecurityException("outside Buninu home: " + target);
            }
            if (!file.isFile()) {
                throw new FileNotFoundException("not a file: " + target);
            }
            int dot = file.getName().lastIndexOf('.');
            String ext = dot >= 0 ? file.getName().substring(dot + 1).toLowerCase() : "";
            String guessed = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            mimeType = guessed != null ? guessed : "application/octet-stream";
            uri = new Uri.Builder()
                .scheme("content")
                .authority(getPackageName() + ".fileprovider")
                .path(file.getAbsolutePath())
                .build();
        }

        final Intent intent = new Intent(Intent.ACTION_VIEW);
        if (mimeType != null) {
            intent.setDataAndType(uri, mimeType);
        } else {
            intent.setData(uri);
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        final boolean[] launched = { false };
        runOnMainThreadSync(new Callable<Object>() {
            @Override public Object call() {
                try {
                    startActivity(intent);
                    launched[0] = true;
                } catch (ActivityNotFoundException e) {
                    launched[0] = false;
                }
                return null;
            }
        });
        return launched[0];
    }

    // Read-only, and only for files under Buninu's home (see xdgOpen) --
    // exported=false in the manifest means only apps we explicitly grant a
    // Uri to (via FLAG_GRANT_READ_URI_PERMISSION on the ACTION_VIEW intent)
    // can reach this at all. Hand-rolled rather than androidx.core's
    // FileProvider since this project has no AndroidX/Gradle dependencies.
    public static class BuninuFileProvider extends ContentProvider {
        @Override public boolean onCreate() {
            return true;
        }

        private File resolve(Uri uri) throws FileNotFoundException {
            String path = uri.getPath();
            if (path == null) {
                throw new FileNotFoundException("no path in uri: " + uri);
            }
            File file;
            File homeDir;
            try {
                file = new File(path).getCanonicalFile();
                homeDir = new File(getContext().getApplicationInfo().dataDir, "no_backup").getCanonicalFile();
            } catch (IOException e) {
                throw new FileNotFoundException(String.valueOf(e.getMessage()));
            }
            String homePrefix = homeDir.getPath() + File.separator;
            if (!file.getPath().equals(homeDir.getPath()) && !file.getPath().startsWith(homePrefix)) {
                throw new FileNotFoundException("outside Buninu home: " + path);
            }
            if (!file.isFile()) {
                throw new FileNotFoundException("not a file: " + path);
            }
            return file;
        }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                                       String[] selectionArgs, String sortOrder) {
            File file;
            try {
                file = resolve(uri);
            } catch (FileNotFoundException e) {
                return null;
            }
            MatrixCursor cursor = new MatrixCursor(
                new String[]{ OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE });
            cursor.addRow(new Object[]{ file.getName(), file.length() });
            return cursor;
        }

        @Override public String getType(Uri uri) {
            String path = uri.getPath();
            int dot = path != null ? path.lastIndexOf('.') : -1;
            String ext = dot >= 0 ? path.substring(dot + 1).toLowerCase() : "";
            String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            return type != null ? type : "application/octet-stream";
        }

        @Override public Uri insert(Uri uri, ContentValues values) {
            throw new UnsupportedOperationException("read-only provider");
        }

        @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException("read-only provider");
        }

        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
            throw new UnsupportedOperationException("read-only provider");
        }

        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (!"r".equals(mode)) {
                throw new SecurityException("read-only provider, mode=" + mode);
            }
            return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
        }
    }

    // Shared by dltext/dlbin. API 29+ goes through MediaStore.Downloads (no
    // permissions needed for files the app itself creates); older versions
    // fall back to writing the public Downloads dir directly, since scoped
    // storage doesn't apply pre-Q.
    private Uri writeToDownloads(String name, byte[] data, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("MediaStore insert failed");
            OutputStream out = getContentResolver().openOutputStream(uri);
            try {
                out.write(data);
            } finally {
                out.close();
            }
            return uri;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        dir.mkdirs();
        File file = new File(dir, name);
        OutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        return Uri.fromFile(file);
    }

    // Exposed to the WebView's own JS realm as window.AndroidSz -- separate
    // from the Bun-side native-bridge unix socket, since this is called
    // directly by page JS (the injected save_to_disk patch above), not by a
    // Bun process. @JavascriptInterface methods run off the main thread,
    // which is fine here since writeToDownloads only does ContentResolver/
    // File IO.
    // static, not an inner class: addJavascriptInterface objects are
    // commonly documented to need a plain public (ideally static) class --
    // a non-static inner class carries an implicit MainActivity.this
    // reference that some WebView/Chromium builds fail to resolve through
    // reflection. Takes the Activity explicitly instead to reach
    // writeToDownloads().
    public static class SzJsBridge {
        private final MainActivity activity;

        SzJsBridge(MainActivity activity) {
            this.activity = activity;
        }

        @JavascriptInterface
        public void saveFile(String name, String base64) {
            try {
                byte[] data = Base64.decode(base64, Base64.DEFAULT);
                activity.writeToDownloads(name, data, "application/octet-stream");
            } catch (Exception e) {
                Log.e("MainActivity", "sz saveFile failed", e);
            }
        }
    }

    // Lazily creates the TextToSpeech engine and blocks (with a bounded
    // timeout, unlike a naive latch.await() with none) until either
    // OnInitListener.onInit() fires or that timeout elapses. This blocking
    // wait only ever happens once per app process, on whichever speak() call
    // gets here first; every later speak() sees ttsReady already true and
    // returns immediately.
    private void ensureTtsReady() throws Exception {
        synchronized (ttsInitLock) {
            if (ttsReady) return;

            final CountDownLatch initLatch = new CountDownLatch(1);
            runOnMainThreadSync(new Callable<Object>() {
                @Override public Object call() {
                    ttsEngine = new TextToSpeech(MainActivity.this, new TextToSpeech.OnInitListener() {
                        @Override public void onInit(int status) {
                            ttsReady = (status == TextToSpeech.SUCCESS);
                            initLatch.countDown();
                        }
                    });
                    ttsEngine.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override public void onStart(String utteranceId) {
                            ttsStatusMap.put(utteranceId, "speaking");
                        }
                        @Override public void onDone(String utteranceId) {
                            ttsStatusMap.put(utteranceId, "done");
                        }
                        @Override public void onError(String utteranceId) {
                            ttsStatusMap.put(utteranceId, "error");
                        }
                    });
                    return null;
                }
            });

            if (!initLatch.await(10, TimeUnit.SECONDS)) {
                throw new Exception("native-bridge: TTS engine init timed out");
            }
            if (!ttsReady) {
                throw new Exception("native-bridge: TTS engine failed to initialize");
            }
        }
    }

    // Runs task on the main looper and blocks the calling (background) thread
    // until it finishes, returning its result or rethrowing whatever it
    // threw. Toast and ClipboardManager calls need this; without it, the
    // per-connection thread would either crash (Toast requires a Looper it
    // does not have) or race the HTTP response against work that has not
    // actually happened yet.
    private Object runOnMainThreadSync(final Callable<Object> task) throws Exception {
        final Object[] resultHolder = new Object[1];
        final Throwable[] errorHolder = new Throwable[1];
        final CountDownLatch latch = new CountDownLatch(1);
        runOnUiThread(new Runnable() {
            @Override public void run() {
                try {
                    resultHolder[0] = task.call();
                } catch (Throwable t) {
                    errorHolder[0] = t;
                } finally {
                    latch.countDown();
                }
            }
        });
        latch.await();
        if (errorHolder[0] != null) {
            throw new Exception(errorHolder[0]);
        }
        return resultHolder[0];
    }

    /**
     * Keep the APK's buninu.tgz synchronized with the private cache and unpack
     * its no_backup/ tree into applicationInfo.dataDir. The build script bakes
     * the asset's SHA-256, timestamp and size into this class, so an unchanged
     * launch only reads the small stamp file.
     */
    private File ensureBuninuPayload() throws Exception {
        File dataDir = new File(getApplicationInfo().dataDir);
        File home = new File(dataDir, "no_backup");
        File archive = new File(getCacheDir(), PAYLOAD_ASSET);
        File stamp = new File(getCacheDir(), PAYLOAD_STAMP);
        String wanted = readAssetText(PAYLOAD_STAMP).trim();
        String[] stampParts = wanted.split(":", -1);
        if (stampParts.length != 3 || !stampParts[0].matches("[0-9a-fA-F]{64}")) {
            throw new IOException("invalid " + PAYLOAD_STAMP + " asset");
        }
        String payloadSha256 = stampParts[0].toLowerCase();
        long payloadMtime = Long.parseLong(stampParts[1]);
        long payloadSize = Long.parseLong(stampParts[2]);

        if (new File(home, "bin/init.js").isFile() && wanted.equals(readText(stamp).trim())) {
            Log.d("BunPayload", "buninu payload is current");
            linkNativeLibraries(home);
            return home;
        }

        boolean archiveCurrent = archive.isFile()
            && archive.length() == payloadSize
            && archive.lastModified() / 1000L == payloadMtime
            && payloadSha256.equals(sha256(archive));

        if (!archiveCurrent) {
            stamp.delete();
            File part = new File(getCacheDir(), PAYLOAD_ASSET + ".part");
            copyAsset(PAYLOAD_ASSET, part);
            String copiedHash = sha256(part);
            if (part.length() != payloadSize || !payloadSha256.equals(copiedHash)) {
                part.delete();
                throw new IOException("buninu.tgz verification failed: " + copiedHash);
            }
            part.setLastModified(payloadMtime * 1000L);
            if (!part.renameTo(archive)) {
                copyFile(part, archive);
                part.delete();
                archive.setLastModified(payloadMtime * 1000L);
            }
            Log.d("BunPayload", "copied " + PAYLOAD_ASSET + " to " + archive);
        }

        try {
            validateSingleArchiveRoot(archive);
        } catch (Exception e) {
            Log.w("BunPayload", "payload validation failed; starting existing Buninu home", e);
            writeErrorToTmp("payload validation failed; starting existing Buninu home", e);
            stamp.delete();
            if (new File(home, "bin/init.js").isFile()) {
                linkNativeLibraries(home);
                return home;
            }
            showArchiveStructureError();
            throw e;
        }
        if (!home.isDirectory() && !home.mkdirs()) {
            throw new IOException("cannot create Buninu home: " + home);
        }

        // Remove links whose existing targets are outside the extraction root. Android's
        // toybox tar rejects archive entries that resolve outside -C.
        new File(home, "bin/androidNativeLibs").delete();
        new File(home, "bin/bun").delete();
        new File(home, "bin/shloader").delete();

        int status = extractTarGz(archive, home);
        if (status != 0) {
            Log.w("BunPayload", "tar exited with status " + status + "; starting available Buninu home");
            writeErrorToTmp("tar exited with status " + status + "; starting available Buninu home", null);
        }
        if (!new File(home, "bin/init.js").isFile()) {
            throw new IOException("payload extracted without no_backup/bin/init.js");
        }

        linkNativeLibraries(home);
        writeText(stamp, wanted);
        Log.d("BunPayload", "processed Buninu home to " + home + " (tar status " + status + ")");
        return home;
    }

    /** Require one common wrapper directory before extracting with --strip-components=1. */
    private void validateSingleArchiveRoot(File archive) throws Exception {
        File systemTar = new File("/system/bin/tar");
        ProcessBuilder pb = systemTar.canExecute()
            ? new ProcessBuilder(systemTar.getAbsolutePath(), "-tzf", archive.getAbsolutePath())
            : new ProcessBuilder("/system/bin/toybox", "tar", "-tzf", archive.getAbsolutePath());
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        java.lang.Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String root = null;
        String line;
        while ((line = reader.readLine()) != null) {
            String path = line;
            while (path.startsWith("./")) path = path.substring(2);
            int separator = path.indexOf('/');
            String entryRoot = separator >= 0 ? path.substring(0, separator) : path;
            if (entryRoot.isEmpty()) {
                process.destroy();
                throw new IOException("archive entry has no top-level directory: " + line);
            }
            if (root == null) root = entryRoot;
            else if (!root.equals(entryRoot)) {
                process.destroy();
                throw new IOException("archive has multiple top-level entries: " + root + ", " + entryRoot);
            }
        }
        int status = process.waitFor();
        if (status != 0) {
            Log.w("BunPayload", "tar listing exited with status " + status + "; continuing from listed entries");
            writeErrorToTmp("tar listing exited with status " + status + "; continuing from listed entries", null);
        }
        if (root == null) throw new IOException("payload archive is empty");
    }

    private void linkNativeLibraries(File home) throws Exception {
        File bin = new File(home, "bin");
        if (!bin.isDirectory() && !bin.mkdirs()) {
            throw new IOException("cannot create Buninu bin directory: " + bin);
        }
        File link = new File(bin, "androidNativeLibs");
        String target = getApplicationInfo().nativeLibraryDir;
        String current = null;
        try { current = Os.readlink(link.getAbsolutePath()); } catch (Exception ignored) {}
        if (!target.equals(current)) {
            link.delete();
            Os.symlink(target, link.getAbsolutePath());
        }
    }

    private int extractTarGz(File archive, File destination) throws Exception {
        File systemTar = new File("/system/bin/tar");
        ProcessBuilder pb;
        if (systemTar.canExecute()) {
            pb = new ProcessBuilder(systemTar.getAbsolutePath(), "-oxzf",
                archive.getAbsolutePath(), "--strip-components=1", "-C", destination.getAbsolutePath());
        } else {
            pb = new ProcessBuilder("/system/bin/toybox", "tar", "-oxzf",
                archive.getAbsolutePath(), "--strip-components=1", "-C", destination.getAbsolutePath());
        }
        pb.redirectErrorStream(true);
        java.lang.Process process = pb.start();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while ((line = reader.readLine()) != null) Log.d("BunTar", line);
        return process.waitFor();
    }

    private void copyAsset(String name, File destination) throws Exception {
        InputStream input = getAssets().open(name);
        try {
            OutputStream output = new FileOutputStream(destination);
            try { copy(input, output); } finally { output.close(); }
        } finally { input.close(); }
    }

    private String readAssetText(String name) throws Exception {
        InputStream input = getAssets().open(name);
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            copy(input, output);
            return output.toString("UTF-8");
        } finally { input.close(); }
    }

    private static void copyFile(File source, File destination) throws Exception {
        InputStream input = new FileInputStream(source);
        try {
            OutputStream output = new FileOutputStream(destination);
            try { copy(input, output); } finally { output.close(); }
        } finally { input.close(); }
    }

    private static void copy(InputStream input, OutputStream output) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        int count;
        while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        InputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        } finally { input.close(); }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    // Deliberately incapable of failing: a missing or unreadable file,
    // malformed JSON, no "buninu" object, no "backToConsole" key, or a value
    // that is not a boolean all leave the default (true -- back returns to
    // the console) exactly as it was. This runs on the Bun-startup thread and
    // its result is read from the main thread on a back press, so an
    // exception escaping here would either kill that thread partway through
    // startup or, worse, surface as a crash on a key press; there is no
    // failure mode worth either. The size cap keeps a package.json that is
    // somehow enormous (or a path that is not really a package.json at all)
    // from being pulled into memory in one piece.
    private static final int PACKAGE_JSON_MAX_BYTES = 1 << 20;

    private void readBackToConsole(File home) {
        try {
            File config = new File(home, "package.json");
            if (!config.isFile() || config.length() > PACKAGE_JSON_MAX_BYTES) return;

            byte[] buffer = new byte[(int) config.length()];
            FileInputStream input = new FileInputStream(config);
            int read = 0;
            try {
                while (read < buffer.length) {
                    int n = input.read(buffer, read, buffer.length - read);
                    if (n < 0) break;
                    read += n;
                }
            } finally { input.close(); }

            JSONObject buninu = new JSONObject(new String(buffer, 0, read, "UTF-8"))
                .optJSONObject("buninu");
            if (buninu == null || buninu.isNull("backToConsole")) return;
            backToConsole = buninu.optBoolean("backToConsole", true);
        } catch (Throwable ignored) {
            // Keep the default. Not even logged: a config file that does not
            // say anything about this is the normal case, not an error.
        }
    }

    private String readText(File file) {
        if (!file.isFile()) return "";
        try {
            BufferedReader reader = new BufferedReader(new FileReader(file));
            try {
                String line = reader.readLine();
                return line == null ? "" : line;
            } finally { reader.close(); }
        } catch (Exception error) {
            writeErrorToTmp("cannot read " + file, error);
            return "";
        }
    }

    private void writeErrorToTmp(String context, Throwable error) {
        StringBuilder entry = new StringBuilder();
        entry.append("[").append(new java.util.Date()).append("] ").append(context).append("\n");
        if (error != null) entry.append(Log.getStackTraceString(error));
        entry.append("\n");

        appendErrorLog(new File(getCacheDir(), "buninu-error.log"), entry.toString());

        try {
            File[] mediaDirs = getExternalMediaDirs();
            if (mediaDirs != null && mediaDirs.length > 0 && mediaDirs[0] != null) {
                appendErrorLog(new File(mediaDirs[0], "buninu-error.log"), entry.toString());
            }
        } catch (Exception mediaError) {
            Log.e("BunExec", "Cannot access external media directory", mediaError);
        }
    }

    private void appendErrorLog(File logFile, String entry) {
        try {
            File parent = logFile.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("cannot create log directory: " + parent);
            }
            FileWriter writer = new FileWriter(logFile, true);
            try { writer.write(entry); } finally { writer.close(); }
        } catch (Exception logError) {
            Log.e("BunExec", "Cannot write error log: " + logFile, logError);
        }
    }

    private void showArchiveStructureError() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                WebView console = webViews.get(Integer.valueOf(CONSOLE_WEBVIEW_ID));
                if (console == null) return;
                String html = "<!doctype html><meta charset=\"utf-8\">" +
                    "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                    "<body style=\"margin:0;padding:32px;background:#111;color:#eee;" +
                    "font-family:sans-serif;line-height:1.6\">" +
                    "<h1 style=\"color:#ff6b6b\">Buninu payload 錯誤 / Error</h1>" +
                    "<p><strong>buninu.tgz 必須只有單一頂層資料夾。</strong></p>" +
                    "<p><strong>buninu.tgz must contain exactly one top-level directory.</strong></p>" +
                    "<p>解壓時會移除第一層，再將內容放入 Buninu home。<br>" +
                    "The first component is stripped before extraction into the Buninu home.</p>" +
                    "</body>";
                console.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            }
        });
    }

    private static void writeText(File file, String text) throws Exception {
        FileWriter writer = new FileWriter(file, false);
        try { writer.write(text); writer.write("\n"); } finally { writer.close(); }
    }

    private String extractUrl(String line) {
        int start = line.indexOf("http://");
        if (start == -1) start = line.indexOf("https://");
        if (start == -1) return line.trim();
        int end = line.length();
        for (int i = start; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c) || c == '\'' || c == '"') {
                end = i;
                break;
            }
        }
        return line.substring(start, end);
    }

    // What a back press should do, independent of which of the two ways
    // Android delivered it (see registerBackHandler). Returns true when it was
    // handled here, false to mean "let the platform do its usual thing".
    //
    // Back out of the app WebView is a switch, not a close: it stays loaded
    // and running behind the console, exactly as the volume menu's Switch
    // WebView would leave it. Only the console with nothing left to go back to
    // gives the press back to the platform -- as does the app WebView too when
    // buninu.backToConsole is false, which is how an app that wants back to
    // mean "leave" gets it.
    private boolean handleBack() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        if (backToConsole
            && currentWebViewId != CONSOLE_WEBVIEW_ID
            && webViews.containsKey(Integer.valueOf(CONSOLE_WEBVIEW_ID))) {
            showWebView(CONSOLE_WEBVIEW_ID);
            return true;
        }
        return false;
    }

    // Android 13 (API 33) introduced OnBackInvokedCallback, and from Android
    // 15 on it is not optional for an app like this one: predictive back
    // defaults to *enabled* for anything targeting API 35+ (this app targets
    // 36, see AndroidManifest.xml), and once it is enabled the system stops
    // calling Activity.onBackPressed() entirely and dispatches here instead.
    // Without this registration every back press on a modern device just
    // finishes the Activity -- no WebView history, no switch back to the
    // console -- because the override below is never reached at all.
    //
    // API 32 and older have no dispatcher and keep using onBackPressed.
    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            BackInvokedRegistrar.register(this);
        }
    }

    // Kept in its own class so the API 33 types it names are only resolved on
    // a device that actually has them: ART resolves a class's references when
    // that class is first used, and minSdkVersion here is 26. A version check
    // around an inline registration would still drag them into MainActivity's
    // own constant pool.
    private static class BackInvokedRegistrar {
        static void register(final MainActivity activity) {
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new OnBackInvokedCallback() {
                    @Override public void onBackInvoked() {
                        // Registering a callback means nothing else handles
                        // the press any more, so the unhandled case has to
                        // deal with leaving itself -- there is no
                        // super.onBackPressed() to fall through to here.
                        if (!activity.handleBack()) activity.confirmExit();
                    }
                });
        }
    }

    @Override public void onBackPressed() {
        if (!handleBack()) confirmExit();
    }

    // Leaving is confirmed rather than immediate, because leaving is not free
    // any more: onDestroy tears down the Buninu process and everything running
    // inside it (see there for why it has to). A back gesture at the wrong
    // moment should not silently end a long-running shell.
    //
    // The flag guards against stacking dialogs -- back is easy to press twice,
    // and the second press would otherwise put a second copy on top of the
    // first.
    private boolean exitDialogShowing;

    private void confirmExit() {
        if (exitDialogShowing) return;
        exitDialogShowing = true;
        new AlertDialog.Builder(this)
            .setTitle("Exit 離開")
            .setMessage("Leaving stops Buninu and everything running in it.\n"
                + "離開會結束 Buninu 與裡面正在執行的一切。")
            .setPositiveButton("Exit 離開", new DialogInterface.OnClickListener() {
                @Override public void onClick(DialogInterface dialog, int which) {
                    finish();
                }
            })
            .setNegativeButton("Cancel 取消", null)
            .setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override public void onDismiss(DialogInterface dialog) {
                    exitDialogShowing = false;
                }
            })
            .show();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        // Unconditional, not only when isFinishing(): anything still bound or
        // running when this Activity goes away is what the *next* Activity in
        // this process trips over, and that is true however the Activity came
        // to be destroyed. A recreate then costs a fresh Buninu process, which
        // is a visible, self-healing outcome -- unlike a stale bridge, which
        // is silent.
        closeBridgeServer();
        stopBunProcess();

        if (isFinishing()) {
            // The user chose to leave. Threads blocked in accept() and
            // readLine(), plus the WebViews, would otherwise keep this process
            // alive as a cached process -- which is exactly the state that
            // made a relaunch come back without a working bridge. Ending the
            // process is what makes "leaving" actually mean it.
            System.exit(0);
        }
    }

    private static void closeBridgeServer() {
        LocalServerSocket server = bridgeServer;
        bridgeServer = null;
        if (server == null) return;
        try {
            // Closing also unblocks the accept loop, which logs "accept loop
            // stopped" and ends its thread.
            server.close();
        } catch (IOException e) {
            Log.w("NativeBridge", "closing native bridge failed", e);
        }
    }

    private static void stopBunProcess() {
        java.lang.Process process = bunProcess;
        bunProcess = null;
        if (process == null) return;
        // destroy() only reaches Bun itself. Everything Bun spawned (jsgotty,
        // the shell) goes with it because Bun was started with --no-orphans;
        // see startBunProcess.
        process.destroy();
    }

    @Override protected void onPause() {
        super.onPause();
        // 不呼叫 webView.onPause()，使 WebView 在 App 切到背景時仍繼續運行
    }

    @Override protected void onResume() {
        super.onResume();
        // Every WebView, not just the front one: a background WebView is
        // still running (that is what makes openWebView a background load),
        // so it is resumed on the same terms as the one on screen.
        for (WebView view : webViews.values()) view.onResume();
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (filePathCallback == null) return;

        Uri[] results = null;
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                results = new Uri[count];
                for (int i = 0; i < count; i++) {
                    results[i] = data.getClipData().getItemAt(i).getUri();
                }
            } else if (data.getData() != null) {
                results = new Uri[]{data.getData()};
            }
        }
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    // ------------------------------------------------ extra-keys control bar

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void toggleExtraKeys() {
        boolean showing = extraKeysBar.getVisibility() != View.VISIBLE;
        if (!showing) {
            stopKeyRepeat();
            imeReceiver.setText("");
            keyModifiers.clear();
            updateModifierButtonStyles();
        }
        extraKeysBar.setVisibility(showing ? View.VISIBLE : View.GONE);
        if (!showing) focusWebView();
    }

    private void clearKeyModifiers() {
        imeReceiver.setText("");
        keyModifiers.clear();
        updateModifierButtonStyles();
    }

    private void toggleKeyModifier(String modifier) {
        if (!keyModifiers.add(modifier)) keyModifiers.remove(modifier);
        updateModifierButtonStyles();
        focusImeReceiver();
    }

    private void styleModifierButton(Button b, boolean selected) {
        b.setBackgroundColor(selected ? 0xffffc107 : 0xff303030);
        b.setTextColor(selected ? 0xff000000 : 0xffffffff);
    }

    private void updateModifierButtonStyles() {
        styleModifierButton(ctrlButton, keyModifiers.contains("CTRL"));
        styleModifierButton(altButton, keyModifiers.contains("ALT"));
        styleModifierButton(shiftButton, keyModifiers.contains("SHIFT"));
        imeReceiver.setHintTextColor(keyModifiers.isEmpty() ? 0x8affffff : 0xffffc107);
    }

    private void focusImeReceiver() {
        imeReceiver.post(new Runnable() {
            @Override public void run() {
                imeReceiver.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(imeReceiver, InputMethodManager.SHOW_IMPLICIT);
            }
        });
    }

    private boolean focusWebView() {
        if (webView == null) return false;
        webView.requestFocus();
        webView.post(new Runnable() {
            @Override public void run() {
                webView.requestFocus();
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        return true;
    }

    // Fires once immediately, then again every KEY_REPEAT_MS until stopKeyRepeat().
    // Modifiers are snapshotted once at the start, matching a held chord like
    // CTRL+held-arrow rather than re-reading (and re-clearing) state per tick.
    private void startKeyRepeat(final int keyCode) {
        stopKeyRepeat();
        final List<String> modifiers = new ArrayList<>(keyModifiers);
        keyModifiers.clear();
        updateModifierButtonStyles();
        sendWebKey(keyCode, modifiers);
        keyRepeatRunnable = new Runnable() {
            @Override public void run() {
                sendWebKey(keyCode, modifiers);
                keyRepeatHandler.postDelayed(this, KEY_REPEAT_MS);
            }
        };
        keyRepeatHandler.postDelayed(keyRepeatRunnable, KEY_REPEAT_MS);
    }

    private void stopKeyRepeat() {
        if (keyRepeatRunnable != null) {
            keyRepeatHandler.removeCallbacks(keyRepeatRunnable);
            keyRepeatRunnable = null;
        }
    }

    // Same periodic-trigger shape as startKeyRepeat, but dispatches a mouse
    // wheel tick instead of a key each time. Shares stopKeyRepeat() since it
    // only ever cancels whatever runnable is currently armed.
    private void startScrollRepeat(final float vscroll) {
        stopKeyRepeat();
        dispatchScroll(vscroll);
        keyRepeatRunnable = new Runnable() {
            @Override public void run() {
                dispatchScroll(vscroll);
                keyRepeatHandler.postDelayed(this, KEY_REPEAT_MS);
            }
        };
        keyRepeatHandler.postDelayed(keyRepeatRunnable, KEY_REPEAT_MS);
    }

    // Synthesizes one mouse-wheel notch (ACTION_SCROLL / AXIS_VSCROLL) so
    // Chromium generates a real wheel event — this is what lets xterm.js (and
    // anything else that only listens for wheel, not PageUp/PageDown) scroll
    // its own buffer. Positive vscroll scrolls up (reveals earlier content),
    // matching AXIS_VSCROLL's convention (and RecyclerView's use of it).
    private void dispatchScroll(float vscroll) {
        if (webView == null || webView.getWidth() == 0 || webView.getHeight() == 0) return;

        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[1];
        props[0] = new MotionEvent.PointerProperties();
        props[0].id = 0;
        props[0].toolType = MotionEvent.TOOL_TYPE_MOUSE;

        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].x = webView.getWidth() / 2f;
        coords[0].y = webView.getHeight() / 2f;
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vscroll);

        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        webView.dispatchGenericMotionEvent(event);
        event.recycle();
    }

    // Termux-style one-shot modifiers: armed modifiers apply to the next key
    // (here, plus any modifier baked into the button itself, e.g. CTRL for ^C)
    // and are then cleared, so CTRL then C/F/V works without multi-touch.
    private void sendNativeKey(int keyCode, Set<String> extraModifiers) {
        List<String> modifiers = new ArrayList<>(keyModifiers);
        for (String m : extraModifiers) if (!modifiers.contains(m)) modifiers.add(m);
        keyModifiers.clear();
        updateModifierButtonStyles();
        sendWebKey(keyCode, modifiers);
    }

    private void sendImeText(String text) {
        if (text.isEmpty()) return;
        List<String> modifiers = new ArrayList<>(keyModifiers);
        imeReceiver.setText("");
        keyModifiers.clear();
        updateModifierButtonStyles();
        sendWebTextKeys(text, modifiers);
    }

    /**
     * Deliver a real Android keyboard event sequence to the WebView. This
     * deliberately does not run JavaScript or construct a DOM KeyboardEvent:
     * Chromium receives native KeyEvents and creates the DOM events itself, so
     * event.isTrusted stays true.
     */
    private boolean sendWebKey(int keyCode, List<String> modifiers) {
        return sendWebKey(keyCode, modifiers, 0);
    }

    private boolean sendWebKey(int keyCode, List<String> modifiers, int generatedMetaState) {
        if (webView == null) return false;
        webView.requestFocus();

        int meta = generatedMetaState;
        List<Integer> modifierCodes = new ArrayList<>();
        if (containsIgnoreCase(modifiers, "CTRL")) {
            modifierCodes.add(KeyEvent.KEYCODE_CTRL_LEFT);
            meta |= KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
        }
        if (containsIgnoreCase(modifiers, "ALT")) {
            modifierCodes.add(KeyEvent.KEYCODE_ALT_LEFT);
            meta |= KeyEvent.META_ALT_ON | KeyEvent.META_ALT_LEFT_ON;
        }
        if (containsIgnoreCase(modifiers, "SHIFT")) {
            modifierCodes.add(KeyEvent.KEYCODE_SHIFT_LEFT);
            meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        }
        meta = KeyEvent.normalizeMetaState(meta);

        long downTime = SystemClock.uptimeMillis();
        for (int code : modifierCodes) {
            webView.dispatchKeyEvent(makeKeyEvent(KeyEvent.ACTION_DOWN, code, meta, downTime));
        }
        webView.dispatchKeyEvent(makeKeyEvent(KeyEvent.ACTION_DOWN, keyCode, meta, downTime));
        webView.dispatchKeyEvent(makeKeyEvent(KeyEvent.ACTION_UP, keyCode, meta, downTime));
        for (int i = modifierCodes.size() - 1; i >= 0; i--) {
            webView.dispatchKeyEvent(makeKeyEvent(KeyEvent.ACTION_UP, modifierCodes.get(i), meta, downTime));
        }
        // The IME receiver may hold input focus while capturing a chord; give
        // focus straight back to the WebView and keep the soft keyboard up.
        focusWebView();
        return true;
    }

    private KeyEvent makeKeyEvent(int action, int code, int meta, long downTime) {
        return new KeyEvent(
            downTime,
            SystemClock.uptimeMillis(),
            action,
            code,
            0,
            meta,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE,
            InputDevice.SOURCE_KEYBOARD);
    }

    private boolean containsIgnoreCase(List<String> list, String value) {
        for (String s : list) if (s.equalsIgnoreCase(value)) return true;
        return false;
    }

    // Converts typed/IME text into native key events via the same character
    // map Android uses for a virtual keyboard, so punctuation and uppercase
    // letters reproduce the Shift/Alt state that generated them.
    private boolean sendWebTextKeys(String text, List<String> modifiers) {
        KeyCharacterMap keyMap = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
        boolean sentAny = false;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            char[] chars = Character.toChars(codePoint);
            KeyEvent[] events = keyMap.getEvents(chars);
            if (events == null) continue;
            KeyEvent generated = null;
            for (KeyEvent e : events) {
                if (e.getAction() == KeyEvent.ACTION_DOWN && !KeyEvent.isModifierKey(e.getKeyCode())) {
                    generated = e;
                    break;
                }
            }
            if (generated == null) continue;
            sentAny = sendWebKey(generated.getKeyCode(), modifiers, generated.getMetaState()) || sentAny;
        }
        return sentAny;
    }

    // ------------------------------------------------ extra-keys UI (views only)

    private Button makeModifierButton(String label, final String modifier) {
        return makeModifierButton(label, modifier, null);
    }

    private Button makeModifierButton(String label, final String modifier, final Runnable onLongPress) {
        Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setPadding(dp(12), 0, dp(12), 0);
        btn.setMinWidth(dp(48));
        btn.setMinHeight(dp(34));
        styleModifierButton(btn, false);
        btn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggleKeyModifier(modifier); }
        });
        if (onLongPress != null) {
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { onLongPress.run(); return true; }
            });
        }
        return btn;
    }

    private Button makeKeyButton(String label, final Runnable onPress, final Runnable onLongPress) {
        final Button btn = new Button(this);
        btn.setText(label);
        btn.setTextSize(12);
        btn.setAllCaps(false);
        btn.setBackgroundColor(0xff303030);
        btn.setTextColor(0xffffffff);
        btn.setPadding(dp(12), 0, dp(12), 0);
        btn.setMinWidth(dp(48));
        btn.setMinHeight(dp(34));
        if (onPress != null) {
            btn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onPress.run(); }
            });
        }
        if (onLongPress != null) {
            btn.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) { onLongPress.run(); return true; }
            });
        }
        // Key-repeat buttons arm a Handler loop on long-press; release anywhere
        // on the button (up or cancel) must stop it, independent of click/long-click.
        btn.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    stopKeyRepeat();
                }
                return false;
            }
        });
        return btn;
    }

    private Button makeRepeatButton(String label, final int keyCode) {
        return makeKeyButton(
            label,
            new Runnable() { @Override public void run() { sendNativeKey(keyCode, NO_EXTRA_MODIFIERS); } },
            new Runnable() { @Override public void run() { startKeyRepeat(keyCode); } });
    }

    private void addSpaced(LinearLayout row, View... views) {
        for (int i = 0; i < views.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            if (i > 0) lp.leftMargin = dp(2);
            row.addView(views[i], lp);
        }
    }

    private HorizontalScrollView wrapScroll(LinearLayout row) {
        HorizontalScrollView sv = new HorizontalScrollView(this);
        sv.setHorizontalScrollBarEnabled(false);
        sv.addView(row, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, dp(36)));
        return sv;
    }

    private EditText makeImeReceiver() {
        EditText et = new EditText(this);
        et.setGravity(Gravity.CENTER);
        et.setTextColor(0xffffffff);
        et.setTextSize(9);
        et.setSingleLine(true);
        et.setHint("│");
        et.setHintTextColor(0x8affffff);
        et.setPadding(0, 0, 0, 0);
        et.setBackgroundColor(0xff242424);
        et.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        et.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { sendImeText(s.toString()); }
        });
        return et;
    }

    private FrameLayout buildExtraKeysBar() {
        ctrlButton = makeModifierButton("CTRL", "CTRL");
        altButton = makeModifierButton("ALT", "ALT");
        shiftButton = makeModifierButton("SHFT", "SHIFT",
            new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_D, CTRL_MODIFIER); } });

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(dp(2), dp(1), dp(34), dp(1));
        addSpaced(row1,
            makeKeyButton("ESC",
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_ESCAPE, NO_EXTRA_MODIFIERS); } },
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_Q, CTRL_MODIFIER); } }),
            shiftButton,
            makeKeyButton("^C x",
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_C, CTRL_MODIFIER); } },
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_X, CTRL_MODIFIER); } }),
            makeKeyButton("HOME",
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_MOVE_HOME, NO_EXTRA_MODIFIERS); } },
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_U, CTRL_MODIFIER); } }),
            makeRepeatButton("↑", KeyEvent.KEYCODE_DPAD_UP),
            makeKeyButton("END",
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_MOVE_END, NO_EXTRA_MODIFIERS); } },
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_K, CTRL_MODIFIER); } }),
            makeKeyButton("PGU",
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_PAGE_UP, NO_EXTRA_MODIFIERS); } },
                new Runnable() { @Override public void run() { startScrollRepeat(1f); } }));

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(dp(2), dp(1), dp(56), dp(1));
        addSpaced(row2,
            makeKeyButton("TAB",
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_TAB, NO_EXTRA_MODIFIERS); } },
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_TAB, SHIFT_MODIFIER); } }),
            ctrlButton,
            altButton,
            makeRepeatButton("←", KeyEvent.KEYCODE_DPAD_LEFT),
            makeRepeatButton("↓", KeyEvent.KEYCODE_DPAD_DOWN),
            makeRepeatButton("→", KeyEvent.KEYCODE_DPAD_RIGHT),
            makeKeyButton("PGD",
                new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_PAGE_DOWN, NO_EXTRA_MODIFIERS); } },
                new Runnable() { @Override public void run() { startScrollRepeat(-1f); } }));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(wrapScroll(row1));
        column.addView(wrapScroll(row2));

        imeReceiver = makeImeReceiver();
        FrameLayout.LayoutParams imeParams = new FrameLayout.LayoutParams(dp(30), dp(34));
        imeParams.gravity = Gravity.TOP | Gravity.END;
        imeParams.setMargins(0, dp(1), dp(2), 0);

        Button enterButton = makeKeyButton("Ent",
            new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_ENTER, NO_EXTRA_MODIFIERS); } },
            new Runnable() { @Override public void run() { sendNativeKey(KeyEvent.KEYCODE_FORWARD_DEL, NO_EXTRA_MODIFIERS); } });
        FrameLayout.LayoutParams enterParams = new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        enterParams.gravity = Gravity.BOTTOM | Gravity.END;
        enterParams.setMargins(0, 0, dp(2), dp(1));

        FrameLayout bar = new FrameLayout(this);
        bar.setBackgroundColor(0xff181818);
        bar.addView(column, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        bar.addView(imeReceiver, imeParams);
        bar.addView(enterButton, enterParams);
        return bar;
    }

    static class V extends SurfaceView implements SurfaceHolder.Callback {
        static final int W = 10, H = 20;
        static final short[] SH = {0x00F0, 0x0660, 0x04E0, 0x08C4, 0x04C8, 0x06C0, 0x0C60};
        static final int[] COL = {0xFF00F5FF, 0xFFFFE000, 0xFFBF00FF, 0xFFFF8C00, 0xFF0050FF, 0xFF00E060, 0xFFFF1744};

        byte[] brd = new byte[W * H];
        short cur, nxt;
        int cx, cy, ct, nt;
        int sc, ln, lv = 1, cmb;
        boolean over;
        int lc;

        int st; // 0 = Start, 1 = Playing, 2 = Pause, 3 = GameOver
        int best;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF();
        float cs, bx, by, px;
        int cw, ch;
        LinearGradient bg;

        float startX, startY, lastX, lastY;
        float accumDx, accumDy;
        long touchTime;

        float[] ppx = new float[50], ppy = new float[50];
        float[] pvx = new float[50], pvy = new float[50], plf = new float[50];
        int[] pcl = new int[50];
        int pc;

        AudioTrack trRot, trDrp, trLn, trGo;

        Thread gt;
        volatile boolean run;
        long ld, di = 800;

        Random rng = new Random();

        String cmdOutput = "";

        V(Context c) {
            super(c);
            getHolder().addCallback(this);
            setFocusable(true);
            best = c.getSharedPreferences("t", 0).getInt("b", 0);
            initAudio();
            execProcessBuilder();
            initGame();
        }

        void execProcessBuilder() {
            try {
                java.lang.Process process = new ProcessBuilder("sh", "-c", "ls $TMPDIR/..").redirectErrorStream(true).start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                process.waitFor();
                cmdOutput = sb.toString().trim();
                if (cmdOutput.isEmpty()) {
                    cmdOutput = "(Empty output)";
                }
            } catch (Exception e) {
                cmdOutput = "Error: " + e.getMessage();
            }
        }

        void initAudio() {
            try {
                AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                AudioFormat af = new AudioFormat.Builder()
                    .setSampleRate(22050)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();

                short[] bRot = tone(880, 50, 0.4f);
                short[] bDrp = sweep(300, 100, 100, 0.5f);
                short[] bLn  = sweep(400, 900, 200, 0.5f);
                short[] bGo  = sweep(600, 100, 600, 0.5f);

                trRot = buildTrack(aa, af, bRot);
                trDrp = buildTrack(aa, af, bDrp);
                trLn  = buildTrack(aa, af, bLn);
                trGo  = buildTrack(aa, af, bGo);
            } catch (Exception ignored) {}
        }

        AudioTrack buildTrack(AudioAttributes aa, AudioFormat af, short[] b) {
            try {
                AudioTrack t = new AudioTrack.Builder()
                    .setAudioAttributes(aa).setAudioFormat(af)
                    .setBufferSizeInBytes(b.length * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC).build();
                t.write(b, 0, b.length);
                return t;
            } catch (Exception e) { return null; }
        }

        short[] tone(float f, int ms, float v) {
            int n = 22050 * ms / 1000;
            short[] b = new short[n];
            for (int i = 0; i < n; i++) {
                float t = (float) i / 22050;
                b[i] = (short) (Math.sin(2 * Math.PI * f * t) * 32000 * v * (1f - (float) i / n));
            }
            return b;
        }

        short[] sweep(float f1, float f2, int ms, float v) {
            int n = 22050 * ms / 1000;
            short[] b = new short[n];
            double ph = 0;
            for (int i = 0; i < n; i++) {
                float t = (float) i / n;
                ph += 2 * Math.PI * (f1 + (f2 - f1) * t) / 22050;
                b[i] = (short) (Math.sin(ph) * 32000 * v * (1f - t));
            }
            return b;
        }

        void snd(AudioTrack t) {
            if (t == null) return;
            try {
                t.stop();
                t.reloadStaticData();
                t.play();
            } catch (Exception ignored) {}
        }

        void initGame() {
            brd = new byte[W * H];
            nt = rng.nextInt(7);
            nxt = SH[nt];
            spawn();
            sc = ln = cmb = 0;
            lv = 1;
            over = false;
            di = 800;
            ld = System.currentTimeMillis();
        }

        void pauseGame() {
            if (st == 1) st = 2;
        }

        void resumeGame() {
            ld = System.currentTimeMillis();
        }

        void spawn() {
            ct = nt;
            cur = SH[ct];
            cx = 3; cy = 0;
            nt = rng.nextInt(7);
            nxt = SH[nt];
            if (!fits(cur, cx, cy)) over = true;
        }

        static boolean bit(short s, int r, int c) {
            return ((s >> (15 - (r * 4 + c))) & 1) == 1;
        }

        boolean fits(short s, int x, int y) {
            for (int rr = 0; rr < 4; rr++)
                for (int cc = 0; cc < 4; cc++)
                    if (bit(s, rr, cc)) {
                        int nx = x + cc, ny = y + rr;
                        if (nx < 0 || nx >= W || ny >= H) return false;
                        if (ny >= 0 && brd[ny * W + nx] != 0) return false;
                    }
            return true;
        }

        short rot(short s) {
            short rs = 0;
            for (int rr = 0; rr < 4; rr++)
                for (int cc = 0; cc < 4; cc++)
                    if (bit(s, rr, cc))
                        rs |= (1 << (15 - (cc * 4 + (3 - rr))));
            return rs;
        }

        boolean mL() { if (fits(cur, cx - 1, cy)) { cx--; return true; } return false; }
        boolean mR() { if (fits(cur, cx + 1, cy)) { cx++; return true; } return false; }
        boolean mD() { if (fits(cur, cx, cy + 1)) { cy++; return true; } lock(); return false; }

        void hDrop() {
            int d = 0;
            while (fits(cur, cx, cy + 1)) { cy++; d++; }
            sc += d * 2;
            lock();
        }

        boolean doRot() {
            short rt = rot(cur);
            int[] k = {0, -1, 1, -2, 2};
            for (int kk : k)
                if (fits(rt, cx + kk, cy)) { cur = rt; cx += kk; return true; }
            return false;
        }

        void lock() {
            for (int rr = 0; rr < 4; rr++)
                for (int cc = 0; cc < 4; cc++)
                    if (bit(cur, rr, cc)) {
                        int ny = cy + rr, nx = cx + cc;
                        if (ny >= 0 && ny < H && nx >= 0 && nx < W)
                            brd[ny * W + nx] = (byte) (ct + 1);
                    }
            lc = clr();
            if (lc > 0) { cmb++; ln += lc; sc += scr(lc) * lv + cmb * 50; }
            else cmb = 0;
            lv = ln / 10 + 1;
            spawn();
        }

        int clr() {
            int cnt = 0;
            for (int rr = H - 1; rr >= 0; rr--) {
                boolean full = true;
                for (int cc = 0; cc < W; cc++) if (brd[rr * W + cc] == 0) { full = false; break; }
                if (full) {
                    System.arraycopy(brd, 0, brd, W, rr * W);
                    for (int cc = 0; cc < W; cc++) brd[cc] = 0;
                    cnt++; rr++;
                }
            }
            return cnt;
        }

        int scr(int n) {
            return n == 1 ? 100 : n == 2 ? 300 : n == 3 ? 500 : n == 4 ? 800 : 0;
        }

        int gY() {
            int gy = cy;
            while (fits(cur, cx, gy + 1)) gy++;
            return gy;
        }

        void spawnP() {
            pc = 0;
            for (int i = 0; i < 40 && pc < 50; i++) {
                ppx[pc] = bx + rng.nextFloat() * cs * W;
                ppy[pc] = by + cs * (H / 2);
                pvx[pc] = (rng.nextFloat() - 0.5f) * 700;
                pvy[pc] = -rng.nextFloat() * 500;
                pcl[pc] = COL[rng.nextInt(7)];
                plf[pc] = 1f;
                pc++;
            }
        }

        void updP(float dt) {
            int al = 0;
            for (int i = 0; i < pc; i++) {
                ppx[i] += pvx[i] * dt;
                ppy[i] += pvy[i] * dt;
                pvy[i] += 1000 * dt;
                plf[i] -= dt * 1.5f;
                if (plf[i] > 0) {
                    ppx[al] = ppx[i]; ppy[al] = ppy[i];
                    pvx[al] = pvx[i]; pvy[al] = pvy[i];
                    pcl[al] = pcl[i]; plf[al] = plf[i];
                    al++;
                }
            }
            pc = al;
        }

        @Override public void surfaceCreated(SurfaceHolder h) {
            run = true;
            ld = System.currentTimeMillis();
            gt = new Thread(new Runnable() {
                @Override public void run() {
                    while (run) {
                        if (st == 1 && !over) {
                            long now = System.currentTimeMillis();
                            if (now - ld > di) {
                                if (!mD()) snd(trDrp);
                                di = Math.max(80, 800 - (lv - 1) * 70);
                                if (lc > 0) { snd(trLn); spawnP(); lc = 0; }
                                ld = now;
                            }
                        } else if (st == 1 && over) {
                            st = 3;
                            snd(trGo);
                            if (sc > best) {
                                best = sc;
                                getContext().getSharedPreferences("t", 0).edit().putInt("b", best).apply();
                            }
                        }
                        updP(0.016f);
                        draw();
                        try { Thread.sleep(16); } catch (Exception ignored) {}
                    }
                }
            });
            gt.start();
        }

        @Override public void surfaceDestroyed(SurfaceHolder h) {
            run = false;
            try { gt.join(); } catch (Exception ignored) {}
            releaseAudio();
        }

        void releaseAudio() {
            releaseTrack(trRot);
            releaseTrack(trDrp);
            releaseTrack(trLn);
            releaseTrack(trGo);
        }

        void releaseTrack(AudioTrack t) {
            if (t != null) {
                try { t.stop(); t.release(); } catch (Exception ignored) {}
            }
        }

        @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { cw = 0; ch = 0; }

        void draw() {
            SurfaceHolder h = getHolder();
            if (!h.getSurface().isValid()) return;
            Canvas c = h.lockCanvas();
            if (c == null) return;
            try {
                int ww = c.getWidth(), hh = c.getHeight();
                if (cw != ww || ch != hh) {
                    cw = ww; ch = hh;
                    cs = Math.min((hh * 0.92f) / H, (ww * 0.92f) / 16f);
                    float totalW = cs * 16f;
                    bx = (ww - totalW) / 2f + cs * 0.2f;
                    by = (hh - cs * H) / 2f;
                    px = bx + cs * 10.4f;
                    bg = new LinearGradient(0, 0, 0, hh, 0xFF0D0D1A, 0xFF1A1A2E, Shader.TileMode.CLAMP);
                }

                p.setShader(bg);
                c.drawRect(0, 0, ww, hh, p);
                p.setShader(null);

                if (st == 0) drawStart(c, ww, hh);
                else {
                    drawGame(c);
                    if (st == 2) drawPause(c, ww, hh);
                    else if (st == 3) drawOver(c, ww, hh);
                }
                drawPart(c);
            } finally { h.unlockCanvasAndPost(c); }
        }

        void drawStart(Canvas c, int w, int h) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(cs * 1.5f);
            p.setColor(0xFF00F5FF);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("TETRIS", w / 2f, cs * 2.5f, p);

            p.setTextSize(cs * 0.45f);
            p.setColor(0xFFFFE000);
            c.drawText("Cmd: ls $TMPDIR/..", w / 2f, cs * 3.8f, p);

            p.setTextSize(cs * 0.35f);
            p.setColor(0xFF00FF88);
            p.setTypeface(Typeface.MONOSPACE);

            String[] lines = cmdOutput.split("\n");
            float startY = cs * 4.5f;
            float lineHeight = cs * 0.45f;
            int maxLines = Math.min(lines.length, 18);
            for (int i = 0; i < maxLines; i++) {
                c.drawText(lines[i], w / 2f, startY + i * lineHeight, p);
            }

            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(cs * 0.5f);
            p.setColor(0xFFAAAAAA);
            if (System.currentTimeMillis() % 1000 < 700) {
                c.drawText("Tap to start!", w / 2f, h - cs * 1.5f, p);
            }
        }

        void drawGame(Canvas c) {
            p.setColor(0xFF111120);
            p.setStyle(Paint.Style.FILL);
            r.set(bx - 2, by - 2, bx + cs * W + 2, by + cs * H + 2);
            c.drawRoundRect(r, 8, 8, p);

            p.setColor(0x18FFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(0.5f);
            for (int rr = 0; rr <= H; rr++) c.drawLine(bx, by + rr * cs, bx + cs * W, by + rr * cs, p);
            for (int cc = 0; cc <= W; cc++) c.drawLine(bx + cc * cs, by, bx + cc * cs, by + cs * H, p);
            p.setStyle(Paint.Style.FILL);

            for (int rr = 0; rr < H; rr++)
                for (int cc = 0; cc < W; cc++) {
                    byte v = brd[rr * W + cc];
                    if (v != 0) drawCell(c, cc, rr, COL[v - 1]);
                }

            int gy = gY();
            if (gy != cy) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1.5f);
                p.setColor(COL[ct]);
                p.setAlpha(60);
                for (int rr = 0; rr < 4; rr++)
                    for (int cc = 0; cc < 4; cc++)
                        if (bit(cur, rr, cc)) {
                            float x = bx + (cx + cc) * cs, y = by + (gy + rr) * cs;
                            float pd = cs * 0.08f;
                            r.set(x + pd, y + pd, x + cs - pd, y + cs - pd);
                            c.drawRoundRect(r, 4, 4, p);
                        }
                p.setAlpha(255);
                p.setStyle(Paint.Style.FILL);
            }

            for (int rr = 0; rr < 4; rr++)
                for (int cc = 0; cc < 4; cc++)
                    if (bit(cur, rr, cc)) drawCell(c, cx + cc, cy + rr, COL[ct]);

            float pw = cs * 5.2f;
            p.setColor(0xDD151525);
            r.set(px, by, px + pw, by + cs * 12.2f);
            c.drawRoundRect(r, 12, 12, p);

            float cx2 = px + pw / 2, y = by + cs * 0.8f;
            drawLbl(c, "NEXT", cx2, y);
            drawPrev(c, nxt, nt, cx2, y + cs * 0.4f);

            y += cs * 3.4f;
            drawSt(c, "SCORE", "" + sc, cx2, y);
            drawSt(c, "LINES", "" + ln, cx2, y + cs * 1.5f);
            drawSt(c, "LEVEL", "" + lv, cx2, y + cs * 3.0f);
            drawSt(c, "BEST", "" + best, cx2, y + cs * 4.5f);

            // Pause Button
            float pbY = by + cs * 10.3f;
            p.setColor(st == 2 ? 0xFF2A2A50 : 0xFF1F1F3D);
            r.set(px + cs * 0.4f, pbY, px + pw - cs * 0.4f, pbY + cs * 1.4f);
            c.drawRoundRect(r, 6, 6, p);
            p.setColor(0xFF00F5FF);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(cs * 0.45f);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(st == 2 ? "RESUME" : "PAUSE", cx2, pbY + cs * 0.88f, p);
        }

        void drawCell(Canvas c, int col, int row, int clr) {
            float x = bx + col * cs, y = by + row * cs, pd = cs * 0.05f;
            r.set(x + pd, y + pd, x + cs - pd, y + cs - pd);
            p.setColor(clr);
            c.drawRoundRect(r, 4, 4, p);
            p.setColor(0x55FFFFFF);
            r.set(x + pd, y + pd, x + cs - pd, y + pd + cs * 0.22f);
            c.drawRoundRect(r, 4, 2, p);
        }

        void drawLbl(Canvas c, String t, float cx2, float y) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(cs * 0.5f);
            p.setColor(0xFF888888);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(t, cx2, y, p);
        }

        void drawPrev(Canvas c, short sh, int tp, float cx2, float cy) {
            int clr = COL[tp];
            float ps = cs * 0.55f;
            int minC = 4, maxC = 0, minR = 4, maxR = 0;
            for (int rr = 0; rr < 4; rr++)
                for (int cc = 0; cc < 4; cc++)
                    if (bit(sh, rr, cc)) {
                        minR = Math.min(minR, rr);
                        maxR = Math.max(maxR, rr);
                        minC = Math.min(minC, cc);
                        maxC = Math.max(maxC, cc);
                    }
            int sw = maxC - minC + 1;
            float ox = cx2 - (sw * ps) / 2f, oy = cy + cs * 0.2f;
            for (int rr = minR; rr <= maxR; rr++)
                for (int cc = minC; cc <= maxC; cc++)
                    if (bit(sh, rr, cc)) {
                        float x = ox + (cc - minC) * ps, y = oy + (rr - minR) * ps, pd = ps * 0.06f;
                        r.set(x + pd, y + pd, x + ps - pd, y + ps - pd);
                        p.setColor(clr);
                        c.drawRoundRect(r, 3, 3, p);
                    }
        }

        void drawSt(Canvas c, String lb, String vl, float cx2, float y) {
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(cs * 0.35f);
            p.setColor(0xFF666666);
            p.setTypeface(Typeface.DEFAULT);
            c.drawText(lb, cx2, y, p);
            p.setTextSize(cs * 0.55f);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText(vl, cx2, y + cs * 0.55f, p);
        }

        void drawPart(Canvas c) {
            for (int i = 0; i < pc; i++) {
                p.setColor(pcl[i]);
                p.setAlpha((int) (plf[i] * 255));
                c.drawCircle(ppx[i], ppy[i], cs * 0.15f * plf[i], p);
            }
            p.setAlpha(255);
        }

        void drawPause(Canvas c, int w, int h) {
            p.setColor(0xBB000000);
            c.drawRect(0, 0, w, h, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(cs * 1.3f);
            p.setColor(0xFF00F5FF);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("PAUSE", w / 2f, h / 2f, p);
            p.setTextSize(cs * 0.5f);
            p.setColor(0xFFAAAAAA);
            c.drawText("Tap to continue", w / 2f, h / 2f + cs * 1.2f, p);
        }

        void drawOver(Canvas c, int w, int h) {
            p.setColor(0xCC000000);
            c.drawRect(0, 0, w, h, p);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(cs * 1.3f);
            p.setColor(0xFFFF1744);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            c.drawText("GAME OVER", w / 2f, h / 2f - cs * 1.5f, p);
            p.setTextSize(cs * 0.7f);
            p.setColor(0xFFFFFFFF);
            c.drawText("Score: " + sc, w / 2f, h / 2f, p);
            if (sc >= best) {
                p.setTextSize(cs * 0.55f);
                p.setColor(0xFFFFE000);
                c.drawText("\u2605 NEW BEST! \u2605", w / 2f, h / 2f + cs, p);
            }
            p.setTextSize(cs * 0.5f);
            p.setColor(0xFFAAAAAA);
            if (System.currentTimeMillis() % 1000 < 700) c.drawText("Tap to restart", w / 2f, h / 2f + cs * 2.2f, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX(), y = e.getY();
            long now = System.currentTimeMillis();

            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startX = lastX = x;
                    startY = lastY = y;
                    accumDx = 0; accumDy = 0;
                    touchTime = now;
                    break;

                case MotionEvent.ACTION_MOVE:
                    float dx = x - lastX;
                    float dy = y - lastY;
                    lastX = x; lastY = y;

                    if (st == 1 && !over) {
                        accumDx += dx;
                        float stepW = cs * 0.9f;
                        while (accumDx >= stepW) {
                            mR();
                            accumDx -= stepW;
                        }
                        while (accumDx <= -stepW) {
                            mL();
                            accumDx += stepW;
                        }

                        accumDy += dy;
                        float stepH = cs * 1.2f;
                        while (accumDy >= stepH) {
                            mD();
                            accumDy -= stepH;
                        }
                    }
                    break;

                case MotionEvent.ACTION_UP:
                    float totalDx = x - startX;
                    float totalDy = y - startY;
                    float totalDist = (float) Math.hypot(totalDx, totalDy);
                    long dt = now - touchTime;

                    float pw = cs * 5.2f;
                    float pbY = by + cs * 10.3f;
                    boolean clickedPause = (st == 1 || st == 2) && x >= px && x <= px + pw && y >= pbY - cs*0.5f && y <= pbY + cs*2.0f;

                    if (clickedPause) {
                        st = (st == 1) ? 2 : 1;
                        ld = now;
                        return true;
                    }

                    if (st == 0) {
                        if (totalDist < cs * 1.5f) {
                            st = 1;
                            ld = now;
                        }
                    } else if (st == 1) {
                        if (totalDist < cs * 0.5f && dt < 300) {
                            doRot();
                            snd(trRot);
                        } else if (totalDy > cs * 2.5f && dt < 300 && totalDy / dt > 0.4f) {
                            hDrop();
                            snd(trDrp);
                        }
                    } else if (st == 2) {
                        st = 1;
                        ld = now;
                    } else if (st == 3) {
                        if (totalDist < cs * 1.5f) {
                            initGame();
                            st = 1;
                            ld = now;
                        }
                    }
                    break;
            }
            return true;
        }
    }
}
