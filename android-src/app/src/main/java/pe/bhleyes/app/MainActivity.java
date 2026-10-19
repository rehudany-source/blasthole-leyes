package pe.bhleyes.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.view.View;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private static final int REQ_ARCHIVO = 10, REQ_PERM_GPS = 11, REQ_PERM_CAM = 12;
    private WebView web;
    private ValueCallback<Uri[]> cbArchivo;
    private WebChromeClient.FileChooserParams paramsPend;
    private Uri fotoUri;
    private String origenGps;
    private GeolocationPermissions.Callback cbGps;
    private String pendienteJs; // archivos abiertos desde otra app, se cargan cuando la página está lista
    private boolean listo = false, usoRespaldo = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setGeolocationEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(false);
        s.setCacheMode(hayInternet() ? WebSettings.LOAD_DEFAULT : WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setUserAgentString(s.getUserAgentString() + " BHLAndroid/" + BuildConfig.VERSION_NAME);
        web.addJavascriptInterface(new Puente(), "BHLAndroid");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String h = u.getHost() == null ? "" : u.getHost();
                if ("file".equals(u.getScheme()) || h.endsWith("github.io") || h.endsWith("supabase.co")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (ActivityNotFoundException e) { }
                return true;
            }
            @Override
            public void onPageFinished(WebView v, String url) {
                listo = true;
                v.evaluateJavascript(JS_PUENTE, null);
                if (pendienteJs != null) { v.evaluateJavascript(pendienteJs, null); pendienteJs = null; }
            }
            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e) {
                // sin internet y sin copia guardada: se abre la copia que viene dentro de la app
                if (r.isForMainFrame() && !usoRespaldo) { usoRespaldo = true; v.loadUrl("file:///android_asset/index.html"); }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { cb.invoke(origin, true, true); return; }
                origenGps = origin; cbGps = cb;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_PERM_GPS);
            }
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (cbArchivo != null) cbArchivo.onReceiveValue(null);
                cbArchivo = cb; paramsPend = p;
                if (p.isCaptureEnabled() && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_PERM_CAM);
                    return true;
                }
                abrirSelector(p);
                return true;
            }
        });

        String inicial = BuildConfig.URL_APP;
        web.loadUrl(inicial);
        recibir(getIntent());
    }

    private boolean hayInternet() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo n = cm.getActiveNetworkInfo();
            return n != null && n.isConnected();
        } catch (Exception e) { return true; }
    }

    private void abrirSelector(WebChromeClient.FileChooserParams p) {
        boolean foto = false;
        for (String t : p.getAcceptTypes()) if (t != null && t.startsWith("image")) foto = true;
        Intent cam = null;
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED && (foto || p.isCaptureEnabled())) {
            try {
                File dir = new File(getCacheDir(), "fotos"); dir.mkdirs();
                File f = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
                fotoUri = FileProvider.getUriForFile(this, getPackageName() + ".archivos", f);
                cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                cam.putExtra(MediaStore.EXTRA_OUTPUT, fotoUri);
                cam.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception e) { cam = null; fotoUri = null; }
        } else fotoUri = null;
        Intent i;
        if (p.isCaptureEnabled() && cam != null) i = cam;
        else {
            Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*"); // CSV, DXF, MSR, Excel...: no se filtra para que se vean todos los archivos
            if (p.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            i = Intent.createChooser(pick, "Elegir archivo");
            if (cam != null) i.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cam});
        }
        try { startActivityForResult(i, REQ_ARCHIVO); }
        catch (ActivityNotFoundException e) { if (cbArchivo != null) cbArchivo.onReceiveValue(null); cbArchivo = null; }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        if (req != REQ_ARCHIVO) { super.onActivityResult(req, res, data); return; }
        if (cbArchivo == null) return;
        Uri[] r = null;
        if (res == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                int n = data.getClipData().getItemCount(); r = new Uri[n];
                for (int k = 0; k < n; k++) r[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data != null && data.getData() != null) r = new Uri[]{data.getData()};
            else if (fotoUri != null) r = new Uri[]{fotoUri};
        }
        cbArchivo.onReceiveValue(r); cbArchivo = null;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
        if (req == REQ_PERM_GPS && cbGps != null) { cbGps.invoke(origenGps, ok, ok); cbGps = null; }
        if (req == REQ_PERM_CAM && paramsPend != null) abrirSelector(paramsPend);
    }

    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); recibir(i); }

    // Archivos compartidos o abiertos desde otra app (WhatsApp, correo, Archivos): se cargan en «Cargar archivos»
    private void recibir(Intent i) {
        if (i == null) return;
        ArrayList<Uri> us = new ArrayList<>();
        if (Intent.ACTION_VIEW.equals(i.getAction()) && i.getData() != null) us.add(i.getData());
        if (Intent.ACTION_SEND.equals(i.getAction())) { Uri u = i.getParcelableExtra(Intent.EXTRA_STREAM); if (u != null) us.add(u); }
        if (us.isEmpty()) return;
        try {
            JSONArray arr = new JSONArray();
            for (Uri u : us) {
                InputStream in = getContentResolver().openInputStream(u);
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n); in.close();
                JSONObject o = new JSONObject();
                o.put("n", nombreDe(u)); o.put("b", Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP));
                arr.put(o);
            }
            String js = "(function f(){if(window.bhlAbrirArchivos&&typeof cargarTodo==='function'){window.bhlAbrirArchivos(" + arr.toString() + ");}else setTimeout(f,400);})()";
            if (listo) web.evaluateJavascript(js, null); else pendienteJs = js;
        } catch (Exception e) { Toast.makeText(this, "No se pudo abrir el archivo", Toast.LENGTH_LONG).show(); }
    }

    private String nombreDe(Uri u) {
        String n = null;
        try (Cursor c = getContentResolver().query(u, null, null, null, null)) {
            if (c != null && c.moveToFirst()) { int k = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (k >= 0) n = c.getString(k); }
        } catch (Exception e) { }
        if (n == null) n = u.getLastPathSegment();
        return n == null ? "archivo" : n;
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) { web.goBack(); return; }
        new AlertDialog.Builder(this).setMessage("¿Salir de BlastHole Leyes?")
                .setPositiveButton("Salir", (d, w) -> finish()).setNegativeButton("Cancelar", null).show();
    }

    // ---- Puente con la página: guardar archivos (DXF, PDF, Excel, CSV...) e imprimir ----
    private class Puente {
        @JavascriptInterface
        public String guardar(String nombre, String mime, String b64) {
            try {
                byte[] d = Base64.decode(b64, Base64.DEFAULT);
                if (mime == null || mime.isEmpty()) mime = "application/octet-stream";
                String ruta;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nombre);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/BlastHole Leyes");
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    OutputStream o = getContentResolver().openOutputStream(u); o.write(d); o.close();
                    ruta = "Descargas/BlastHole Leyes/" + nombre;
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "BlastHole Leyes"); dir.mkdirs();
                    File f = new File(dir, nombre); FileOutputStream o = new FileOutputStream(f); o.write(d); o.close();
                    ruta = f.getAbsolutePath();
                }
                final String rr = ruta;
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Guardado en " + rr, Toast.LENGTH_LONG).show());
                return ruta;
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "No se pudo guardar: " + e.getMessage(), Toast.LENGTH_LONG).show());
                return "";
            }
        }

        @JavascriptInterface
        public void compartir(String nombre, String mime, String b64) {
            try {
                File dir = new File(getCacheDir(), "salidas"); dir.mkdirs();
                File f = new File(dir, nombre); FileOutputStream o = new FileOutputStream(f); o.write(Base64.decode(b64, Base64.DEFAULT)); o.close();
                Uri u = FileProvider.getUriForFile(MainActivity.this, getPackageName() + ".archivos", f);
                Intent i = new Intent(Intent.ACTION_SEND); i.setType(mime == null || mime.isEmpty() ? "*/*" : mime);
                i.putExtra(Intent.EXTRA_STREAM, u); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                runOnUiThread(() -> startActivity(Intent.createChooser(i, "Compartir " + nombre)));
            } catch (Exception e) { }
        }

        @JavascriptInterface
        public void imprimir(String titulo) {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                final PrintDocumentAdapter a = web.createPrintDocumentAdapter(titulo == null ? "BlastHole Leyes" : titulo);
                PrintDocumentAdapter envuelto = new PrintDocumentAdapter() {
                    @Override public void onStart() { a.onStart(); }
                    @Override public void onLayout(PrintAttributes o, PrintAttributes n, CancellationSignal c, LayoutResultCallback cb, Bundle x) { a.onLayout(o, n, c, cb, x); }
                    @Override public void onWrite(PageRange[] p, ParcelFileDescriptor d, CancellationSignal c, WriteResultCallback cb) { a.onWrite(p, d, c, cb); }
                    @Override public void onFinish() { a.onFinish(); web.evaluateJavascript("window.dispatchEvent(new Event('afterprint'))", null); }
                };
                pm.print(titulo == null ? "BlastHole Leyes" : titulo, envuelto, null);
            });
        }

        @JavascriptInterface
        public String version() { return BuildConfig.VERSION_NAME; }
    }

    // Se inyecta en cada carga: descargas de la página → carpeta Descargas; window.print → impresión de Android
    private static final String JS_PUENTE =
        "(function(){if(window.__bhlAnd)return;window.__bhlAnd=1;" +
        "window.print=function(){BHLAndroid.imprimir(document.title||'BlastHole Leyes');};" +
        "function b64(bl,cb){var fr=new FileReader();fr.onload=function(){cb(String(fr.result).split(',')[1]||'');};fr.readAsDataURL(bl);}" +
        "window.bhlGuardarAndroid=function(bl,nombre){b64(bl,function(d){BHLAndroid.guardar(nombre,bl.type||'',d);});};" +
        "document.addEventListener('click',function(e){var a=e.target&&e.target.closest&&e.target.closest('a[download]');if(!a)return;var h=a.href||'';" +
        "if(!/^(blob:|data:)/.test(h))return;e.preventDefault();e.stopPropagation();" +
        "fetch(h).then(function(r){return r.blob();}).then(function(bl){window.bhlGuardarAndroid(bl,a.getAttribute('download')||'archivo');});},true);" +
        "window.bhlAbrirArchivos=function(L){var fs=L.map(function(o){var s=atob(o.b),u=new Uint8Array(s.length);for(var i=0;i<s.length;i++)u[i]=s.charCodeAt(i);return new File([u],o.n);});" +
        "try{if(typeof abrirCarga==='function')abrirCarga();cargarTodo(fs);}catch(e){alert('No se pudo cargar: '+e.message);}};" +
        "})()";
}
