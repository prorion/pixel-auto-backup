package com.pab.companion;

import java.util.Base64;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

final class Link {
    private final URL endpoint;
    private final String token;
    private final String id;
    private final SSLContext tls;
    final String namespace;

    Link(String code, String id) throws Exception {
        if (!code.startsWith("PAB1.") || code.length() > 2048) {
            throw new IOException("페어링 코드 형식을 확인해 주세요.");
        }
        this.id = id;
        JSONObject config = new JSONObject(new String(
                Base64.getUrlDecoder().decode(code.substring(5)),
                StandardCharsets.UTF_8));
        endpoint = new URL(config.getString("url"));
        token = config.getString("token");
        final String pin = config.getString("pin");
        if (!endpoint.getProtocol().equals("https") || !endpoint.getHost().matches("[0-9.]+")
                || endpoint.getPort() < 1 || endpoint.getUserInfo() != null || !endpoint.getFile().isEmpty()
                || endpoint.getRef() != null || !pin.matches("[a-f0-9]{64}") || !token.matches("[A-Za-z0-9_-]{43}")) {
            throw new IOException("페어링 주소를 확인해 주세요.");
        }
        namespace = pin.substring(0, 20);
        tls = tls(pin, null);
    }

    static SSLContext tls(String pin, AtomicReference<String> captured) throws Exception {
        TrustManager[] trust = {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String type) throws CertificateException {
                throw new CertificateException("Server certificate required");
            }
            @Override public void checkServerTrusted(X509Certificate[] chain, String type) throws CertificateException {
                try {
                    if (chain == null || chain.length == 0) throw new CertificateException("PC certificate missing");
                    chain[0].checkValidity();
                    String actual = hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()));
                    if (pin != null && !MessageDigest.isEqual(pin.getBytes(StandardCharsets.US_ASCII),
                            actual.getBytes(StandardCharsets.US_ASCII))) {
                        throw new CertificateException("PC certificate fingerprint changed");
                    }
                    if (captured != null && !captured.compareAndSet(null, actual) && !actual.equals(captured.get())) {
                        throw new CertificateException("PC certificate changed during pairing");
                    }
                } catch (CertificateException e) {
                    throw e;
                } catch (Exception e) {
                    throw new CertificateException(e);
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, trust, null);
        return tls;
    }

    static HttpsURLConnection direct(URL endpoint, String path, SSLContext tls) throws IOException {
        HttpsURLConnection conn = (HttpsURLConnection) new URL(endpoint, path).openConnection(Proxy.NO_PROXY);
        conn.setSSLSocketFactory(tls.getSocketFactory());
        // Pairing authenticates the captured certificate. Subsequent requests require that exact pin.
        conn.setHostnameVerifier((host, session) -> host.equals(endpoint.getHost()));
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(20000);
        return conn;
    }

    HttpsURLConnection connect(String path) throws IOException {
        HttpsURLConnection conn = direct(endpoint, path, tls);
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("X-Device", id);
        return conn;
    }
    JSONObject sync(JSONObject report) throws Exception {
        HttpsURLConnection conn = connect("/v1/sync");
        try {
            byte[] data = report.toString().getBytes(StandardCharsets.UTF_8);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setFixedLengthStreamingMode(data.length);
            try (java.io.OutputStream out = conn.getOutputStream()) { out.write(data); }
            int status = conn.getResponseCode();
            if (status != 200) throw new IOException("윈도우 응답 " + status);
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(new String(read(in, 4 << 20), StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
    }
    static byte[] read(InputStream in, int limit) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int n; (n = in.read(buffer)) != -1;) {
            if (n > limit - out.size()) throw new IOException("응답이 너무 큽니다.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
    static String hex(byte[] bytes) {
        StringBuilder s = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            int value = b & 255;
            s.append(Character.forDigit(value >>> 4, 16)).append(Character.forDigit(value & 15, 16));
        }
        return s.toString();
    }
}
