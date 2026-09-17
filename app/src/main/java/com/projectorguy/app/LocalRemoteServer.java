package com.projectorguy.app;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

/**
 * Minimal HTTP server so a phone on the same WiFi network can load the
 * Remote Control page and send projector commands. remote.html only calls
 * window.AndroidBridge directly when it's running inside this app's own
 * WebView; a phone has no such bridge, so it instead loads this page from
 * here and hits /command over the LAN. Serving remote.html itself from here
 * (rather than pointing the phone at the HTTPS GitHub Pages copy) also
 * avoids mixed-content blocking, since both the page and its fetch() calls
 * are then same-origin plain HTTP.
 */
public class LocalRemoteServer {

    private static final String TAG = "LocalRemoteServer";
    static final int PORT = 8899;
    private static final String ASSET_DIR = "www/remote/";

    private final Context context;
    private final AndroidBridge bridge;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    LocalRemoteServer(Context context, AndroidBridge bridge) {
        this.context = context;
        this.bridge = bridge;
    }

    void start() {
        if (running) return;
        running = true;
        new Thread(this::acceptLoop, "LocalRemoteServer").start();
    }

    void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {
        }
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket(PORT);
        } catch (IOException e) {
            Log.e(TAG, "Failed to bind port " + PORT + ": " + e.getMessage());
            running = false;
            return;
        }
        while (running) {
            try {
                Socket client = serverSocket.accept();
                new Thread(() -> handleClient(client), "LocalRemoteServer-client").start();
            } catch (IOException e) {
                if (running) Log.e(TAG, "accept() failed: " + e.getMessage());
            }
        }
    }

    private void handleClient(Socket client) {
        try (Socket socket = client;
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {

            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String requestLine = reader.readLine();
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];

            // Drain the rest of the request headers off the socket.
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // ignored
            }

            if (path.startsWith("/command")) {
                handleCommand(path, out);
            } else {
                handleAsset(path, out);
            }
        } catch (IOException e) {
            Log.e(TAG, "handleClient failed: " + e.getMessage());
        }
    }

    private void handleCommand(String path, OutputStream out) throws IOException {
        String query = path.contains("?") ? path.substring(path.indexOf('?') + 1) : "";
        String cmd = queryParam(query, "cmd");
        String result = (cmd != null) ? bridge.sendProjectorCommand("network", cmd) : "fail";
        writeResponse(out, 200, "text/plain", result.getBytes(StandardCharsets.UTF_8));
    }

    private void handleAsset(String path, OutputStream out) throws IOException {
        String assetName = path.equals("/") ? "remote.html" : path.substring(1);
        if (assetName.contains("?")) assetName = assetName.substring(0, assetName.indexOf('?'));

        try (InputStream assetIn = context.getAssets().open(ASSET_DIR + assetName)) {
            writeResponse(out, 200, contentTypeFor(assetName), readAll(assetIn));
        } catch (IOException e) {
            writeResponse(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String queryParam(String query, String key) {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            String k = pair.substring(0, eq);
            if (k.equals(key)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (Exception e) {
                    return pair.substring(eq + 1);
                }
            }
        }
        return null;
    }

    private static String contentTypeFor(String assetName) {
        if (assetName.endsWith(".html")) return "text/html; charset=utf-8";
        if (assetName.endsWith(".json")) return "application/json; charset=utf-8";
        if (assetName.endsWith(".js")) return "application/javascript; charset=utf-8";
        return "application/octet-stream";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void writeResponse(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        String statusText = status == 200 ? "OK" : "Not Found";
        String headers = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: " + contentType + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n"
                + "\r\n";
        out.write(headers.getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
