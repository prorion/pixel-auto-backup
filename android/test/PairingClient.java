package com.pab.companion;

import org.json.JSONObject;

public final class PairingClient {
    public static void main(String[] args) throws Exception {
        String device = args[2];
        String stored = Pairing.join(args[0], args[1], device);
        Link link = new Link(stored, device);
        if (link.sync(new JSONObject().put("free", 1_000_000_000)).getInt("interval") != 15) {
            throw new AssertionError("Paired client could not sync");
        }
    }
}
