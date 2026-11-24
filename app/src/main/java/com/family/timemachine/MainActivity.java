package com.family.timemachine;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Time Machine app: shows the time machine page (bundled inside the app) full screen
 * and adds the things a web page can't do on its own: backups to a folder you choose,
 * restoring, the phone's real voices, file and microphone access, and the back button.
 * The app has no internet permission, so it cannot go online.
 * It can also link to the Temporal Engine (the brass ESP32 console) over Bluetooth Low Energy.
 */
@SuppressLint("MissingPermission")   // Bluetooth permissions are checked at run time in engineBegin()
public class MainActivity extends Activity {
    private static final int REQ_FILES = 1, REQ_FOLDER = 2, REQ_RESTORE = 3, REQ_MIC = 4, REQ_BT = 5;

    // Nordic UART Service, as used by the engine's ESP32 firmware
    private static final UUID NUS = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    private static final UUID NUS_RX = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e"); // phone -> engine
    private static final UUID NUS_TX = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e"); // engine -> phone
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic rxChar;
    private volatile boolean engineWanted = false, scanning = false, askedBtOn = false;
    private final StringBuilder engineBuf = new StringBuilder();
    private final ArrayDeque<byte[]> writeQueue = new ArrayDeque<>();
    private boolean writing = false;

    private WebView web;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMic;

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final Map<String, File> ttsFiles = Collections.synchronizedMap(new HashMap<>());

