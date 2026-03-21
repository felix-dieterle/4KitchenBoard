package com.kitchenboard.chat;

import android.util.Log;

import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Sends chat messages directly to other KitchenBoard devices on the LAN via
 * TCP, using the port monitored by {@link LanChatServer}.
 *
 * <p>All methods are synchronous and must be called from a background thread.
 */
public class LanChatClient {

    private static final String TAG     = "LanChatClient";
    private static final int    TIMEOUT = 8_000;

    /**
     * Sends a message to a single peer identified by IP address.
     *
     * @param ip      IPv4 address of the target device
     * @param message The message to send (will be serialised as one JSON line)
     * @throws Exception on any network error
     */
    public static void sendToPeer(String ip, ChatMessage message) throws Exception {
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(ip, LanChatServer.CHAT_PORT), TIMEOUT);
            s.setSoTimeout(TIMEOUT);
            OutputStream out = s.getOutputStream();
            byte[] payload = (message.toJson().toString() + "\n")
                    .getBytes(Charset.forName("UTF-8"));
            out.write(payload);
            out.flush();
        }
    }

    /**
     * Broadcasts a message to all known LAN peers.
     *
     * <p>Failures for individual peers are logged but do not abort the broadcast.
     *
     * @param peers   List of known peers (from {@link LanDiscoveryManager#getPeers()})
     * @param message The message to deliver
     */
    public static void broadcast(List<LanPeer> peers, ChatMessage message) {
        for (LanPeer peer : peers) {
            try {
                sendToPeer(peer.ip, message);
            } catch (Exception e) {
                Log.w(TAG, "broadcast: failed to reach " + peer.deviceName
                        + " (" + peer.ip + ")", e);
            }
        }
    }
}
