package com.kitchenboard.chat;

/**
 * Represents another KitchenBoard device discovered on the local network via
 * {@link LanDiscoveryManager}.
 */
public class LanPeer {

    /** Opaque device identifier (matches ChatMessage.senderId format). */
    public final String deviceId;
    /** Human-readable display name of the remote device / person. */
    public final String deviceName;
    /** IPv4 address of the remote device on the LAN. */
    public final String ip;
    /** Timestamp (ms) when this peer was last seen. */
    public long lastSeenMs;

    /** Creates a peer record. */
    public LanPeer(String deviceId, String deviceName, String ip, long lastSeenMs) {
        this.deviceId   = deviceId;
        this.deviceName = deviceName;
        this.ip         = ip;
        this.lastSeenMs = lastSeenMs;
    }
}
