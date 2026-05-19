package com.athex.dlp;

import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.util.Log;

import com.athex.dlp.services.MainService;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ATHEX DLP Enterprise - ATHEXApplication
 * 
 * Custom Application class that initializes global components:
 * - Global error handler
 * - Notification channels
 * - Network monitoring
 * - Service auto-start
 * - Configuration management
 * - Build info tracking
 * 
 * This is the FIRST class that runs when the app starts.
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class ATHEXApplication extends Application {
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final String TAG = "ATHEX_Application";
    
    // Notification Channels
    public static final String CHANNEL_SERVICE = "athex_dlp_service";
    public static final String CHANNEL_NOTIFICATIONS = "athex_dlp_notifications";
    public static final String CHANNEL_ALERTS = "athex_dlp_alerts";
    public static final String CHANNEL_SILENT = "athex_dlp_silent";
    
    // Preferences
    private static final String PREF_NAME = "athex_dlp_prefs";
    private static final String KEY_FIRST_LAUNCH = "first_launch_time";
    private static final String KEY_CRASH_COUNT = "crash_count";
    private static final String KEY_LAST_CRASH = "last_crash_time";
    private static final String KEY_APP_VERSION = "app_version";
    private static final String KEY_BUILD_FLAVOR = "build_flavor";
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    // Singleton instance
    private static ATHEXApplication instance;
    
    // Managers
    private NotificationManager notificationManager;
    private ConnectivityManager connectivityManager;
    private SharedPreferences preferences;
    
    // Handlers
    private Handler mainHandler;
    
    // State
    private boolean isNetworkAvailable = false;
    private boolean isAppInForeground = false;
    private long appStartTime;
    private int crashCount = 0;
    
    // Network callback
    private ConnectivityManager.NetworkCallback networkCallback;
    
    // Original default uncaught exception handler
    private Thread.UncaughtExceptionHandler originalUncaughtExceptionHandler;
    
    // ============================================================
    // LIFECYCLE
    // ============================================================
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // Set singleton instance
        instance = this;
        
        // Record start time
        appStartTime = System.currentTimeMillis();
        
        Log.i(TAG, "============================================");
        Log.i(TAG, "ATHEXApplication onCreate()");
        Log.i(TAG, "============================================");
        
        // Initialize components in order
        initializePreferences();
        initializeHandlers();
        initializeNotificationChannels();
        initializeNetworkMonitoring();
        initializeCrashHandler();
        logDeviceInfo();
        checkFirstLaunch();
        
        // Apply StrictMode in debug builds
        if (isDebugBuild()) {
            enableStrictMode();
        }
        
        Log.i(TAG, "Application initialized successfully");
        Log.i(TAG, "App version: " + getAppVersion());
        Log.i(TAG, "Build flavor: " + getBuildFlavor());
        
        // Schedule service start after initialization
        scheduleServiceStart();
    }
    
    @Override
    public void onTerminate() {
        super.onTerminate();
        
        // Unregister network callback
        if (networkCallback != null && connectivityManager != null) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback: " + e.getMessage());
            }
        }
        
        Log.i(TAG, "Application terminated");
    }
    
    // ============================================================
    // INITIALIZATION METHODS
    // ============================================================
    
    /**
     * Initialize shared preferences
     */
    private void initializePreferences() {
        preferences = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        
        // Load crash count
        crashCount = preferences.getInt(KEY_CRASH_COUNT, 0);
        
        Log.d(TAG, "Preferences initialized");
    }
    
    /**
     * Initialize handlers
     */
    private void initializeHandlers() {
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Handlers initialized");
    }
    
    /**
     * Initialize all notification channels
     */
    private void initializeNotificationChannels() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Channel 1: Service (Low priority, no sound)
            NotificationChannel serviceChannel = new NotificationChannel(
                CHANNEL_SERVICE,
                "Service Status",
                NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Background service status");
            serviceChannel.setShowBadge(false);
            serviceChannel.enableLights(false);
            serviceChannel.enableVibration(false);
            serviceChannel.setSound(null, null);
            notificationManager.createNotificationChannel(serviceChannel);
            
            // Channel 2: Notifications (Default priority)
            NotificationChannel notifChannel = new NotificationChannel(
                CHANNEL_NOTIFICATIONS,
                "Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            );
            notifChannel.setDescription("Mirrored device notifications");
            notifChannel.setShowBadge(true);
            notificationManager.createNotificationChannel(notifChannel);
            
            // Channel 3: Alerts (High priority)
            NotificationChannel alertChannel = new NotificationChannel(
                CHANNEL_ALERTS,
                "Alerts",
                NotificationManager.IMPORTANCE_HIGH
            );
            alertChannel.setDescription("Important system alerts");
            alertChannel.setShowBadge(true);
            alertChannel.enableVibration(true);
            notificationManager.createNotificationChannel(alertChannel);
            
            // Channel 4: Silent (Min priority, no interruption)
            NotificationChannel silentChannel = new NotificationChannel(
                CHANNEL_SILENT,
                "Silent Updates",
                NotificationManager.IMPORTANCE_MIN
            );
            silentChannel.setDescription("Silent background updates");
            silentChannel.setShowBadge(false);
            silentChannel.enableLights(false);
            silentChannel.enableVibration(false);
            silentChannel.setSound(null, null);
            notificationManager.createNotificationChannel(silentChannel);
            
            Log.d(TAG, "4 notification channels created");
        }
    }
    
    /**
     * Initialize network monitoring
     */
    private void initializeNetworkMonitoring() {
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        
        if (connectivityManager != null) {
            // Check current network state
            updateNetworkState();
            
            // Register network callback for changes
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                networkCallback = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(@NonNull Network network) {
                        super.onAvailable(network);
                        isNetworkAvailable = true;
                        Log.d(TAG, "Network available");
                        onNetworkStateChanged(true);
                    }
                    
                    @Override
                    public void onLost(@NonNull Network network) {
                        super.onLost(network);
                        isNetworkAvailable = false;
                        Log.d(TAG, "Network lost");
                        onNetworkStateChanged(false);
                    }
                    
                    @Override
                    public void onCapabilitiesChanged(@NonNull Network network, 
                                                      @NonNull NetworkCapabilities capabilities) {
                        super.onCapabilitiesChanged(network, capabilities);
                        updateNetworkState();
                        Log.d(TAG, "Network capabilities changed");
                    }
                };
                
                NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
                
                connectivityManager.registerNetworkCallback(request, networkCallback);
                
                Log.d(TAG, "Network monitoring initialized");
            } else {
                // Legacy network monitoring via broadcast receiver
                registerLegacyNetworkReceiver();
            }
        }
    }
    
    /**
     * Register legacy network broadcast receiver (pre-Android 7)
     */
    private void registerLegacyNetworkReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateNetworkState();
                onNetworkStateChanged(isNetworkAvailable);
            }
        }, filter);
        
        Log.d(TAG, "Legacy network receiver registered");
    }
    
    /**
     * Update current network state
     */
    private void updateNetworkState() {
        if (connectivityManager != null) {
            NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
            isNetworkAvailable = activeNetwork != null && activeNetwork.isConnectedOrConnecting();
        }
    }
    
    /**
     * Handle network state changes
     */
    private void onNetworkStateChanged(boolean available) {
        if (available) {
            Log.d(TAG, "Network connected - checking service");
            
            // Notify service to reconnect if running
            Intent intent = new Intent("com.athex.dlp.NETWORK_AVAILABLE");
            intent.putExtra("available", true);
            sendBroadcast(intent);
        } else {
            Log.d(TAG, "Network disconnected");
            
            Intent intent = new Intent("com.athex.dlp.NETWORK_AVAILABLE");
            intent.putExtra("available", false);
            sendBroadcast(intent);
        }
    }
    
    /**
     * Initialize global crash handler
     */
    private void initializeCrashHandler() {
        originalUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler();
        
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Log crash
            logCrash(throwable);
            
            // Save crash info
            saveCrashInfo(throwable);
            
            // Attempt to restart service
            attemptServiceRestart();
            
            // Call original handler
            if (originalUncaughtExceptionHandler != null) {
                originalUncaughtExceptionHandler.uncaughtException(thread, throwable);
            }
        });
        
        Log.d(TAG, "Crash handler initialized");
    }
    
    /**
     * Log crash details
     */
    private void logCrash(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        
        Log.e(TAG, "=== APP CRASH ===");
        Log.e(TAG, "Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date()));
        Log.e(TAG, "Thread: " + Thread.currentThread().getName());
        Log.e(TAG, "Exception: " + throwable.getClass().getName());
        Log.e(TAG, "Message: " + throwable.getMessage());
        Log.e(TAG, "Stack Trace:\n" + sw.toString());
        Log.e(TAG, "=================");
    }
    
    /**
     * Save crash information to preferences
     */
    private void saveCrashInfo(Throwable throwable) {
        crashCount++;
        
        preferences.edit()
            .putInt(KEY_CRASH_COUNT, crashCount)
            .putLong(KEY_LAST_CRASH, System.currentTimeMillis())
            .apply();
        
        Log.w(TAG, "Crash #" + crashCount + " recorded");
    }
    
    /**
     * Attempt to restart service after crash
     */
    private void attemptServiceRestart() {
        try {
            Intent serviceIntent = new Intent(this, MainService.class);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            
            Log.i(TAG, "Service restart attempted after crash");
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart service after crash: " + e.getMessage());
        }
    }
    
    /**
     * Enable StrictMode for debug builds
     */
    private void enableStrictMode() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
            .detectDiskReads()
            .detectDiskWrites()
            .detectNetwork()
            .penaltyLog()
            .build());
        
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
            .detectLeakedSqlLiteObjects()
            .detectLeakedClosableObjects()
            .penaltyLog()
            .build());
        
        Log.d(TAG, "StrictMode enabled (debug build)");
    }
    
    // ============================================================
    // DEVICE & APP INFO
    // ============================================================
    
    /**
     * Log device and app information
     */
    private void logDeviceInfo() {
        Log.i(TAG, "=== DEVICE INFO ===");
        Log.i(TAG, "Manufacturer: " + Build.MANUFACTURER);
        Log.i(TAG, "Model: " + Build.MODEL);
        Log.i(TAG, "Product: " + Build.PRODUCT);
        Log.i(TAG, "Brand: " + Build.BRAND);
        Log.i(TAG, "Device: " + Build.DEVICE);
        Log.i(TAG, "Hardware: " + Build.HARDWARE);
        Log.i(TAG, "Android: " + Build.VERSION.RELEASE);
        Log.i(TAG, "SDK: " + Build.VERSION.SDK_INT);
        Log.i(TAG, "App Version: " + getAppVersion());
        Log.i(TAG, "Package: " + getPackageName());
        Log.i(TAG, "Debug: " + isDebugBuild());
        Log.i(TAG, "===================");
    }
    
    /**
     * Check if this is the first launch
     */
    private void checkFirstLaunch() {
        long firstLaunch = preferences.getLong(KEY_FIRST_LAUNCH, 0);
        
        if (firstLaunch == 0) {
            // First launch
            firstLaunch = System.currentTimeMillis();
            preferences.edit()
                .putLong(KEY_FIRST_LAUNCH, firstLaunch)
                .putString(KEY_APP_VERSION, getAppVersion())
                .apply();
            
            Log.i(TAG, "First launch detected at: " + 
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(firstLaunch)));
        } else {
            Log.d(TAG, "Previous launch: " + 
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(firstLaunch)));
            
            // Update app version if changed
            String savedVersion = preferences.getString(KEY_APP_VERSION, "");
            String currentVersion = getAppVersion();
            
            if (!savedVersion.equals(currentVersion)) {
                Log.i(TAG, "App updated from " + savedVersion + " to " + currentVersion);
                preferences.edit().putString(KEY_APP_VERSION, currentVersion).apply();
            }
        }
    }
    
    /**
     * Schedule service start after app initialization
     */
    private void scheduleServiceStart() {
        mainHandler.postDelayed(() -> {
            try {
                Intent serviceIntent = new Intent(this, MainService.class);
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                
                Log.i(TAG, "Service auto-started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to auto-start service: " + e.getMessage());
            }
        }, 2000); // 2 second delay
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    /**
     * Get app version name
     */
    public String getAppVersion() {
        try {
            PackageInfo packageInfo = getPackageManager()
                .getPackageInfo(getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }
    
    /**
     * Get app version code
     */
    public int getAppVersionCode() {
        try {
            PackageInfo packageInfo = getPackageManager()
                .getPackageInfo(getPackageName(), 0);
            return packageInfo.versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }
    
    /**
     * Check if this is a debug build
     */
    public boolean isDebugBuild() {
        try {
            ApplicationInfo appInfo = getPackageManager()
                .getApplicationInfo(getPackageName(), 0);
            return (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Get build flavor from metadata
     */
    public String getBuildFlavor() {
        try {
            ApplicationInfo appInfo = getPackageManager()
                .getApplicationInfo(getPackageName(), PackageManager.GET_META_DATA);
            
            if (appInfo.metaData != null) {
                return appInfo.metaData.getString("com.athex.dlp.BUILD_TYPE", "unknown");
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Failed to get build flavor: " + e.getMessage());
        }
        return "unknown";
    }
    
    /**
     * Get app uptime in milliseconds
     */
    public long getUptime() {
        return System.currentTimeMillis() - appStartTime;
    }
    
    /**
     * Get app uptime formatted string
     */
    public String getUptimeFormatted() {
        long uptime = getUptime();
        long seconds = (uptime / 1000) % 60;
        long minutes = (uptime / (1000 * 60)) % 60;
        long hours = (uptime / (1000 * 60 * 60)) % 24;
        long days = (uptime / (1000 * 60 * 60 * 24));
        
        if (days > 0) {
            return String.format(Locale.getDefault(), "%dd %dh %dm %ds", days, hours, minutes, seconds);
        } else if (hours > 0) {
            return String.format(Locale.getDefault(), "%dh %dm %ds", hours, minutes, seconds);
        } else {
            return String.format(Locale.getDefault(), "%dm %ds", minutes, seconds);
        }
    }
    
    // ============================================================
    // GLOBAL STATE METHODS
    // ============================================================
    
    /**
     * Set app foreground state
     */
    public void setAppInForeground(boolean foreground) {
        this.isAppInForeground = foreground;
        
        Intent intent = new Intent("com.athex.dlp.APP_STATE");
        intent.putExtra("foreground", foreground);
        sendBroadcast(intent);
    }
    
    /**
     * Check if app is in foreground
     */
    public boolean isAppInForeground() {
        return isAppInForeground;
    }
    
    /**
     * Check if network is available
     */
    public boolean isNetworkAvailable() {
        return isNetworkAvailable;
    }
    
    /**
     * Get crash count
     */
    public int getCrashCount() {
        return crashCount;
    }
    
    /**
     * Get notification manager
     */
    public NotificationManager getNotificationManager() {
        return notificationManager;
    }
    
    /**
     * Get main handler
     */
    public Handler getMainHandler() {
        return mainHandler;
    }
    
    /**
     * Get preferences
     */
    public SharedPreferences getPreferences() {
        return preferences;
    }
    
    // ============================================================
    // SINGLETON ACCESS
    // ============================================================
    
    /**
     * Get singleton application instance
     */
    public static ATHEXApplication getInstance() {
        return instance;
    }
    
    /**
     * Get application context (convenience method)
     */
    public static Context getAppContext() {
        return instance != null ? instance.getApplicationContext() : null;
    }
}