package com.athex.dlp.collectors;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ATHEX DLP Enterprise - WiFiCollector
 * 
 * Comprehensive WiFi network information module.
 * Collects all WiFi-related data including:
 * - Current connection info (SSID, BSSID, IP, MAC)
 * - Available network scan results
 * - Saved/configured networks
 * - Signal strength and link speed
 * - DHCP information
 * - Network capabilities
 * - Real-time WiFi state changes
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class WiFiCollector {
    
    private static final String TAG = "ATHEX_WiFiCollector";
    
    // Security types
    public static final String SECURITY_OPEN = "OPEN";
    public static final String SECURITY_WEP = "WEP";
    public static final String SECURITY_WPA = "WPA";
    public static final String SECURITY_WPA2 = "WPA2";
    public static final String SECURITY_WPA3 = "WPA3";
    public static final String SECURITY_EAP = "EAP";
    public static final String SECURITY_UNKNOWN = "UNKNOWN";
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final WifiManager wifiManager;
    private final ConnectivityManager connectivityManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean isMonitoring = new AtomicBoolean(false);
    
    // Receivers
    private BroadcastReceiver wifiStateReceiver;
    private BroadcastReceiver scanResultsReceiver;
    private ConnectivityManager.NetworkCallback networkCallback;
    
    // Options
    private boolean includeSavedNetworks = true;
    private boolean includeScanResults = true;
    private boolean autoScan = false;
    private long scanInterval = 30000; // 30 seconds
    private int maxScanResults = 50;
    
    // Statistics
    private int scanCount = 0;
    private long lastScanTime = 0;
    private long monitoringStartTime = 0;
    
    // Callbacks
    private WiFiCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface WiFiCallback {
        void onCurrentNetworkChanged(JSONObject networkInfo);
        void onScanResultsAvailable(JSONArray networks);
        void onWiFiStateChanged(boolean enabled);
        void onWiFiError(String error);
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public WiFiCollector(Context context) {
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) context.getApplicationContext()
            .getSystemService(Context.WIFI_SERVICE);
        this.connectivityManager = (ConnectivityManager) context
            .getSystemService(Context.CONNECTIVITY_SERVICE);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public WiFiCollector setIncludeSavedNetworks(boolean include) {
        this.includeSavedNetworks = include;
        return this;
    }
    
    public WiFiCollector setIncludeScanResults(boolean include) {
        this.includeScanResults = include;
        return this;
    }
    
    public WiFiCollector setAutoScan(boolean auto, long intervalMs) {
        this.autoScan = auto;
        this.scanInterval = Math.max(5000, intervalMs);
        return this;
    }
    
    public WiFiCollector setCallback(WiFiCallback callback) {
        this.callback = callback;
        return this;
    }
    
    // ============================================================
    // CURRENT NETWORK INFO
    // ============================================================
    
    /**
     * Get current WiFi connection information
     */
    public JSONObject getCurrentNetworkInfo() {
        JSONObject networkInfo = new JSONObject();
        
        try {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            
            if (wifiInfo == null || wifiInfo.getSSID() == null || 
                wifiInfo.getSSID().equals("<unknown ssid>")) {
                networkInfo.put("connected", false);
                return networkInfo;
            }
            
            networkInfo.put("connected", true);
            
            // SSID (remove quotes)
            String ssid = wifiInfo.getSSID();
            if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            networkInfo.put("ssid", ssid);
            
            // BSSID
            String bssid = wifiInfo.getBSSID();
            networkInfo.put("bssid", bssid != null ? bssid : "Unknown");
            
            // IP Address
            int ip = wifiInfo.getIpAddress();
            String ipAddress = String.format(Locale.getDefault(),
                "%d.%d.%d.%d",
                (ip & 0xff),
                (ip >> 8 & 0xff),
                (ip >> 16 & 0xff),
                (ip >> 24 & 0xff)
            );
            networkInfo.put("ip_address", ipAddress);
            
            // MAC Address
            String macAddress = wifiInfo.getMacAddress();
            networkInfo.put("mac_address", macAddress != null ? macAddress : "Unknown");
            
            // Signal strength (0 to -100 dBm)
            int rssi = wifiInfo.getRssi();
            networkInfo.put("rssi", rssi);
            networkInfo.put("signal_level", WifiManager.calculateSignalLevel(rssi, 5));
            networkInfo.put("signal_percent", getSignalPercent(rssi));
            
            // Link speed
            int linkSpeed = wifiInfo.getLinkSpeed();
            networkInfo.put("link_speed_mbps", linkSpeed);
            
            // Frequency
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int frequency = wifiInfo.getFrequency();
                networkInfo.put("frequency_mhz", frequency);
                networkInfo.put("band", frequency > 5000 ? "5GHz" : "2.4GHz");
            }
            
            // Network ID
            networkInfo.put("network_id", wifiInfo.getNetworkId());
            
            // Hidden SSID
            networkInfo.put("hidden_ssid", wifiInfo.getHiddenSSID());
            
            // DHCP Info
            DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
            if (dhcpInfo != null) {
                JSONObject dhcp = new JSONObject();
                dhcp.put("gateway", intToIp(dhcpInfo.gateway));
                dhcp.put("dns1", intToIp(dhcpInfo.dns1));
                dhcp.put("dns2", intToIp(dhcpInfo.dns2));
                dhcp.put("server_address", intToIp(dhcpInfo.serverAddress));
                dhcp.put("lease_duration", dhcpInfo.leaseDuration);
                networkInfo.put("dhcp", dhcp);
            }
            
            // Network capabilities
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                JSONObject capabilities = getNetworkCapabilities();
                if (capabilities != null) {
                    networkInfo.put("capabilities", capabilities);
                }
            }
            
            // Timestamp
            networkInfo.put("timestamp", System.currentTimeMillis());
            networkInfo.put("timestamp_formatted", 
                new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date()));
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting current network info: " + e.getMessage());
            try {
                networkInfo.put("error", e.getMessage());
            } catch (Exception ex) {
                Log.e(TAG, "Error adding error info: " + ex.getMessage());
            }
        }
        
        return networkInfo;
    }
    
    /**
     * Get network capabilities
     */
    private JSONObject getNetworkCapabilities() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null;
        
        try {
            ConnectivityManager cm = (ConnectivityManager) 
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            
            Network currentNetwork = cm.getActiveNetwork();
            if (currentNetwork == null) return null;
            
            NetworkCapabilities caps = cm.getNetworkCapabilities(currentNetwork);
            if (caps == null) return null;
            
            JSONObject capabilities = new JSONObject();
            capabilities.put("has_internet", 
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
            capabilities.put("is_validated", 
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED));
            capabilities.put("is_metered", 
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false);
            capabilities.put("is_vpn", 
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
            capabilities.put("is_wifi", 
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            capabilities.put("is_cellular", 
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            
            int linkDownBandwidth = caps.getLinkDownstreamBandwidthKbps();
            int linkUpBandwidth = caps.getLinkUpstreamBandwidthKbps();
            
            capabilities.put("downstream_kbps", linkDownBandwidth);
            capabilities.put("upstream_kbps", linkUpBandwidth);
            
            return capabilities;
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting capabilities: " + e.getMessage());
            return null;
        }
    }
    
    // ============================================================
    // WIFI SCANNING
    // ============================================================
    
    /**
     * Scan for available WiFi networks
     */
    public void scanNetworks() {
        if (!hasWifiPermission()) {
            notifyError("WiFi permissions not granted");
            return;
        }
        
        executor.execute(() -> {
            try {
                // Start scan
                boolean scanStarted = wifiManager.startScan();
                
                if (!scanStarted) {
                    Log.w(TAG, "WiFi scan could not be started");
                    
                    // Try to get cached results
                    List<ScanResult> cachedResults = wifiManager.getScanResults();
                    if (cachedResults != null) {
                        JSONArray networks = parseScanResults(cachedResults);
                        notifyScanResults(networks);
                    }
                    return;
                }
                
                // Wait for scan results
                // Results will come through BroadcastReceiver
                lastScanTime = System.currentTimeMillis();
                scanCount++;
                
                Log.d(TAG, "WiFi scan initiated (scan #" + scanCount + ")");
                
            } catch (Exception e) {
                Log.e(TAG, "Error scanning WiFi: " + e.getMessage());
                notifyError("Scan failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Parse scan results into JSON
     */
    private JSONArray parseScanResults(List<ScanResult> results) {
        JSONArray networks = new JSONArray();
        
        if (results == null || results.isEmpty()) {
            return networks;
        }
        
        // Sort by signal strength (strongest first)
        Collections.sort(results, (a, b) -> Integer.compare(b.level, a.level));
        
        int count = 0;
        for (ScanResult result : results) {
            if (count >= maxScanResults) break;
            
            try {
                JSONObject network = new JSONObject();
                
                // SSID
                network.put("ssid", result.SSID != null ? result.SSID : "<hidden>");
                
                // BSSID
                network.put("bssid", result.BSSID != null ? result.BSSID : "Unknown");
                
                // Signal
                network.put("rssi", result.level);
                network.put("signal_level", WifiManager.calculateSignalLevel(result.level, 5));
                network.put("signal_percent", getSignalPercent(result.level));
                
                // Frequency
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    network.put("frequency_mhz", result.frequency);
                    network.put("band", result.frequency > 5000 ? "5GHz" : "2.4GHz");
                    network.put("channel", getChannelFromFrequency(result.frequency));
                }
                
                // Security
                String security = getSecurityType(result.capabilities);
                network.put("security", security);
                network.put("capabilities", result.capabilities);
                
                // Timestamp
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    network.put("timestamp", result.timestamp);
                }
                
                // Distance estimate (rough)
                network.put("distance_estimate_meters", estimateDistance(result.level, result.frequency));
                
                networks.put(network);
                count++;
                
            } catch (Exception e) {
                Log.e(TAG, "Error parsing scan result: " + e.getMessage());
            }
        }
        
        return networks;
    }
    
    // ============================================================
    // SAVED NETWORKS
    // ============================================================
    
    /**
     * Get saved/configured WiFi networks
     */
    public JSONArray getSavedNetworks() {
        JSONArray savedNetworks = new JSONArray();
        
        if (!hasWifiPermission()) {
            return savedNetworks;
        }
        
        try {
            List<WifiConfiguration> configuredNetworks = wifiManager.getConfiguredNetworks();
            
            if (configuredNetworks == null || configuredNetworks.isEmpty()) {
                return savedNetworks;
            }
            
            for (WifiConfiguration config : configuredNetworks) {
                try {
                    JSONObject network = new JSONObject();
                    
                    // SSID (remove quotes)
                    String ssid = config.SSID;
                    if (ssid != null) {
                        if (ssid.startsWith("\"") && ssid.endsWith("\"")) {
                            ssid = ssid.substring(1, ssid.length() - 1);
                        }
                    }
                    network.put("ssid", ssid != null ? ssid : "Unknown");
                    
                    network.put("network_id", config.networkId);
                    network.put("priority", config.priority);
                    network.put("status", getConfigStatus(config.status));
                    
                    // Security type
                    String security = getConfigSecurity(config);
                    network.put("security", security);
                    
                    // Hidden SSID
                    network.put("hidden_ssid", config.hiddenSSID);
                    
                    // BSSID if set
                    if (config.BSSID != null) {
                        network.put("bssid", config.BSSID);
                    }
                    
                    // Pre-shared key (masked)
                    if (config.preSharedKey != null && !config.preSharedKey.equals("*")) {
                        network.put("has_password", true);
                    } else {
                        network.put("has_password", false);
                    }
                    
                    savedNetworks.put(network);
                    
                } catch (Exception e) {
                    Log.e(TAG, "Error parsing saved network: " + e.getMessage());
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting saved networks: " + e.getMessage());
        }
        
        return savedNetworks;
    }
    
    // ============================================================
    // COMPLETE REPORT
    // ============================================================
    
    /**
     * Get complete WiFi report (current + saved + scan)
     */
    public void getCompleteReport() {
        executor.execute(() -> {
            try {
                JSONObject report = new JSONObject();
                
                // Current connection
                report.put("current_network", getCurrentNetworkInfo());
                
                // Saved networks
                if (includeSavedNetworks) {
                    report.put("saved_networks", getSavedNetworks());
                }
                
                // WiFi state
                report.put("wifi_enabled", isWifiEnabled());
                report.put("timestamp", System.currentTimeMillis());
                
                // If scan requested, it will come through callback
                if (includeScanResults) {
                    scanNetworks();
                }
                
                // Notify current info immediately
                if (callback != null) {
                    mainHandler.post(() -> 
                        callback.onCurrentNetworkChanged(
                            report.optJSONObject("current_network")
                        )
                    );
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error generating report: " + e.getMessage());
            }
        });
    }
    
    // ============================================================
    // MONITORING
    // ============================================================
    
    /**
     * Start WiFi state monitoring
     */
    public void startMonitoring() {
        if (isMonitoring.get()) return;
        
        Log.i(TAG, "Starting WiFi monitoring...");
        isMonitoring.set(true);
        monitoringStartTime = System.currentTimeMillis();
        
        // Register WiFi state receiver
        registerWiFiStateReceiver();
        
        // Register scan results receiver
        registerScanResultsReceiver();
        
        // Register network callback
        registerNetworkCallback();
        
        // Start auto scan if enabled
        if (autoScan) {
            startAutoScan();
        }
        
        Log.i(TAG, "WiFi monitoring started");
    }
    
    /**
     * Stop WiFi state monitoring
     */
    public void stopMonitoring() {
        if (!isMonitoring.get()) return;
        
        Log.i(TAG, "Stopping WiFi monitoring...");
        isMonitoring.set(false);
        
        // Unregister receivers
        try {
            if (wifiStateReceiver != null) {
                context.unregisterReceiver(wifiStateReceiver);
                wifiStateReceiver = null;
            }
            if (scanResultsReceiver != null) {
                context.unregisterReceiver(scanResultsReceiver);
                scanResultsReceiver = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receivers: " + e.getMessage());
        }
        
        // Unregister network callback
        if (networkCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.e(TAG, "Error unregistering network callback: " + e.getMessage());
            }
            networkCallback = null;
        }
        
        Log.i(TAG, "WiFi monitoring stopped");
    }
    
    private void registerWiFiStateReceiver() {
        wifiStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                
                if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(action)) {
                    int wifiState = intent.getIntExtra(
                        WifiManager.EXTRA_WIFI_STATE, 
                        WifiManager.WIFI_STATE_UNKNOWN
                    );
                    
                    boolean enabled = wifiState == WifiManager.WIFI_STATE_ENABLED;
                    
                    if (callback != null) {
                        mainHandler.post(() -> callback.onWiFiStateChanged(enabled));
                    }
                    
                    Log.d(TAG, "WiFi state changed: " + (enabled ? "ON" : "OFF"));
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        context.registerReceiver(wifiStateReceiver, filter);
    }
    
    private void registerScanResultsReceiver() {
        scanResultsReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                    executor.execute(() -> {
                        try {
                            List<ScanResult> results = wifiManager.getScanResults();
                            JSONArray networks = parseScanResults(results);
                            
                            if (callback != null) {
                                final JSONArray finalNetworks = networks;
                                mainHandler.post(() -> 
                                    callback.onScanResultsAvailable(finalNetworks)
                                );
                            }
                            
                            Log.d(TAG, "Scan results available: " + 
                                networks.length() + " networks");
                            
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing scan results: " + e.getMessage());
                        }
                    });
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        context.registerReceiver(scanResultsReceiver, filter);
    }
    
    private void registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        
        networkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                executor.execute(() -> {
                    JSONObject networkInfo = getCurrentNetworkInfo();
                    if (callback != null) {
                        final JSONObject finalInfo = networkInfo;
                        mainHandler.post(() -> 
                            callback.onCurrentNetworkChanged(finalInfo)
                        );
                    }
                });
            }
            
            @Override
            public void onLost(Network network) {
                if (callback != null) {
                    mainHandler.post(() -> {
                        try {
                            JSONObject disconnected = new JSONObject();
                            disconnected.put("connected", false);
                            callback.onCurrentNetworkChanged(disconnected);
                        } catch (Exception e) {
                            Log.e(TAG, "Error: " + e.getMessage());
                        }
                    });
                }
            }
            
            @Override
            public void onCapabilitiesChanged(Network network, 
                                              NetworkCapabilities capabilities) {
                executor.execute(() -> {
                    JSONObject networkInfo = getCurrentNetworkInfo();
                    if (callback != null) {
                        final JSONObject finalInfo = networkInfo;
                        mainHandler.post(() -> 
                            callback.onCurrentNetworkChanged(finalInfo)
                        );
                    }
                });
            }
        };
        
        NetworkRequest request = new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build();
        
        connectivityManager.registerNetworkCallback(request, networkCallback);
    }
    
    private void startAutoScan() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!isMonitoring.get() || !autoScan) return;
                
                scanNetworks();
                mainHandler.postDelayed(this, scanInterval);
            }
        }, scanInterval);
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    private String getSecurityType(String capabilities) {
        if (capabilities == null) return SECURITY_UNKNOWN;
        
        if (capabilities.contains("WPA3")) return SECURITY_WPA3;
        if (capabilities.contains("WPA2")) return SECURITY_WPA2;
        if (capabilities.contains("WPA")) return SECURITY_WPA;
        if (capabilities.contains("WEP")) return SECURITY_WEP;
        if (capabilities.contains("EAP")) return SECURITY_EAP;
        if (capabilities.contains("ESS")) return SECURITY_OPEN;
        
        return SECURITY_UNKNOWN;
    }
    
    private String getConfigSecurity(WifiConfiguration config) {
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA3_SAE)) 
            return SECURITY_WPA3;
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA2_PSK)) 
            return SECURITY_WPA2;
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.WPA_PSK)) 
            return SECURITY_WPA;
        if (config.allowedKeyManagement.get(WifiConfiguration.KeyMgmt.IEEE8021X)) 
            return SECURITY_EAP;
        if (config.wepTxKeyIndex >= 0) return SECURITY_WEP;
        
        return SECURITY_OPEN;
    }
    
    private String getConfigStatus(int status) {
        switch (status) {
            case WifiConfiguration.Status.CURRENT: return "Current";
            case WifiConfiguration.Status.DISABLED: return "Disabled";
            case WifiConfiguration.Status.ENABLED: return "Enabled";
            default: return "Unknown";
        }
    }
    
    private int getSignalPercent(int rssi) {
        if (rssi <= -100) return 0;
        if (rssi >= -50) return 100;
        return 2 * (rssi + 100);
    }
    
    private int getChannelFromFrequency(int frequency) {
        if (frequency >= 2412 && frequency <= 2484) {
            return (frequency - 2412) / 5 + 1;
        } else if (frequency >= 5170 && frequency <= 5825) {
            return (frequency - 5170) / 5 + 34;
        }
        return -1;
    }
    
    private double estimateDistance(int rssi, int frequency) {
        // Free-space path loss formula (very rough estimate)
        double exp = (27.55 - (20 * Math.log10(frequency)) + Math.abs(rssi)) / 20.0;
        return Math.pow(10.0, exp);
    }
    
    private String intToIp(int ip) {
        return String.format(Locale.getDefault(),
            "%d.%d.%d.%d",
            (ip & 0xff),
            (ip >> 8 & 0xff),
            (ip >> 16 & 0xff),
            (ip >> 24 & 0xff)
        );
    }
    
    public boolean isWifiEnabled() {
        return wifiManager != null && wifiManager.isWifiEnabled();
    }
    
    public boolean enableWifi() {
        if (wifiManager != null) {
            return wifiManager.setWifiEnabled(true);
        }
        return false;
    }
    
    public boolean disableWifi() {
        if (wifiManager != null) {
            return wifiManager.setWifiEnabled(false);
        }
        return false;
    }
    
    private boolean hasWifiPermission() {
        return ActivityCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_WIFI_STATE) == PackageManager.PERMISSION_GRANTED;
    }
    
    private void notifyError(String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onWiFiError(error));
        }
    }
    
    private void notifyScanResults(JSONArray networks) {
        if (callback != null) {
            mainHandler.post(() -> callback.onScanResultsAvailable(networks));
        }
    }
    
    public void shutdown() {
        stopMonitoring();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}