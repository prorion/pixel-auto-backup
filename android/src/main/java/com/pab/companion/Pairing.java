package com.pab.companion;

import android.content.SharedPreferences;
import java.util.Base64;
import org.json.JSONObject;
import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class Pairing {
    private Pairing() {}

    static synchronized String device(SharedPreferences prefs) throws IOException {
        String current = prefs.getString("device", "");
        if (!current.isEmpty()) return current;
        current = UUID.randomUUID().toString();
        if (!prefs.edit().putString("device", current).commit()) {
            throw new IOException("픽셀 식별 정보를 저장할 수 없습니다.");
        }
        return current;
    }

    static String join(String address, String entered, String device) throws Exception {
        String code = PairCode.normalize(entered);
        if (!PairCode.valid(code)) throw new IOException("연결 코드 6자를 입력해 주세요.");
        URL endpoint = endpoint(address);
        AtomicReference<String> discovered = new AtomicReference<>();
        PairCode.Session session = new PairCode.Session();
        JSONObject begin = new JSONObject().put("device",device).put("a",Link.hex(PairCode.pad(session.A)));
        JSONObject challenge = request(Link.direct(endpoint, "/v1/pair/start", Link.tls(null, discovered)), begin);
        String pin = discovered.get();
        String id = challenge.getString("id");
        if (pin == null || !id.matches("[A-Za-z0-9_-]{43}")) throw new IOException("PC 연결 정보가 올바르지 않습니다.");

        byte[] key = session.key(code,challenge.getString("salt"),challenge.getString("b"));
        String proof = Link.hex(PairCode.proof(key, "phone", pin, id, device, ""));
        JSONObject payload = new JSONObject().put("device",device).put("id",id).put("proof",proof);
        JSONObject result = request(Link.direct(endpoint, "/v1/pair", Link.tls(pin, null)), payload);
        String token = result.getString("token");
        String expected = Link.hex(PairCode.proof(key, "pc", pin, id, device, token));
        if (!token.matches("[A-Za-z0-9_-]{43}") || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                result.getString("proof").getBytes(StandardCharsets.US_ASCII))) {
            throw new IOException("PC가 연결 코드를 인증하지 못했습니다.");
        }
        JSONObject trusted = new JSONObject().put("url",endpoint.toString()).put("pin",pin).put("token",token);
        return "PAB1." + Base64.getUrlEncoder().withoutPadding().encodeToString(trusted.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static URL endpoint(String entered) throws IOException {
        String text = entered.trim();
        if (!text.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?::[0-9]{1,5})?")) {
            throw new IOException("PC에 표시된 주소를 입력해 주세요.");
        }
        String[] address = text.split(":");
        for (String part : address[0].split("\\.")) {
            int value = Integer.parseInt(part);
            if (value > 255 || !Integer.toString(value).equals(part)) {
                throw new IOException("PC 주소를 확인해 주세요.");
            }
        }
        int port = address.length == 2 ? Integer.parseInt(address[1]) : 47626;
        if (port < 1 || port > 65535) throw new IOException("PC 포트를 확인해 주세요.");
        return new URL("https",address[0],port,"");
    }

    private static JSONObject request(HttpsURLConnection conn, JSONObject payload) throws Exception {
        try {
            if (payload != null) {
                byte[] data = payload.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/json");
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            }
            int status = conn.getResponseCode();
            if (status == 410) throw new IOException("PC에서 새 연결 코드를 만들어 주세요.");
            if (status == 401) throw new IOException("연결 코드가 일치하지 않습니다.");
            if (status == 409) throw new IOException("이 PC 프로필은 다른 픽셀에 연결되어 있습니다.");
            if (status != 200) throw new IOException("PC 연결 응답 오류 " + status);
            try (InputStream in = conn.getInputStream()) {
                return new JSONObject(new String(Link.read(in, 16 * 1024), StandardCharsets.UTF_8));
            }
        } finally {
            conn.disconnect();
        }
    }
}
