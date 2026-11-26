package com.pab.companion;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class PairCode {
    private PairCode() {}

    static String normalize(String entered) {
        return entered.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "")
                .replace('O','0').replace('I','1').replace('L','1');
    }

    static boolean valid(String code) {
        return code.matches("[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{12}");
    }

    static byte[] proof(String code, String role, String pin, String nonce, String device, String token) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(code.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        String transcript = String.join("\n", "pab-pair-v2", role, pin, nonce, device, token);
        return mac.doFinal(transcript.getBytes(StandardCharsets.US_ASCII));
    }
}
