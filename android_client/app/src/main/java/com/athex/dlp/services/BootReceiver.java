package com.athex.dlp.services;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ATHEX DLP Enterprise - BootReceiver
 * 
 * Advanced boot-time receiver that ensures the background service
 * starts automatically when the device boots up. Implements multiple
 * fallback mechanisms to guarantee service persistence.
 * 
 * Features:
 * - BOOT_COMPLETED intent handling
 * - Delayed start for system stability
 * - WakeLock to ensure execution
 * - AlarmManager backup scheduling
 * - Multi-manufacturer compatibility
 * - Persistence verification
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class BootReceiver extends BroadcastReceiver {
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    private static final String TAG = "ATHEX_BootReceiver";
    
    // Timing constants
    private static final long INITIAL_DELAY_MS = 15000;      // 15 seconds initial delay
    private static final long VERIFICATION_DELAY_MS = 60000;  // 1 minute verification
    private static final long RETRY_DELAY_MS = 30000;         // 30 seconds retry
    private static final long ALARM_INTERVAL_MS = 300000;     // 5 minutes alarm backup
    private static final int MAX_RETRY_ATTEMPTS = 3;
    
    // Preferences
    private static final String PREF_NAME = "athex_dlp_boot";
    private static final String KEY_LAST_BOOT_TIME = "last_boot_time";
    private static final String KEY_BOOT_COUNT = "boot_count";
    private static final String KEY_SERVICE_START_COUNT = "service_start_count";
    private static final String KEY_LAST_SERVICE_START = "last_service_start";
    
    // Intent actions
    private static final String ACTION_VERIFY_SERVICE = "com.athex.dlp.VERIFY_SERVICE";
    private static final String ACTION_RETRY_START = "com.athex.dlp.RETRY_START";
    private static final String ACTION_ALARM_BACKUP = "com.athex.dlp.ALARM_BACKUP";
    
    // ============================================================
    // RECEIVE METHOD
    // ============================================================
    
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            Log.e(TAG, "Received null intent");
            return;
        }
        
        String action = intent.getAction();
        
        if (action == null) {
            Log.e(TAG, "Received intent with null action");
            return;
        }
        
        Log.i(TAG, "========================================");
        Log.i(TAG, "BootReceiver triggered");
        Log.i(TAG, "Action: " + action);
        Log.i(TAG, "Time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(new Date()));
        Log.i(TAG, "========================================");
        
        switch (action) {
            case Intent.ACTION_BOOT_COMPLETED:
            case "android.intent.action.QUICKBOOT_POWERON":
            case "com.htc.intent.action.QUICKBOOT_POWERON":
            case Intent.ACTION_REBOOT:
                handleBootCompleted(context);
                break;
                
            case ACTION_VERIFY_SERVICE:
                handleVerifyService(context);
                break;
                
            case ACTION_RETRY_START:
                handleRetryStart(context);
                break;
                
            case ACTION_ALARM_BACKUP:
                handleAlarmBackup(context);
                break;
                
            default:
                Log.w(TAG, "Unknown action: " + action);
                break;
        }
    }
    
    // ============================================================
    // BOOT HANDLERS
    // ============================================================
    
    /**
     * Handle BOOT_COMPLETED event
     */
    private void handleBootCompleted(Context context) {
        Log.i(TAG, "Device boot completed - initiating service start");
        
        // Save boot information
        saveBootInfo(context);
        
        // Acquire wake lock to ensure execution
        PowerManager.WakeLock wakeLock = acquireWakeLock(context);
        
        try {
            // Schedule initial service start with delay
            // Delay allows system to fully initialize
            scheduleServiceStart(context, INITIAL_DELAY_MS);
            
            // Schedule verification check
            scheduleVerification(context, VERIFICATION_DELAY_MS);
            
            // Schedule alarm backup
            scheduleAlarmBackup(context);
            
            Log.i(TAG, "Service start scheduled in " + (INITIAL_DELAY_MS / 1000) + " seconds");
            
        } catch (Exception e) {
            Log.e(TAG, "Error handling boot: " + e.getMessage(), e);
            
        } finally {
            // Release wake lock
            releaseWakeLock(wakeLock);
        }
    }
    
    /**
     * Handle service verification
     */
    private void handleVerifyService(Context context) {
        Log.d(TAG, "Verifying service status...");
        
        if (!isServiceRunning(context)) {
            Log.w(TAG, "Service not running during verification - retrying");
            scheduleServiceStart(context, 0); // Immediate start
        } else {
            Log.i(TAG, "Service verification passed - service is running");
        }
    }
    
    /**
     * Handle retry start
     */
    private void handleRetryStart(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int retryCount = prefs.getInt("retry_count", 0);
        
        if (retryCount >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "Max retry attempts reached (" + MAX_RETRY_ATTEMPTS + ")");
            
            // Reset retry count for next boot
            prefs.edit().putInt("retry_count", 0).apply();
            
            // Use AlarmManager as last resort
            scheduleAlarmBackup(context);
            return;
        }
        
        Log.i(TAG, "Retry attempt " + (retryCount + 1) + " of " + MAX_RETRY_ATTEMPTS);
        
        // Increment retry count
        prefs.edit().putInt("retry_count", retryCount + 1).apply();
        
        // Start service
        startServiceNow(context);
        
        // Schedule next retry if needed
        scheduleRetry(context, RETRY_DELAY_MS);
    }
    
    /**
     * Handle alarm backup trigger
     */
    private void handleAlarmBackup(Context context) {
        Log.d(TAG, "Alarm backup triggered");
        
        if (!isServiceRunning(context)) {
            Log.w(TAG, "Service not running - starting via alarm backup");
            startServiceNow(context);
        }
        
        // Reschedule alarm
        scheduleAlarmBackup(context);
    }
    
    // ============================================================
    // SERVICE CONTROL
    // ============================================================
    
    /**
     * Start the background service immediately
     */
    private void startServiceNow(Context context) {
        try {
            Intent serviceIntent = new Intent(context, MainService.class);
            
            // Add extras for server config
            SharedPreferences mainPrefs = context.getSharedPreferences(
                "athex_dlp_prefs", Context.MODE_PRIVATE
            );
            
            String serverHost = mainPrefs.getString("server_host", "127.0.0.1");
            int serverPort = mainPrefs.getInt("server_port", 22533);
            
            serviceIntent.putExtra("server_host", serverHost);
            serviceIntent.putExtra("server_port", serverPort);
            serviceIntent.putExtra("started_from", "boot_receiver");
            serviceIntent.putExtra("start_time", System.currentTimeMillis());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            // Save start info
            SharedPreferences bootPrefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            int startCount = bootPrefs.getInt(KEY_SERVICE_START_COUNT, 0);
            
            bootPrefs.edit()
                .putInt(KEY_SERVICE_START_COUNT, startCount + 1)
                .putLong(KEY_LAST_SERVICE_START, System.currentTimeMillis())
                .apply();
            
            Log.i(TAG, "Service started successfully (Start #" + (startCount + 1) + ")");
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service: " + e.getMessage(), e);
        }
    }
    
    /**
     * Check if service is running
     */
    private boolean isServiceRunning(Context context) {
        android.app.ActivityManager manager = (android.app.ActivityManager) 
            context.getSystemService(Context.ACTIVITY_SERVICE);
        
        if (manager != null) {
            for (android.app.ActivityManager.RunningServiceInfo service : 
                 manager.getRunningServices(Integer.MAX_VALUE)) {
                if (MainService.class.getName().equals(
                    service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }
    
    // ============================================================
    // SCHEDULING METHODS
    // ============================================================
    
    /**
     * Schedule service start with delay
     */
    private void scheduleServiceStart(Context context, long delayMs) {
        Handler handler = new Handler(Looper.getMainLooper());
        
        handler.postDelayed(() -> {
            startServiceNow(context);
        }, delayMs);
        
        Log.d(TAG, "Service start scheduled with " + (delayMs / 1000) + "s delay");
    }
    
    /**
     * Schedule verification check
     */
    private void scheduleVerification(Context context, long delayMs) {
        Intent verifyIntent = new Intent(context, BootReceiver.class);
        verifyIntent.setAction(ACTION_VERIFY_SERVICE);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            2001,
            verifyIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        if (alarmManager != null) {
            long triggerTime = SystemClock.elapsedRealtime() + delayMs;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                );
            } else {
                alarmManager.setExact(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                );
            }
            
            Log.d(TAG, "Verification scheduled in " + (delayMs / 1000) + "s");
        }
    }
    
    /**
     * Schedule retry attempt
     */
    private void scheduleRetry(Context context, long delayMs) {
        Intent retryIntent = new Intent(context, BootReceiver.class);
        retryIntent.setAction(ACTION_RETRY_START);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            2002,
            retryIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        if (alarmManager != null) {
            long triggerTime = SystemClock.elapsedRealtime() + delayMs;
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    pendingIntent
                );
            }
            
            Log.d(TAG, "Retry scheduled in " + (delayMs / 1000) + "s");
        }
    }
    
    /**
     * Schedule alarm backup (periodic check)
     */
    private void scheduleAlarmBackup(Context context) {
        Intent alarmIntent = new Intent(context, BootReceiver.class);
        alarmIntent.setAction(ACTION_ALARM_BACKUP);
        
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
            context,
            2003,
            alarmIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        
        if (alarmManager != null) {
            long triggerTime = SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS;
            
            // Use setInexactRepeating for battery efficiency
            try {
                alarmManager.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerTime,
                    ALARM_INTERVAL_MS,
                    pendingIntent
                );
                
                Log.d(TAG, "Alarm backup scheduled every " + (ALARM_INTERVAL_MS / 60000) + " minutes");
                
            } catch (SecurityException e) {
                Log.e(TAG, "Security exception scheduling alarm: " + e.getMessage());
                
                // Fallback: use exact alarm
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    );
                }
            }
        }
    }
    
    // ============================================================
    // WAKE LOCK MANAGEMENT
    // ============================================================
    
    /**
     * Acquire wake lock to prevent device from sleeping during execution
     */
    private PowerManager.WakeLock acquireWakeLock(Context context) {
        try {
            PowerManager powerManager = (PowerManager) 
                context.getSystemService(Context.POWER_SERVICE);
            
            if (powerManager != null) {
                PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "ATHEX_DLP::BootReceiver"
                );
                
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(30000); // 30 seconds timeout
                
                Log.d(TAG, "WakeLock acquired for 30 seconds");
                return wakeLock;
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to acquire WakeLock: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Release wake lock safely
     */
    private void releaseWakeLock(PowerManager.WakeLock wakeLock) {
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
    // PERSISTENCE
    // ============================================================
    
    /**
     * Save boot information to preferences
     */
    private void saveBootInfo(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int bootCount = prefs.getInt(KEY_BOOT_COUNT, 0);
        
        prefs.edit()
            .putLong(KEY_LAST_BOOT_TIME, System.currentTimeMillis())
            .putInt(KEY_BOOT_COUNT, bootCount + 1)
            .putInt("retry_count", 0) // Reset retry count on new boot
            .apply();
        
        Log.i(TAG, "Boot #" + (bootCount + 1) + " recorded");
    }
    
    /**
     * Get boot statistics
     */
    public static String getBootStats(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        int bootCount = prefs.getInt(KEY_BOOT_COUNT, 0);
        long lastBoot = prefs.getLong(KEY_LAST_BOOT_TIME, 0);
        int startCount = prefs.getInt(KEY_SERVICE_START_COUNT, 0);
        long lastStart = prefs.getLong(KEY_LAST_SERVICE_START, 0);
        
        StringBuilder stats = new StringBuilder();
        stats.append("Boot Statistics:\n");
        stats.append("Total Boots: ").append(bootCount).append("\n");
        
        if (lastBoot > 0) {
            stats.append("Last Boot: ").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(lastBoot))
            ).append("\n");
        }
        
        stats.append("Service Starts: ").append(startCount).append("\n");
        
        if (lastStart > 0) {
            stats.append("Last Start: ").append(
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(lastStart))
            ).append("\n");
        }
        
        return stats.toString();
    }
}