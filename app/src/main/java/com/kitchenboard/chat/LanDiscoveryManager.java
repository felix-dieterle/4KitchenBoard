package com.kitchenboard.chat;

import android.util.Log;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages UDP-based peer discovery for direct LAN chat.
 *
 * <p>Each KitchenBoard device running in LAN mode periodically broadcasts a
 * "hello" JSON payload on {@value #DISCOVERY_PORT}.  Peers respond with their
 * own info so all participants build a live registry of reachable devices.
 *
 * <p>Discovery packets are simple UTF-8 JSON strings:
 * <pre>{@code {"type":"hello","deviceId":"device_…","deviceName":"…"}}</pre>
 *
 * <p>Call {@link #start(String, String)} to begin discovery and
 * {@link #stop()} to shut down.
 */
public class LanDiscoveryManager {

    private static final String TAG            = "LanDiscovery";
    /** UDP port used for peer-discovery broadcasts. */
    static final int    DISCOVERY_PORT  = 47474;
    /** How long a peer remains in the registry without a fresh hello (ms). */
    private static final long   PEER_TTL_MS    = 10 * 60_000L;  // 10 minutes
    /** Max datagram size (bytes). */
    private static final int    BUF_SIZE       = 1024;

    private final Map<String, LanPeer> peers = new LinkedHashMap<>();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** Separate executor for ad-hoc broadcast calls (e.g. on active-status change). */
    private final ExecutorService chatBroadcastExecutor = Executors.newSingleThreadExecutor();
    private Future<?> listenerTask;
    private DatagramSocket listenerSocket;

    private String myDeviceId;
    private String myDeviceName;
    private volatile boolean myActiveStatus = true;

    /** Callback interface for peer list changes. */
    public interface PeerListListener {
        /** Called on a background thread whenever the peer list changes. */
        void onPeersChanged(List<LanPeer> peers);
    }

    private PeerListListener peerListListener;

    public LanDiscoveryManager() {
    }

    /** Sets a listener that is notified whenever the peer list changes. */
    public void setPeerListListener(PeerListListener l) {
        this.peerListListener = l;
    }

    /**
     * Updates the local active status and immediately broadcasts it to all peers.
     *
     * @param active {@code true} means this device is ready to receive chat messages
     */
    public void setActiveStatus(boolean active) {
        myActiveStatus = active;
        chatBroadcastExecutor.submit(this::broadcastHello);
    }

    /** Returns the current active status of this device. */
    public boolean getActiveStatus() {
        return myActiveStatus;
    }

    /**
     * Starts the discovery service: opens a UDP socket, listens for hellos,
     * and sends an initial broadcast to announce this device.
     *
     * @param deviceId   This device's unique ID
     * @param deviceName This device's display name
     */
    public void start(String deviceId, String deviceName) {
        this.myDeviceId   = deviceId;
        this.myDeviceName = deviceName;
        if (running.compareAndSet(false, true)) {
            listenerTask = executor.submit(this::listenLoop);
        }
    }

    /** Stops the discovery service and closes the socket. */
    public void stop() {
        running.set(false);
        if (listenerSocket != null) {
            listenerSocket.close();
        }
        if (listenerTask != null) {
            listenerTask.cancel(true);
        }
    }

    /**
     * Sends a UDP broadcast hello so peers discover this device immediately.
     * Must be called from a background thread.
     */
    public void broadcastHello() {
        try (DatagramSocket s = new DatagramSocket()) {
            s.setBroadcast(true);
            byte[] payload = buildHelloPayload().getBytes(Charset.forName("UTF-8"));
            // 255.255.255.255 reaches all hosts on the subnet
            DatagramPacket packet = new DatagramPacket(
                    payload, payload.length,
                    InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
            s.send(packet);
        } catch (Exception e) {
            Log.w(TAG, "broadcastHello failed", e);
        }
    }

    /** Returns a snapshot of currently known live peers (excluding this device). */
    public synchronized List<LanPeer> getPeers() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, LanPeer>> it = peers.entrySet().iterator();
        while (it.hasNext()) {
            if (now - it.next().getValue().lastSeenMs > PEER_TTL_MS) it.remove();
        }
        return new ArrayList<>(peers.values());
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void listenLoop() {
        try {
            listenerSocket = new DatagramSocket(null);
            listenerSocket.setReuseAddress(true);
            listenerSocket.bind(new InetSocketAddress(DISCOVERY_PORT));
            listenerSocket.setBroadcast(true);
            listenerSocket.setSoTimeout(30_000);

            // Announce presence immediately
            broadcastHello();

            byte[] buf = new byte[BUF_SIZE];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    listenerSocket.receive(packet);
                } catch (SocketTimeoutException e) {
                    // Periodic timeout – send another hello to keep peers fresh
                    broadcastHello();
                    continue;
                }
                String senderIp = packet.getAddress().getHostAddress();
                String payload  = new String(packet.getData(), 0, packet.getLength(),
                        Charset.forName("UTF-8")).trim();
                handleDiscoveryPayload(senderIp, payload);
            }
        } catch (Exception e) {
            if (running.get()) Log.w(TAG, "listenLoop error", e);
        } finally {
            if (listenerSocket != null && !listenerSocket.isClosed()) {
                listenerSocket.close();
            }
            // Always reset running so start() can be retried after a failure.
            running.set(false);
        }
    }

    private void handleDiscoveryPayload(String senderIp, String payload) {
        try {
            JSONObject obj = new JSONObject(payload);
            if (!"hello".equals(obj.optString("type"))) return;
            String  deviceId   = obj.optString("deviceId",   "");
            String  deviceName = obj.optString("deviceName", "");
            boolean active     = obj.optBoolean("active", true);
            if (deviceId.isEmpty() || deviceId.equals(myDeviceId)) return;

            boolean changed;
            synchronized (this) {
                LanPeer existing = peers.get(deviceId);
                if (existing != null) {
                    existing.lastSeenMs = System.currentTimeMillis();
                    boolean wasActive = existing.isActive;
                    existing.isActive = active;
                    // Update IP in case it changed (DHCP reassignment)
                    existing.ip = senderIp;
                    // Active-status change counts as a list change even if peer was already known
                    changed = (wasActive != active);
                } else {
                    peers.put(deviceId, new LanPeer(deviceId, deviceName, senderIp,
                            System.currentTimeMillis(), active));
                    changed = true;
                }
            }
            // Always reply so the sender can rediscover us immediately after a restart.
            // Without this, a restarted device waits up to 30 s (the broadcast interval)
            // before it hears our next broadcast and finally learns about us.
            sendUnicast(senderIp, buildHelloPayload());
            if (changed) {
                notifyPeersChanged();
            }
        } catch (Exception e) {
            Log.w(TAG, "handleDiscoveryPayload error for " + senderIp, e);
        }
    }

    private String buildHelloPayload() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("type",       "hello");
            obj.put("deviceId",   myDeviceId);
            obj.put("deviceName", myDeviceName);
            obj.put("active",     myActiveStatus);
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private void sendUnicast(String ip, String payload) {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] data = payload.getBytes(Charset.forName("UTF-8"));
            DatagramPacket packet = new DatagramPacket(
                    data, data.length,
                    InetAddress.getByName(ip), DISCOVERY_PORT);
            s.send(packet);
        } catch (Exception e) {
            Log.w(TAG, "sendUnicast to " + ip + " failed", e);
        }
    }

    private void notifyPeersChanged() {
        PeerListListener l = peerListListener;
        if (l != null) l.onPeersChanged(getPeers());
    }
}
