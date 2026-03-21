package com.kitchenboard.chat;

import android.content.Context;
import android.util.Log;

import com.kitchenboard.notifications.AppNotification;
import com.kitchenboard.notifications.NotificationStore;
import com.kitchenboard.update.UpdateLogger;
import com.kitchenboard.R;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP server that receives chat messages sent directly over the local network
 * by other KitchenBoard devices running in LAN mode.
 *
 * <p>The server listens on {@value #CHAT_PORT}.  Each connection carries
 * exactly one UTF-8 JSON line.  The payload is either a regular
 * {@link ChatMessage} (no {@code type} field) or an ACK packet:
 * <pre>{@code {"type":"ack","msgId":12345,"status":1}}</pre>
 * where status is {@link ChatMessage#STATUS_DELIVERED} (1) or
 * {@link ChatMessage#STATUS_READ} (2).
 *
 * <p>Received messages are stored in the local {@link ChatDatabaseHelper} and
 * a delivered-ACK is sent back to the sender automatically. A summary
 * notification is posted via {@link NotificationStore}.
 *
 * <p>Start the server with {@link #start(Context, String)} and stop it with
 * {@link #stop()}.  It is safe to call {@link #start} multiple times; a
 * second call is a no-op if the server is already running.
 */
public class LanChatServer {

    private static final String TAG       = "LanChatServer";
    /** TCP port on which the server accepts incoming LAN chat messages. */
    static final int CHAT_PORT   = 47475;

    private static LanChatServer instance;

    /** Listener notified when a new message is stored (called on the IO thread). */
    public interface MessageReceivedListener {
        void onMessageReceived(ChatMessage msg);
    }

    /** Listener notified when an ACK (delivered or read receipt) arrives. */
    public interface AckReceivedListener {
        /** Called with the original message ID and its new delivery status. */
        void onAckReceived(long msgId, int status);
    }

    private final AtomicBoolean running  = new AtomicBoolean(false);
    private final AtomicLong    lanIdSeq = new AtomicLong(
            -(System.currentTimeMillis() / 1000L));   // negative IDs avoid collision with server IDs

    private ServerSocket serverSocket;
    private MessageReceivedListener messageListener;
    private AckReceivedListener ackListener;
    private final ExecutorService acceptExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService ioExecutor     = Executors.newCachedThreadPool();

    /** This device's ID (used to filter self-directed messages). */
    private String myDeviceId = "";

    private LanChatServer() {}

    /** Returns the singleton instance. */
    public static synchronized LanChatServer getInstance() {
        if (instance == null) instance = new LanChatServer();
        return instance;
    }

    /** Sets a listener notified (on an IO thread) when a new message arrives. */
    public void setMessageReceivedListener(MessageReceivedListener l) {
        this.messageListener = l;
    }

    /** Sets a listener notified (on an IO thread) when a delivery/read ACK arrives. */
    public void setAckReceivedListener(AckReceivedListener l) {
        this.ackListener = l;
    }

    /**
     * Starts the TCP listener.  Safe to call multiple times.
     *
     * @param context    Application context
     * @param myDeviceId This device's sender ID for self-loop prevention
     */
    public void start(Context context, String myDeviceId) {
        this.myDeviceId = myDeviceId;
        if (!running.compareAndSet(false, true)) return;
        acceptExecutor.submit(() -> acceptLoop(context.getApplicationContext()));
    }

    /** Stops the server and closes the listening socket. */
    public void stop() {
        running.set(false);
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (Exception ignored) {}
    }

    /** Returns {@code true} if the server is currently listening. */
    public boolean isRunning() {
        return running.get();
    }

    // ── Accept loop ───────────────────────────────────────────────────────────

    private void acceptLoop(Context context) {
        try {
            serverSocket = new ServerSocket(CHAT_PORT);
            Log.i(TAG, "LAN chat server listening on port " + CHAT_PORT);
            while (running.get()) {
                try {
                    Socket client = serverSocket.accept();
                    ioExecutor.submit(() -> handleClient(context, client));
                } catch (SocketException e) {
                    if (running.get()) Log.w(TAG, "accept error", e);
                }
            }
        } catch (Exception e) {
            if (running.get()) UpdateLogger.logError(context, "LanChatServer: accept loop", e);
        } finally {
            running.set(false);
        }
    }

    private void handleClient(Context context, Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(10_000);
            String senderIp = s.getInetAddress().getHostAddress();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), Charset.forName("UTF-8")));
            String line = reader.readLine();
            if (line == null || line.trim().isEmpty()) return;

            JSONObject obj = new JSONObject(line.trim());

            // ── ACK packet ──────────────────────────────────────────────────
            if ("ack".equals(obj.optString("type"))) {
                long msgId  = obj.optLong("msgId", 0L);
                int  status = obj.optInt("status", ChatMessage.STATUS_DELIVERED);
                if (msgId != 0) {
                    ChatDatabaseHelper db = new ChatDatabaseHelper(context);
                    try {
                        db.updateDeliveryStatus(msgId, status);
                    } finally {
                        db.close();
                    }
                    AckReceivedListener al = ackListener;
                    if (al != null) al.onAckReceived(msgId, status);
                }
                return;
            }

            // ── Regular chat message ─────────────────────────────────────
            ChatMessage msg = ChatMessage.fromJson(obj);

            // Ignore messages apparently sent by ourselves (loop-back)
            if (myDeviceId.equals(msg.senderId)) return;

            // Ignore messages directed to someone else
            if (!msg.recipientId.isEmpty() && !msg.recipientId.equals(myDeviceId)) return;

            // Assign a local ID if none
            long localId = (msg.id == 0 || msg.id == -1)
                    ? lanIdSeq.getAndDecrement()
                    : msg.id;
            ChatMessage stored = new ChatMessage(
                    localId,
                    msg.senderId,
                    msg.senderName,
                    msg.recipientId,
                    msg.recipientName,
                    msg.message,
                    msg.timestampMs > 0 ? msg.timestampMs : System.currentTimeMillis(),
                    false);

            ChatDatabaseHelper db = new ChatDatabaseHelper(context);
            try {
                db.upsert(stored);
                db.pruneOldMessages(200);
            } finally {
                db.close();
            }

            // Send delivered-ACK back to the sender using the original message ID
            final long originalId = msg.id;
            if (senderIp != null && originalId != 0 && originalId != -1) {
                ioExecutor.submit(() -> LanChatClient.sendAck(senderIp, originalId,
                        ChatMessage.STATUS_DELIVERED));
            }

            // Post in-app notification
            String preview = stored.senderName + ": " + stored.message;
            if (preview.length() > 80) preview = preview.substring(0, 80) + "…";
            NotificationStore.getInstance(context).addNotification(
                    AppNotification.TYPE_CHAT,
                    context.getString(R.string.chat_notif_new_singular, 1),
                    preview,
                    -1);

            MessageReceivedListener l = messageListener;
            if (l != null) l.onMessageReceived(stored);

        } catch (Exception e) {
            Log.w(TAG, "handleClient error", e);
        }
    }
}
