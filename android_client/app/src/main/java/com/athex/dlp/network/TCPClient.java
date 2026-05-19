package com.athex.dlp.network;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ATHEX DLP Enterprise - TCPClient
 * 
 * Advanced TCP connection handler for Android-to-Server communication.
 * Handles connection lifecycle, auto-reconnection, message queuing,
 * heartbeat monitoring, and bidirectional command processing.
 * 
 * Architecture:
 * - Persistent TCP socket connection to server
 * - Auto-reconnect with exponential backoff
 * - Message queue for offline buffering
 * - Heartbeat mechanism for connection health
 * - Thread-safe operations
 * - Battery-efficient design
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class TCPClient {
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final String TAG = "ATHEX_TCPClient";
    
    // Connection settings - WILL BE INJECTED DURING BUILD
    private String serverHost = "127.0.0.1";
    private int serverPort = 22533;
    
    // Timing constants (milliseconds)
    private static final int CONNECTION_TIMEOUT = 15000;    // 15 seconds
    private static final int READ_TIMEOUT = 30000;           // 30 seconds
    private static final int HEARTBEAT_INTERVAL = 30000;     // 30 seconds
    private static final int INITIAL_RECONNECT_DELAY = 2000; // 2 seconds
    private static final int MAX_RECONNECT_DELAY = 120000;   // 2 minutes
    private static final int RECONNECT_BACKOFF_MULTIPLIER = 2;
    private static final int MAX_RECONNECT_ATTEMPTS = 10;
    private static final int MESSAGE_QUEUE_MAX_SIZE = 500;
    private static final int SOCKET_BUFFER_SIZE = 65536;     // 64KB
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    // Context
    private final Context appContext;
    
    // Network components
    private Socket socket;
    private PrintWriter writer;
    private BufferedReader reader;
    private Thread connectionThread;
    private Thread heartbeatThread;
    private Thread messageProcessorThread;
    
    // State management
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private int reconnectAttempts = 0;
    private long lastSuccessfulConnection = 0;
    private long bytesSent = 0;
    private long bytesReceived = 0;
    
    // Message queue for offline buffering
    private final ConcurrentLinkedQueue<String> outgoingMessageQueue;
    private final ConcurrentLinkedQueue<String> incomingMessageQueue;
    
    // Handlers
    private final Handler mainHandler;
    private final Handler backgroundHandler;
    
    // Listeners
    private ConnectionListener connectionListener;
    private MessageListener messageListener;
    
    // Device info (lazy loaded)
    private String deviceModel;
    private String androidVersion;
    private String deviceId;
    private String deviceIpAddress;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    /**
     * Interface for connection state changes
     */
    public interface ConnectionListener {
        void onConnected(String serverInfo);
        void onDisconnected(String reason);
        void onConnectionFailed(String error, int attempt);
        void onReconnecting(int attempt, int delaySeconds);
    }
    
    /**
     * Interface for incoming messages
     */
    public interface MessageListener {
        void onMessageReceived(String message);
        void onCommandReceived(String command, String[] args);
        void onMessageSent(String message);
        void onMessageFailed(String message, String error);
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    /**
     * Primary constructor
     * @param context Application context
     * @param host Server hostname or IP
     * @param port Server port
     */
    public TCPClient(Context context, String host, int port) {
        this.appContext = context.getApplicationContext();
        this.serverHost = host;
        this.serverPort = port;
        
        // Initialize queues
        this.outgoingMessageQueue = new ConcurrentLinkedQueue<>();
        this.incomingMessageQueue = new ConcurrentLinkedQueue<>();
        
        // Initialize handlers
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.backgroundHandler = new Handler(Looper.getMainLooper());
        
        // Collect device information
        collectDeviceInfo();
        
        Log.d(TAG, "TCPClient initialized for " + host + ":" + port);
    }
    
    /**
     * Constructor with default host/port (will be injected)
     */
    public TCPClient(Context context) {
        this(context, "127.0.0.1", 22533);
    }
    
    // ============================================================
    // CONNECTION LIFECYCLE
    // ============================================================
    
    /**
     * Start the connection to server
     * Begins connection thread and auto-reconnection logic
     */
    public synchronized void connect() {
        if (isRunning.get()) {
            Log.w(TAG, "Already running, ignoring connect request");
            return;
        }
        
        Log.i(TAG, "Starting connection to " + serverHost + ":" + serverPort);
        isRunning.set(true);
        reconnectAttempts = 0;
        
        // Start connection thread
        connectionThread = new Thread(new ConnectionRunnable(), "ATHEX-Connection");
        connectionThread.setDaemon(true);
        connectionThread.start();
    }
    
    /**
     * Disconnect from server and stop all threads
     */
    public synchronized void disconnect() {
        Log.i(TAG, "Disconnecting from server...");
        isRunning.set(false);
        isConnected.set(false);
        isReconnecting.set(false);
        
        // Close socket
        closeSocket();
        
        // Interrupt threads
        if (connectionThread != null && connectionThread.isAlive()) {
            connectionThread.interrupt();
        }
        if (heartbeatThread != null && heartbeatThread.isAlive()) {
            heartbeatThread.interrupt();
        }
        if (messageProcessorThread != null && messageProcessorThread.isAlive()) {
            messageProcessorThread.interrupt();
        }
        
        notifyDisconnected("User initiated disconnect");
    }
    
    /**
     * Reconnect to server (used after configuration changes)
     */
    public void reconnect() {
        disconnect();
        // Small delay before reconnecting
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        connect();
    }
    
    // ============================================================
    // CONNECTION RUNNABLE (Main Connection Loop)
    // ============================================================
    
    /**
     * Main connection runnable that handles the connection lifecycle
     */
    private class ConnectionRunnable implements Runnable {
        @Override
        public void run() {
            Log.d(TAG, "Connection thread started");
            
            while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // Attempt connection
                    boolean connected = attemptConnection();
                    
                    if (connected) {
                        // Reset reconnect attempts on success
                        reconnectAttempts = 0;
                        lastSuccessfulConnection = System.currentTimeMillis();
                        
                        // Start heartbeat
                        startHeartbeat();
                        
                        // Start message processor
                        startMessageProcessor();
                        
                        // Process outgoing messages
                        processOutgoingQueue();
                        
                        // Read incoming messages (blocking)
                        readIncomingMessages();
                    }
                    
                } catch (InterruptedException e) {
                    Log.d(TAG, "Connection thread interrupted");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Connection error: " + e.getMessage(), e);
                }
                
                // If we get here, connection was lost
                if (isRunning.get()) {
                    handleReconnection();
                }
            }
            
            Log.d(TAG, "Connection thread ended");
        }
    }
    
    /**
     * Attempt to establish TCP connection
     * @return true if connected successfully
     */
    private boolean attemptConnection() throws InterruptedException {
        Log.d(TAG, "Attempting connection to " + serverHost + ":" + serverPort);
        
        try {
            // Create socket with timeout
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(READ_TIMEOUT);
            socket.setSendBufferSize(SOCKET_BUFFER_SIZE);
            socket.setReceiveBufferSize(SOCKET_BUFFER_SIZE);
            
            // Connect with timeout
            InetSocketAddress address = new InetSocketAddress(serverHost, serverPort);
            socket.connect(address, CONNECTION_TIMEOUT);
            
            // Create writer (auto-flush enabled)
            writer = new PrintWriter(
                new BufferedWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8")
                ), true
            );
            
            // Create reader
            reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8")
            );
            
            // Mark as connected
            isConnected.set(true);
            
            Log.i(TAG, "✅ Connected to server successfully!");
            
            // Send handshake with device info
            sendHandshake();
            
            // Notify listeners on main thread
            mainHandler.post(() -> {
                String serverInfo = serverHost + ":" + serverPort;
                notifyConnected(serverInfo);
            });
            
            return true;
            
        } catch (SocketTimeoutException e) {
            Log.e(TAG, "Connection timeout");
            notifyConnectionFailed("Connection timeout", reconnectAttempts);
            return false;
            
        } catch (IOException e) {
            Log.e(TAG, "Connection failed: " + e.getMessage());
            notifyConnectionFailed(e.getMessage(), reconnectAttempts);
            return false;
        }
    }
    
    /**
     * Handle reconnection with exponential backoff
     */
    private void handleReconnection() {
        if (!isRunning.get()) return;
        
        isConnected.set(false);
        isReconnecting.set(true);
        reconnectAttempts++;
        
        // Check max attempts
        if (reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Max reconnection attempts reached");
            notifyDisconnected("Max reconnection attempts reached");
            isRunning.set(false);
            return;
        }
        
        // Calculate delay with exponential backoff
        long delay = calculateReconnectDelay();
        
        Log.w(TAG, "Reconnecting in " + (delay / 1000) + " seconds (Attempt " + 
              reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
        
        notifyReconnecting(reconnectAttempts, (int)(delay / 1000));
        
        // Close old socket
        closeSocket();
        
        // Wait before reconnecting
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        
        isReconnecting.set(false);
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private long calculateReconnectDelay() {
        long delay = INITIAL_RECONNECT_DELAY * 
                     (long) Math.pow(RECONNECT_BACKOFF_MULTIPLIER, reconnectAttempts - 1);
        
        // Cap at maximum delay
        return Math.min(delay, MAX_RECONNECT_DELAY);
    }
    
    // ============================================================
    // HEARTBEAT MECHANISM
    // ============================================================
    
    /**
     * Start heartbeat thread to keep connection alive
     */
    private void startHeartbeat() {
        if (heartbeatThread != null && heartbeatThread.isAlive()) {
            heartbeatThread.interrupt();
        }
        
        heartbeatThread = new Thread(() -> {
            Log.d(TAG, "Heartbeat thread started");
            
            while (isConnected.get() && isRunning.get() && 
                   !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL);
                    
                    if (isConnected.get()) {
                        sendMessage("HEARTBEAT|" + System.currentTimeMillis());
                    }
                } catch (InterruptedException e) {
                    Log.d(TAG, "Heartbeat thread interrupted");
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            Log.d(TAG, "Heartbeat thread ended");
        }, "ATHEX-Heartbeat");
        
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }
    
    // ============================================================
    // MESSAGE PROCESSING
    // ============================================================
    
    /**
     * Start message processor thread
     */
    private void startMessageProcessor() {
        if (messageProcessorThread != null && messageProcessorThread.isAlive()) {
            messageProcessorThread.interrupt();
        }
        
        messageProcessorThread = new Thread(() -> {
            Log.d(TAG, "Message processor started");
            
            while (isConnected.get() && isRunning.get() && 
                   !Thread.currentThread().isInterrupted()) {
                try {
                    // Process outgoing queue
                    processOutgoingQueue();
                    
                    // Small sleep to prevent CPU spinning
                    Thread.sleep(100);
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            
            Log.d(TAG, "Message processor ended");
        }, "ATHEX-MessageProcessor");
        
        messageProcessorThread.setDaemon(true);
        messageProcessorThread.start();
    }
    
    /**
     * Process outgoing message queue
     */
    private void processOutgoingQueue() {
        if (!isConnected.get() || writer == null) {
            return;
        }
        
        String message;
        int processed = 0;
        int maxBatch = 50; // Process max 50 messages per batch
        
        while ((message = outgoingMessageQueue.poll()) != null && processed < maxBatch) {
            try {
                writer.println(message);
                writer.flush();
                bytesSent += message.length();
                processed++;
                
                notifyMessageSent(message);
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to send message: " + e.getMessage());
                // Re-queue the message
                outgoingMessageQueue.offer(message);
                notifyMessageFailed(message, e.getMessage());
                break;
            }
        }
    }
    
    /**
     * Read incoming messages from server (blocking)
     */
    private void readIncomingMessages() {
        try {
            String line;
            while (isConnected.get() && isRunning.get() && 
                   (line = reader.readLine()) != null) {
                
                bytesReceived += line.length();
                final String message = line;
                
                // Process on background thread
                backgroundHandler.post(() -> processIncomingMessage(message));
            }
            
        } catch (SocketTimeoutException e) {
            // Timeout is expected, continue
            Log.d(TAG, "Socket timeout - no data received");
            
        } catch (IOException e) {
            Log.e(TAG, "Connection lost while reading: " + e.getMessage());
            
        } finally {
            // Connection was lost
            if (isConnected.get()) {
                isConnected.set(false);
                notifyDisconnected("Connection lost");
            }
        }
    }
    
    /**
     * Process incoming message from server
     */
    private void processIncomingMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        Log.d(TAG, "Received: " + message.substring(0, Math.min(100, message.length())));
        
        // Notify general message listener
        notifyMessageReceived(message);
        
        // Parse command
        if (message.contains("|")) {
            String[] parts = message.split("\\|", 2);
            String command = parts[0];
            String[] args = parts.length > 1 ? parts[1].split("\\|") : new String[0];
            
            notifyCommandReceived(command, args);
        }
    }
    
    // ============================================================
    // MESSAGE SENDING API
    // ============================================================
    
    /**
     * Send message to server (async)
     * @param message Message to send
     * @return true if queued successfully
     */
    public boolean sendMessage(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        
        // Check queue size
        if (outgoingMessageQueue.size() >= MESSAGE_QUEUE_MAX_SIZE) {
            Log.w(TAG, "Message queue full, dropping oldest message");
            outgoingMessageQueue.poll(); // Remove oldest
        }
        
        boolean queued = outgoingMessageQueue.offer(message);
        
        if (queued) {
            Log.d(TAG, "Message queued: " + message.substring(0, Math.min(50, message.length())));
        }
        
        return queued;
    }
    
    /**
     * Send message immediately (blocking, use carefully)
     * @param message Message to send
     * @return true if sent successfully
     */
    public boolean sendMessageImmediate(String message) {
        if (!isConnected.get() || writer == null) {
            return false;
        }
        
        try {
            writer.println(message);
            writer.flush();
            bytesSent += message.length();
            notifyMessageSent(message);
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to send immediate message: " + e.getMessage());
            notifyMessageFailed(message, e.getMessage());
            return false;
        }
    }
    
    /**
     * Send formatted data message
     * @param command Command type
     * @param data Data payload
     */
    public void sendData(String command, String data) {
        String message = command + "|" + data;
        sendMessage(message);
    }
    
    // ============================================================
    // HANDSHAKE
    // ============================================================
    
    /**
     * Send initial handshake with device information
     */
    private void sendHandshake() {
        StringBuilder handshake = new StringBuilder();
        handshake.append("DEVICE_INFO");
        handshake.append("|").append(deviceModel != null ? deviceModel : Build.MODEL);
        handshake.append("|").append(androidVersion != null ? androidVersion : Build.VERSION.RELEASE);
        handshake.append("|").append(deviceId != null ? deviceId : "unknown");
        handshake.append("|").append(deviceIpAddress != null ? deviceIpAddress : "0.0.0.0");
        handshake.append("|").append(Build.MANUFACTURER);
        handshake.append("|").append(Build.PRODUCT);
        
        sendMessageImmediate(handshake.toString());
        Log.i(TAG, "Handshake sent: " + handshake.toString());
    }
    
    // ============================================================
    // DEVICE INFO
    // ============================================================
    
    /**
     * Collect device information
     */
    private void collectDeviceInfo() {
        try {
            deviceModel = Build.MODEL;
            androidVersion = Build.VERSION.RELEASE;
            deviceId = Settings.Secure.getString(
                appContext.getContentResolver(),
                Settings.Secure.ANDROID_ID
            );
            
            // Get local IP (best effort)
            deviceIpAddress = "0.0.0.0"; // Will be updated when network is available
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to collect device info: " + e.getMessage());
        }
    }
    
    // ============================================================
    // LISTENER SETTERS
    // ============================================================
    
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }
    
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }
    
    // ============================================================
    // NOTIFICATION HELPERS
    // ============================================================
    
    private void notifyConnected(String serverInfo) {
        if (connectionListener != null) {
            connectionListener.onConnected(serverInfo);
        }
    }
    
    private void notifyDisconnected(String reason) {
        if (connectionListener != null) {
            connectionListener.onDisconnected(reason);
        }
    }
    
    private void notifyConnectionFailed(String error, int attempt) {
        if (connectionListener != null) {
            connectionListener.onConnectionFailed(error, attempt);
        }
    }
    
    private void notifyReconnecting(int attempt, int delaySeconds) {
        if (connectionListener != null) {
            connectionListener.onReconnecting(attempt, delaySeconds);
        }
    }
    
    private void notifyMessageReceived(String message) {
        if (messageListener != null) {
            messageListener.onMessageReceived(message);
        }
    }
    
    private void notifyCommandReceived(String command, String[] args) {
        if (messageListener != null) {
            messageListener.onCommandReceived(command, args);
        }
    }
    
    private void notifyMessageSent(String message) {
        if (messageListener != null) {
            messageListener.onMessageSent(message);
        }
    }
    
    private void notifyMessageFailed(String message, String error) {
        if (messageListener != null) {
            messageListener.onMessageFailed(message, error);
        }
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    /**
     * Close socket and clean up resources
     */
    private void closeSocket() {
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
            if (reader != null) {
                reader.close();
                reader = null;
            }
            if (socket != null && !socket.isClosed()) {
                socket.close();
                socket = null;
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket: " + e.getMessage());
        }
    }
    
    /**
     * Update server configuration (for runtime changes)
     */
    public void updateServerConfig(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        Log.d(TAG, "Server config updated: " + host + ":" + port);
    }
    
    // ============================================================
    // STATE GETTERS
    // ============================================================
    
    public boolean isConnected() {
        return isConnected.get();
    }
    
    public boolean isReconnecting() {
        return isReconnecting.get();
    }
    
    public int getReconnectAttempts() {
        return reconnectAttempts;
    }
    
    public long getBytesSent() {
        return bytesSent;
    }
    
    public long getBytesReceived() {
        return bytesReceived;
    }
    
    public int getQueueSize() {
        return outgoingMessageQueue.size();
    }
    
    public long getLastSuccessfulConnection() {
        return lastSuccessfulConnection;
    }
    
    public String getServerHost() {
        return serverHost;
    }
    
    public int getServerPort() {
        return serverPort;
    }
}