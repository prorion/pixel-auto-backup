package com.pab.companion;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;

final class PairCode {
    // RFC 5054 2048-bit group. The SRP-6a exchange uses SHA-256.
    static final BigInteger N = new BigInteger(
            "AC6BDB41324A9A9BF166DE5E1389582FAF72B6651987EE07FC3192943DB56050A37329CBB4A099ED8193E0757767A13DD52312AB4B03310D"
            + "CD7F48A9DA04FD50E8083969EDB767B0CF6095179A163AB3661A05FBD5FAAAE82918A9962F0B93B855F97993EC975EEAA80D740ADBF4"
            + "FF747359D041D5C33EA71D281E446B14773BCA97B43A23FB801676BD207A436C6481F1D2B9078717461A5B9D32E688F87748544523B"
            + "524B0D57D5EA77A2775D2ECFA032CFBDBF52FB3786160279004E57AE6AF874E7303CE53299CCC041C7BC308D82A5698F3A8D0C3827"
            + "1AE35F8E9DBFBB694B5C803D89F7AE435DE236D525F54759B65E372FCD68EF20FA7111F9E4AFF73", 16);
    static final BigInteger G = BigInteger.valueOf(2);

    private PairCode() {}

    static String normalize(String entered) {
        return entered.toUpperCase(Locale.ROOT).replaceAll("[\\s-]", "")
                .replace('O','0').replace('I','1').replace('L','1');
    }
    static boolean valid(String code) {
        return code.matches("[0123456789ABCDEFGHJKMNPQRSTVWXYZ]{6}");
    }

    static byte[] pad(BigInteger n) {
        byte[] raw = n.toByteArray(), result = new byte[256];
        int offset = Math.max(0, raw.length - result.length);
        System.arraycopy(raw, offset, result, result.length - (raw.length - offset), raw.length - offset);
        return result;
    }

    static byte[] unhex(String value, int size) {
        if (value.length() != size * 2 || !value.matches("[0-9a-f]+"))
            throw new IllegalArgumentException("PC의 연결 인증값을 읽을 수 없습니다.");
        byte[] result=new byte[size];
        for (int i=0; i<size; i++) result[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return result;
    }

    static byte[] hash(byte[]... values) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for (byte[] value : values) digest.update(value);
        return digest.digest();
    }

    static final class Session {
        private final BigInteger a = new BigInteger(256,new SecureRandom()).setBit(255);
        final BigInteger A = G.modPow(a,N);

        byte[] key(String code,String salt,String server) throws Exception {
            BigInteger B=new BigInteger(1,unhex(server,256));
            if (B.signum()<=0 || B.compareTo(N)>=0) throw new IllegalArgumentException("PC 연결 공개값을 확인해 주세요.");
            BigInteger k=new BigInteger(1,hash(pad(N),pad(G)));
            BigInteger u=new BigInteger(1,hash(pad(A),pad(B)));
            BigInteger x=new BigInteger(1,hash(unhex(salt,16),hash(("autopixel:"+code).getBytes(StandardCharsets.US_ASCII))));
            BigInteger base=B.subtract(k.multiply(G.modPow(x,N))).mod(N);
            if (u.signum()==0 || base.signum()==0) throw new IllegalArgumentException("새 연결 코드로 다시 연결해 주세요.");
            return hash(pad(base.modPow(a.add(u.multiply(x)),N)));
        }
    }

    static byte[] proof(byte[] key, String role, String pin, String id, String device, String token) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        String transcript = String.join("\n", "autopixel-pair-srp6a", role, pin, id, device, token);
        return mac.doFinal(transcript.getBytes(StandardCharsets.US_ASCII));
    }
}