    private final Map<Integer, OutputStream> outs = new HashMap<>();
    private final Map<Integer, Uri> outUris = new HashMap<>();
    private int nextHandle = 1;
    private RandomAccessFile restoreRaf;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("timemachine", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);           // to open the page bundled inside the app
        s.setAllowContentAccess(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setBlockNetworkLoads(true);         // no network, ever
        s.setTextZoom(100);

        web.addJavascriptInterface(new Bridge(), "TMApp");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return true; // never navigate anywhere else
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    return new WebResourceResponse("text/plain", "utf-8", 403, "Offline app",
                            new HashMap<>(), new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                String[] types = mimeTypes(params.getAcceptTypes());
                if (types.length > 0) i.putExtra(Intent.EXTRA_MIME_TYPES, types);
                if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose"), REQ_FILES);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    if (!Arrays.asList(request.getResources()).contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                        request.deny();
                        return;
                    }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        pendingMic = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
                    }
                });
            }
        });

        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady) tts.setOnUtteranceProgressListener(new TtsListener());
        });

        web.loadUrl("file:///android_asset/index.html");
    }

    /** Accept types from the page -> MIME types for the Android file picker. */
    private static String[] mimeTypes(String[] accept) {
        List<String> out = new ArrayList<>();
        if (accept != null) for (String a : accept) for (String t : a.split(",")) {
            t = t.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            // backups: show every file, since file managers label .zip/.json inconsistently
            if (t.contains("zip") || t.contains("json")) return new String[0];
            if (t.contains("/")) out.add(t);
        }
        return out.toArray(new String[0]);
    }

    // ---------------- results from pickers and permission prompts ----------------

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILES) {
            if (fileCallback == null) return;
            Uri[] result = null;
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    result = new Uri[n];
                    for (int i = 0; i < n; i++) result[i] = data.getClipData().getItemAt(i).getUri();
                } else if (data.getData() != null) {
                    result = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(result);
            fileCallback = null;
        } else if (requestCode == REQ_FOLDER) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            Uri tree = data.getData();
            try {
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
            String name = friendlyFolderName(tree);
            prefs.edit().putString("folder", tree.toString()).putString("folderName", name).apply();
            send(msg("folder").put2("name", name));
        } else if (requestCode == REQ_RESTORE) {
            if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
            final Uri uri = data.getData();
            send(msg("toast").put2("message", "Opening the backup…"));
            new Thread(() -> {
                try {
                    File f = new File(getCacheDir(), "restore.bin");
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(f)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    }
                    synchronized (this) {
                        if (restoreRaf != null) restoreRaf.close();
                        restoreRaf = new RandomAccessFile(f, "r");
                    }
                    send(msg("restorePicked").put2("name", displayName(uri)).put2("size", f.length()));
                } catch (Exception e) {
                    send(msg("toast").put2("message", "Couldn't open that file."));
                }
            }).start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode == REQ_BT) {
            if (hasBlePerms()) engineBegin();
            else engineState("noperm");
            return;
        }
        if (requestCode == REQ_MIC && pendingMic != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingMic.deny();
            pendingMic = null;
        }
    }

    // ---------------- lifecycle and back button ----------------

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("window.tmBack ? tmBack() : false", value -> {
            if (!"true".equals(value)) moveTaskToBack(true);
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        engineEnd();
        if (tts != null) tts.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }

    // ---------------- helpers ----------------

    /** A small JSON message builder for messages sent to the page. */
    private static class Msg extends JSONObject {
        Msg put2(String k, Object v) {
            try { put(k, v); } catch (Exception ignored) { }
            return this;
        }
    }

    private static Msg msg(String type) { return new Msg().put2("type", type); }

    private void send(JSONObject m) {
        final String js = "window.tmNative && window.tmNative(" + m.toString() + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) { }
        return "backup";
    }

    private String friendlyFolderName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree); // e.g. "primary:Download/Backups" or "1A2B-3C4D:Backups"
            int colon = id.indexOf(':');
            String vol = colon > 0 ? id.substring(0, colon) : id;
            String path = colon >= 0 ? id.substring(colon + 1) : "";
            String where = "primary".equalsIgnoreCase(vol) ? "Tablet storage" : "SD card / USB";
            return path.isEmpty() ? where : where + " › " + path.replace("/", " › ");
        } catch (Exception e) {
            return "chosen folder";
        }
    }

    private Uri backupTree() {
        String t = prefs.getString("folder", null);
        if (t == null) return null;
        Uri tree = Uri.parse(t);
        for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(tree) && p.isWritePermission()) return tree;
        }
        return null; // permission lost (e.g. SD card removed and re-formatted)
    }

    private class TtsListener extends UtteranceProgressListener {
        @Override public void onStart(String id) { }

        @Override
        public void onDone(String id) {
            File f = ttsFiles.remove(id);
            if (f == null) return;
            try {
                byte[] bytes = new byte[(int) f.length()];
                try (FileInputStream in = new FileInputStream(f)) {
                    int off = 0, n;
                    while (off < bytes.length && (n = in.read(bytes, off, bytes.length - off)) > 0) off += n;
                }
                send(msg("tts").put2("id", id).put2("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)));
            } catch (Exception e) {
                send(msg("tts").put2("id", id).put2("error", "read"));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }

        @Override
        public void onError(String id) {
            File f = ttsFiles.remove(id);
            if (f != null) //noinspection ResultOfMethodCallIgnored
                f.delete();
            send(msg("tts").put2("id", id).put2("error", "synth"));
        }
    }

    // ---------------- Temporal Engine link (Bluetooth Low Energy) ----------------

    private void engineState(String state) { send(msg("engineState").put2("state", state)); }

    private String[] blePerms() {
        if (Build.VERSION.SDK_INT >= 31)
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT};
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};   // Android 11 and older need this to scan
    }

    private boolean hasBlePerms() {
        for (String p : blePerms()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private void engineBeginLater(long ms) { ui.postDelayed(() -> { if (engineWanted && gatt == null && !scanning) engineBegin(); }, ms); }

    private void engineBegin() {
        engineWanted = true;
        if (!hasBlePerms()) { engineState("asking"); requestPermissions(blePerms(), REQ_BT); return; }
        BluetoothManager bm = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        btAdapter = bm == null ? null : bm.getAdapter();
        if (btAdapter == null) { engineState("nobluetooth"); return; }
        if (!btAdapter.isEnabled()) {
            engineState("btoff");
            if (!askedBtOn) {
                askedBtOn = true;
                try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) { }
            }
            engineBeginLater(4000);
            return;
        }
        if (gatt != null || scanning) return;
        scanner = btAdapter.getBluetoothLeScanner();
        if (scanner == null) { engineBeginLater(3000); return; }
        List<ScanFilter> filters = Collections.singletonList(new ScanFilter.Builder().setServiceUuid(new ParcelUuid(NUS)).build());
        ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        scanning = true;
        engineState("searching");
        try { scanner.startScan(filters, settings, scanCallback); }
        catch (Exception e) { scanning = false; engineBeginLater(5000); return; }
        ui.postDelayed(() -> {                       // scan in 15-second rounds to save battery
            if (scanning) { stopScan(); engineBeginLater(5000); }
        }, 15000);
    }

    private void stopScan() {
        if (!scanning) return;
        scanning = false;
        try { if (scanner != null) scanner.stopScan(scanCallback); } catch (Exception ignored) { }
    }

    private void engineEnd() {
        engineWanted = false;
        stopScan();
        if (gatt != null) { try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) { } }
        gatt = null; rxChar = null;
        synchronized (writeQueue) { writeQueue.clear(); writing = false; }
        engineState("off");
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) return;
            stopScan();
            engineState("connecting");
            BluetoothDevice device = result.getDevice();
            gatt = device.connectGatt(MainActivity.this, false, gattCallback, BluetoothDevice.TRANSPORT_LE);
        }

        @Override
        public void onScanFailed(int errorCode) { scanning = false; engineBeginLater(5000); }
    };

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (!g.requestMtu(185)) g.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                try { g.close(); } catch (Exception ignored) { }
                if (g == gatt) { gatt = null; rxChar = null; }
                synchronized (writeQueue) { writeQueue.clear(); writing = false; }
                if (engineWanted) { engineState("searching"); engineBeginLater(2000); }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) { g.discoverServices(); }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            BluetoothGattService svc = g.getService(NUS);
            if (svc == null) { g.disconnect(); return; }
            rxChar = svc.getCharacteristic(NUS_RX);
            BluetoothGattCharacteristic tx = svc.getCharacteristic(NUS_TX);
            if (rxChar == null || tx == null) { g.disconnect(); return; }
            g.setCharacteristicNotification(tx, true);
            BluetoothGattDescriptor d = tx.getDescriptor(CCCD);
            if (d != null) {
                d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                g.writeDescriptor(d);
            } else {
                engineState("connected");
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, int status) { engineState("connected"); }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
            byte[] v = c.getValue();
            if (v == null) return;
            synchronized (engineBuf) {
                engineBuf.append(new String(v, StandardCharsets.UTF_8));
                int nl;
                while ((nl = engineBuf.indexOf("\n")) >= 0) {
                    String line = engineBuf.substring(0, nl).trim();
                    engineBuf.delete(0, nl + 1);
                    if (!line.isEmpty()) send(msg("engine").put2("line", line));
                }
                if (engineBuf.length() > 512) engineBuf.setLength(0);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, int status) {
            synchronized (writeQueue) { writing = false; }
            pumpWrites();
        }
    };

    private void pumpWrites() {
        byte[] next;
        synchronized (writeQueue) {
            if (writing || gatt == null || rxChar == null || writeQueue.isEmpty()) return;
            next = writeQueue.poll();
            writing = true;
        }
        try {
            rxChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            rxChar.setValue(next);
            if (!gatt.writeCharacteristic(rxChar)) { synchronized (writeQueue) { writing = false; } ui.postDelayed(this::pumpWrites, 50); }
        } catch (Exception e) {
            synchronized (writeQueue) { writing = false; }
        }
    }

    // ---------------- what the page can call (window.TMApp) ----------------

    private class Bridge {
        @JavascriptInterface
        public boolean isApp() { return true; }

        // ----- backup folder -----
        @JavascriptInterface
        public String getBackupFolderName() {
            return backupTree() == null ? "" : prefs.getString("folderName", "chosen folder");
        }

        @JavascriptInterface
        public void chooseBackupFolder() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try { startActivityForResult(i, REQ_FOLDER); }
                catch (Exception e) { send(msg("toast").put2("message", "This tablet has no folder picker.")); }
            });
        }

        // ----- writing a backup -----
        @JavascriptInterface
        public synchronized int openBackup(String fileName) {
            try {
                Uri tree = backupTree();
                if (tree == null) return -1;
                ContentResolver cr = getContentResolver();
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                Uri doc = DocumentsContract.createDocument(cr, parent, "application/zip", fileName);
                if (doc == null) return -1;
                OutputStream os = cr.openOutputStream(doc, "w");
                if (os == null) return -1;
                int h = nextHandle++;
                outs.put(h, new BufferedOutputStream(os, 1 << 16));
                outUris.put(h, doc);
                return h;
            } catch (Exception e) {
                return -1;
            }
        }

        @JavascriptInterface
        public synchronized boolean writeChunk(int handle, String b64) {
            try {
                OutputStream os = outs.get(handle);
                if (os == null) return false;
                os.write(Base64.decode(b64, Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean closeBackup(int handle) {
            try {
                OutputStream os = outs.remove(handle);
                outUris.remove(handle);
                if (os == null) return false;
                os.flush();
                os.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public synchronized void abortBackup(int handle) {
            try {
                OutputStream os = outs.remove(handle);
                if (os != null) os.close();
            } catch (Exception ignored) { }
            try {
                Uri doc = outUris.remove(handle);
                if (doc != null) DocumentsContract.deleteDocument(getContentResolver(), doc);
            } catch (Exception ignored) { }
        }

        /** Keeps only the newest `keep` backups in the folder. */
        @JavascriptInterface
        public boolean pruneBackups(String prefix, int keep) {
            Uri tree = backupTree();
            if (tree == null) return false;
            try {
                String rootId = DocumentsContract.getTreeDocumentId(tree);
                Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
                List<String[]> found = new ArrayList<>(); // {name, id}
                try (Cursor c = getContentResolver().query(children, new String[]{
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
                    while (c != null && c.moveToNext()) {
                        String n = c.getString(0);
                        if (n != null && n.startsWith(prefix) && n.endsWith(".zip")) found.add(new String[]{n, c.getString(1)});
                    }
                }
                found.sort((a, b) -> b[0].compareTo(a[0])); // names contain the date, newest first
                for (int i = keep; i < found.size(); i++) {
                    DocumentsContract.deleteDocument(getContentResolver(), DocumentsContract.buildDocumentUriUsingTree(tree, found.get(i)[1]));
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        // ----- restoring -----
        @JavascriptInterface
        public void pickRestoreFile() {
            runOnUiThread(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try { startActivityForResult(i, REQ_RESTORE); }
                catch (Exception e) { send(msg("toast").put2("message", "This tablet has no file picker.")); }
            });
        }

        @JavascriptInterface
        public String readRestoreChunk(double offset, int length) {
            synchronized (MainActivity.this) {
                try {
                    if (restoreRaf == null) return null;
                    byte[] buf = new byte[length];
                    restoreRaf.seek((long) offset);
                    int off = 0, n;
                    while (off < length && (n = restoreRaf.read(buf, off, length - off)) > 0) off += n;
                    return Base64.encodeToString(buf, 0, off, Base64.NO_WRAP);
                } catch (Exception e) {
                    return null;
                }
            }
        }

        // ----- the Temporal Engine -----
        @JavascriptInterface
        public void engineStart() { runOnUiThread(MainActivity.this::engineBegin); }

        @JavascriptInterface
        public void engineStop() { runOnUiThread(MainActivity.this::engineEnd); }

        @JavascriptInterface
        public boolean engineSend(String line) {
            if (gatt == null || rxChar == null) return false;
            synchronized (writeQueue) {
                if (writeQueue.size() > 20) writeQueue.poll();
                writeQueue.add((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
            pumpWrites();
            return true;
        }

        // ----- the phone's voices -----
        @JavascriptInterface
        public String ttsVoices() {
            JSONArray arr = new JSONArray();
            if (!ttsReady) return arr.toString();
            try {
                Set<Voice> set = tts.getVoices();
                List<Voice> list = new ArrayList<>();
                if (set != null) for (Voice v : set) {
                    if (v.isNetworkConnectionRequired()) continue;
                    Set<String> f = v.getFeatures();
                    if (f != null && f.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                    list.add(v);
                }
                list.sort((a, b) -> {
                    boolean ea = "en".equals(a.getLocale().getLanguage()), eb = "en".equals(b.getLocale().getLanguage());
                    if (ea != eb) return ea ? -1 : 1;
                    int c = a.getLocale().getDisplayName().compareTo(b.getLocale().getDisplayName());
                    return c != 0 ? c : a.getName().compareTo(b.getName());
                });
                for (Voice v : list) {
                    JSONObject o = new JSONObject();
                    o.put("name", v.getName());
                    o.put("label", v.getLocale().getDisplayName() + " · " + shortName(v.getName()));
                    arr.put(o);
                }
            } catch (Exception ignored) { }
            return arr.toString();
        }

        private String shortName(String n) {
            // "en-us-x-iom-local" -> "voice IOM"
            String[] p = n.split("-");
            for (int i = 0; i < p.length - 1; i++) if ("x".equals(p[i])) return "voice " + p[i + 1].toUpperCase(Locale.ROOT);
            return n;
        }

        @JavascriptInterface
        public synchronized boolean ttsSynth(String id, String text, String voiceName, double pitch, double rate) {
            if (!ttsReady) return false;
            try {
                if (voiceName != null && !voiceName.isEmpty()) {
                    Set<Voice> set = tts.getVoices();
                    if (set != null) for (Voice v : set) if (v.getName().equals(voiceName)) { tts.setVoice(v); break; }
                }
                tts.setPitch((float) Math.max(0.25, Math.min(2.5, pitch)));
                tts.setSpeechRate((float) Math.max(0.3, Math.min(2.5, rate)));
                File f = new File(getCacheDir(), "tts-" + id + ".wav");
                ttsFiles.put(id, f);
                int r = tts.synthesizeToFile(text, new Bundle(), f, id);
                if (r != TextToSpeech.SUCCESS) { ttsFiles.remove(id); return false; }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
