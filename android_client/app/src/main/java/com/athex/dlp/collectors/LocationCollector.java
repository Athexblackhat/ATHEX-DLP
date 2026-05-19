package com.athex.dlp.collectors;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Address;
import android.location.Criteria;
import android.location.Geocoder;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ATHEX DLP Enterprise - LocationCollector
 * 
 * Advanced GPS and location tracking module.
 * Provides comprehensive location services:
 * - Real-time GPS tracking
 * - Network location (WiFi/Cell tower)
 * - Passive location listening
 * - Geocoding (address lookup)
 * - Reverse geocoding
 * - Location history
 * - Accuracy filtering
 * - Distance-based updates
 * - Battery-efficient modes
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class LocationCollector {
    
    private static final String TAG = "ATHEX_LocationCollector";
    
    // Timing constants
    private static final long MIN_TIME_BETWEEN_UPDATES = 5000;     // 5 seconds
    private static final float MIN_DISTANCE_BETWEEN_UPDATES = 10f; // 10 meters
    private static final long GPS_TIMEOUT = 30000;                  // 30 seconds
    private static final long LOCATION_STALE_THRESHOLD = 120000;    // 2 minutes
    
    // Location providers
    public static final String PROVIDER_GPS = LocationManager.GPS_PROVIDER;
    public static final String PROVIDER_NETWORK = LocationManager.NETWORK_PROVIDER;
    public static final String PROVIDER_PASSIVE = LocationManager.PASSIVE_PROVIDER;
    
    // Accuracy levels
    public static final int ACCURACY_HIGH = 0;
    public static final int ACCURACY_MEDIUM = 1;
    public static final int ACCURACY_LOW = 2;
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final LocationManager locationManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean isTracking = new AtomicBoolean(false);
    private final AtomicBoolean isListening = new AtomicBoolean(false);
    
    // Location listeners
    private LocationListener gpsListener;
    private LocationListener networkListener;
    private LocationListener passiveListener;
    
    // Last known location
    private Location lastKnownLocation;
    private Location bestLocation;
    private long lastLocationTime = 0;
    private int locationCount = 0;
    
    // History
    private JSONArray locationHistory;
    private int maxHistorySize = 100;
    
    // Options
    private int accuracyLevel = ACCURACY_HIGH;
    private long updateInterval = MIN_TIME_BETWEEN_UPDATES;
    private float minDistance = MIN_DISTANCE_BETWEEN_UPDATES;
    private boolean useGPS = true;
    private boolean useNetwork = true;
    private boolean includeAddress = true;
    private boolean saveHistory = true;
    private long locationTimeout = GPS_TIMEOUT;
    
    // Callbacks
    private LocationCallback callback;
    private LocationUpdateCallback updateCallback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface LocationCallback {
        void onLocationReceived(Location location, JSONObject locationData);
        void onAddressResolved(Address address);
        void onProviderEnabled(String provider);
        void onProviderDisabled(String provider);
        void onLocationError(String error);
    }
    
    public interface LocationUpdateCallback {
        void onLocationChanged(JSONObject locationData);
        void onStatusChanged(String provider, int status);
        void onAccuracyChanged(int accuracyMeters);
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public LocationCollector(Context context) {
        this.context = context.getApplicationContext();
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.locationHistory = new JSONArray();
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public LocationCollector setAccuracyLevel(int level) {
        this.accuracyLevel = level;
        applyAccuracySettings();
        return this;
    }
    
    public LocationCollector setUpdateInterval(long intervalMs) {
        this.updateInterval = Math.max(1000, intervalMs);
        return this;
    }
    
    public LocationCollector setMinDistance(float meters) {
        this.minDistance = Math.max(1f, meters);
        return this;
    }
    
    public LocationCollector setUseGPS(boolean use) {
        this.useGPS = use;
        return this;
    }
    
    public LocationCollector setUseNetwork(boolean use) {
        this.useNetwork = use;
        return this;
    }
    
    public LocationCollector setIncludeAddress(boolean include) {
        this.includeAddress = include;
        return this;
    }
    
    public LocationCollector setSaveHistory(boolean save) {
        this.saveHistory = save;
        return this;
    }
    
    public LocationCollector setMaxHistorySize(int size) {
        this.maxHistorySize = Math.min(size, 1000);
        return this;
    }
    
    public LocationCollector setCallback(LocationCallback callback) {
        this.callback = callback;
        return this;
    }
    
    public LocationCollector setUpdateCallback(LocationUpdateCallback callback) {
        this.updateCallback = callback;
        return this;
    }
    
    private void applyAccuracySettings() {
        switch (accuracyLevel) {
            case ACCURACY_HIGH:
                updateInterval = 2000;    // 2 seconds
                minDistance = 1f;         // 1 meter
                locationTimeout = 15000;  // 15 seconds
                break;
            case ACCURACY_MEDIUM:
                updateInterval = 10000;   // 10 seconds
                minDistance = 50f;        // 50 meters
                locationTimeout = 30000;  // 30 seconds
                break;
            case ACCURACY_LOW:
                updateInterval = 60000;   // 1 minute
                minDistance = 500f;       // 500 meters
                locationTimeout = 60000;  // 1 minute
                break;
        }
    }
    
    // ============================================================
    // LOCATION REQUEST METHODS
    // ============================================================
    
    /**
     * Get current location once (single update)
     */
    public void getCurrentLocation() {
        if (!hasLocationPermission()) {
            notifyError("Location permission not granted");
            return;
        }
        
        if (!isLocationEnabled()) {
            notifyError("Location services are disabled");
            return;
        }
        
        executor.execute(() -> {
            try {
                Location location = getBestLastKnownLocation();
                
                if (location != null && !isLocationStale(location)) {
                    // Use cached location if recent enough
                    processLocation(location);
                } else {
                    // Request fresh location
                    requestSingleUpdate();
                }
            } catch (Exception e) {
                Log.e(TAG, "Error getting current location: " + e.getMessage());
                notifyError("Failed to get location: " + e.getMessage());
            }
        });
    }
    
    /**
     * Start continuous location tracking
     */
    public void startTracking() {
        if (!hasLocationPermission()) {
            notifyError("Location permission not granted");
            return;
        }
        
        if (!isLocationEnabled()) {
            notifyError("Location services are disabled");
            return;
        }
        
        if (isTracking.get()) {
            Log.w(TAG, "Already tracking");
            return;
        }
        
        Log.i(TAG, "Starting location tracking...");
        isTracking.set(true);
        
        try {
            // GPS listener
            if (useGPS && locationManager.isProviderEnabled(PROVIDER_GPS)) {
                gpsListener = createLocationListener("GPS");
                
                if (ActivityCompat.checkSelfPermission(context, 
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    
                    locationManager.requestLocationUpdates(
                        PROVIDER_GPS,
                        updateInterval,
                        minDistance,
                        gpsListener,
                        Looper.getMainLooper()
                    );
                    
                    Log.i(TAG, "GPS tracking started");
                }
            }
            
            // Network listener
            if (useNetwork && locationManager.isProviderEnabled(PROVIDER_NETWORK)) {
                networkListener = createLocationListener("Network");
                
                locationManager.requestLocationUpdates(
                    PROVIDER_NETWORK,
                    updateInterval,
                    minDistance,
                    networkListener,
                    Looper.getMainLooper()
                );
                
                Log.i(TAG, "Network tracking started");
            }
            
            // Passive listener (for battery efficiency)
            passiveListener = createLocationListener("Passive");
            locationManager.requestLocationUpdates(
                PROVIDER_PASSIVE,
                60000, // 1 minute minimum for passive
                100f,  // 100 meters
                passiveListener,
                Looper.getMainLooper()
            );
            
            Log.i(TAG, "Passive tracking started");
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting tracking: " + e.getMessage());
            notifyError("Failed to start tracking: " + e.getMessage());
        }
    }
    
    /**
     * Stop location tracking
     */
    public void stopTracking() {
        Log.i(TAG, "Stopping location tracking...");
        isTracking.set(false);
        
        try {
            if (gpsListener != null) {
                locationManager.removeUpdates(gpsListener);
                gpsListener = null;
            }
            if (networkListener != null) {
                locationManager.removeUpdates(networkListener);
                networkListener = null;
            }
            if (passiveListener != null) {
                locationManager.removeUpdates(passiveListener);
                passiveListener = null;
            }
            
            Log.i(TAG, "All location listeners removed");
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping tracking: " + e.getMessage());
        }
    }
    
    // ============================================================
    // LOCATION LISTENER
    // ============================================================
    
    private LocationListener createLocationListener(String providerName) {
        return new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (location == null) return;
                
                Log.d(TAG, providerName + " location: " + 
                    location.getLatitude() + ", " + location.getLongitude() +
                    " (±" + location.getAccuracy() + "m)");
                
                // Update best location
                if (isBetterLocation(location, bestLocation)) {
                    bestLocation = location;
                }
                
                lastKnownLocation = location;
                lastLocationTime = System.currentTimeMillis();
                locationCount++;
                
                // Process location
                processLocation(location);
            }
            
            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                String statusStr;
                switch (status) {
                    case LocationProvider.AVAILABLE:
                        statusStr = "Available";
                        notifyProviderEnabled(provider);
                        break;
                    case LocationProvider.OUT_OF_SERVICE:
                        statusStr = "Out of Service";
                        notifyProviderDisabled(provider);
                        break;
                    case LocationProvider.TEMPORARILY_UNAVAILABLE:
                        statusStr = "Temporarily Unavailable";
                        break;
                    default:
                        statusStr = "Unknown";
                }
                
                Log.d(TAG, provider + " status: " + statusStr);
                
                if (updateCallback != null) {
                    mainHandler.post(() -> updateCallback.onStatusChanged(provider, status));
                }
            }
            
            @Override
            public void onProviderEnabled(String provider) {
                Log.d(TAG, provider + " enabled");
                notifyProviderEnabled(provider);
            }
            
            @Override
            public void onProviderDisabled(String provider) {
                Log.w(TAG, provider + " disabled");
                notifyProviderDisabled(provider);
            }
        };
    }
    
    // ============================================================
    // LOCATION PROCESSING
    // ============================================================
    
    /**
     * Process received location
     */
    private void processLocation(Location location) {
        executor.execute(() -> {
            try {
                JSONObject locationData = locationToJson(location);
                
                // Add provider info
                locationData.put("provider", location.getProvider());
                locationData.put("timestamp_collected", System.currentTimeMillis());
                locationData.put("timestamp_formatted", 
                    new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date()));
                
                // Geocode if requested
                if (includeAddress && Geocoder.isPresent()) {
                    Address address = reverseGeocode(location);
                    if (address != null) {
                        JSONObject addressJson = addressToJson(address);
                        locationData.put("address", addressJson);
                        
                        if (callback != null) {
                            mainHandler.post(() -> callback.onAddressResolved(address));
                        }
                    }
                }
                
                // Save to history
                if (saveHistory) {
                    synchronized (locationHistory) {
                        locationHistory.put(locationData);
                        
                        // Trim history
                        while (locationHistory.length() > maxHistorySize) {
                            locationHistory.remove(0);
                        }
                    }
                }
                
                // Notify callbacks
                final JSONObject finalData = locationData;
                
                if (callback != null) {
                    mainHandler.post(() -> callback.onLocationReceived(location, finalData));
                }
                
                if (updateCallback != null) {
                    mainHandler.post(() -> updateCallback.onLocationChanged(finalData));
                    mainHandler.post(() -> updateCallback.onAccuracyChanged(
                        Math.round(location.getAccuracy())));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error processing location: " + e.getMessage());
            }
        });
    }
    
    /**
     * Convert Location to JSONObject
     */
    private JSONObject locationToJson(Location location) {
        JSONObject json = new JSONObject();
        
        try {
            json.put("latitude", location.getLatitude());
            json.put("longitude", location.getLongitude());
            json.put("accuracy", location.getAccuracy());
            json.put("altitude", location.getAltitude());
            json.put("bearing", location.getBearing());
            json.put("speed", location.getSpeed());  // m/s
            json.put("speed_kmh", location.getSpeed() * 3.6);  // km/h
            json.put("speed_mph", location.getSpeed() * 2.23694);  // mph
            json.put("elapsed_realtime_nanos", location.getElapsedRealtimeNanos());
            json.put("time", location.getTime());
            json.put("time_formatted", formatTimestamp(location.getTime()));
            
            // Android 8+ (API 26)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                json.put("vertical_accuracy", location.getVerticalAccuracyMeters());
                json.put("speed_accuracy", location.getSpeedAccuracyMetersPerSecond());
                json.put("bearing_accuracy", location.getBearingAccuracyDegrees());
            }
            
            // Location extras
            Bundle extras = location.getExtras();
            if (extras != null) {
                JSONObject extrasJson = new JSONObject();
                
                // Satellites
                int satellites = extras.getInt("satellites", -1);
                if (satellites >= 0) {
                    extrasJson.put("satellites", satellites);
                }
                
                // HDOP (Horizontal Dilution of Precision)
                if (extras.containsKey("HDOP")) {
                    extrasJson.put("hdop", extras.getDouble("HDOP"));
                }
                
                if (extrasJson.length() > 0) {
                    json.put("extras", extrasJson);
                }
            }
            
            // Check if from mock provider
            if (android.os.Build.VERSION.SDK_INT >= 18) {
                json.put("is_mock", location.isFromMockProvider());
            }
            
            // Precision category
            String precision;
            float accuracy = location.getAccuracy();
            if (accuracy <= 10) precision = "excellent";
            else if (accuracy <= 50) precision = "good";
            else if (accuracy <= 200) precision = "moderate";
            else precision = "poor";
            json.put("precision", precision);
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting location to JSON: " + e.getMessage());
        }
        
        return json;
    }
    
    /**
     * Convert Address to JSONObject
     */
    private JSONObject addressToJson(Address address) {
        JSONObject json = new JSONObject();
        
        try {
            // Build full address
            StringBuilder fullAddress = new StringBuilder();
            
            for (int i = 0; i <= address.getMaxAddressLineIndex(); i++) {
                String line = address.getAddressLine(i);
                if (line != null) {
                    if (fullAddress.length() > 0) fullAddress.append(", ");
                    fullAddress.append(line);
                }
            }
            
            json.put("full_address", fullAddress.toString());
            
            if (address.getFeatureName() != null)
                json.put("feature_name", address.getFeatureName());
            if (address.getThoroughfare() != null)
                json.put("street", address.getThoroughfare());
            if (address.getSubThoroughfare() != null)
                json.put("street_number", address.getSubThoroughfare());
            if (address.getLocality() != null)
                json.put("city", address.getLocality());
            if (address.getSubLocality() != null)
                json.put("sub_locality", address.getSubLocality());
            if (address.getAdminArea() != null)
                json.put("state", address.getAdminArea());
            if (address.getSubAdminArea() != null)
                json.put("district", address.getSubAdminArea());
            if (address.getPostalCode() != null)
                json.put("postal_code", address.getPostalCode());
            if (address.getCountryName() != null)
                json.put("country", address.getCountryName());
            if (address.getCountryCode() != null)
                json.put("country_code", address.getCountryCode());
            if (address.getPremises() != null)
                json.put("premises", address.getPremises());
            
            // Coordinates from geocoder
            if (address.hasLatitude() && address.hasLongitude()) {
                json.put("geocoded_latitude", address.getLatitude());
                json.put("geocoded_longitude", address.getLongitude());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error converting address to JSON: " + e.getMessage());
        }
        
        return json;
    }
    
    // ============================================================
    // GEOCODING
    // ============================================================
    
    /**
     * Reverse geocode location to address
     */
    private Address reverseGeocode(Location location) {
        try {
            if (!Geocoder.isPresent()) {
                Log.w(TAG, "Geocoder not available");
                return null;
            }
            
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(
                location.getLatitude(),
                location.getLongitude(),
                1  // Max results
            );
            
            if (addresses != null && !addresses.isEmpty()) {
                return addresses.get(0);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Geocoding error: " + e.getMessage());
        }
        
        return null;
    }
    
    /**
     * Geocode address to coordinates
     */
    public void geocodeAddress(String addressStr, GeocodeCallback callback) {
        executor.execute(() -> {
            try {
                if (!Geocoder.isPresent()) {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onGeocodeError("Geocoder not available"));
                    }
                    return;
                }
                
                Geocoder geocoder = new Geocoder(context, Locale.getDefault());
                List<Address> addresses = geocoder.getFromLocationName(addressStr, 5);
                
                if (addresses != null && !addresses.isEmpty()) {
                    JSONArray results = new JSONArray();
                    
                    for (Address address : addresses) {
                        JSONObject result = addressToJson(address);
                        results.put(result);
                    }
                    
                    if (callback != null) {
                        final JSONArray finalResults = results;
                        mainHandler.post(() -> callback.onGeocodeComplete(finalResults));
                    }
                } else {
                    if (callback != null) {
                        mainHandler.post(() -> callback.onGeocodeError("No results found"));
                    }
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Geocoding error: " + e.getMessage());
                if (callback != null) {
                    mainHandler.post(() -> callback.onGeocodeError(e.getMessage()));
                }
            }
        });
    }
    
    public interface GeocodeCallback {
        void onGeocodeComplete(JSONArray results);
        void onGeocodeError(String error);
    }
    
    // ============================================================
    // LOCATION UTILITIES
    // ============================================================
    
    /**
     * Get best last known location
     */
    private Location getBestLastKnownLocation() {
        Location bestLocation = null;
        float bestAccuracy = Float.MAX_VALUE;
        
        try {
            List<String> providers = locationManager.getAllProviders();
            
            for (String provider : providers) {
                if (ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    continue;
                }
                
                Location location = locationManager.getLastKnownLocation(provider);
                
                if (location != null) {
                    float accuracy = location.getAccuracy();
                    
                    if (accuracy < bestAccuracy) {
                        bestLocation = location;
                        bestAccuracy = accuracy;
                    }
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting last known location: " + e.getMessage());
        }
        
        return bestLocation;
    }
    
    /**
     * Request single location update
     */
    private void requestSingleUpdate() {
        try {
            Criteria criteria = new Criteria();
            criteria.setAccuracy(Criteria.ACCURACY_FINE);
            criteria.setPowerRequirement(Criteria.POWER_HIGH);
            
            String bestProvider = locationManager.getBestProvider(criteria, true);
            
            if (bestProvider != null) {
                LocationListener singleListener = new LocationListener() {
                    @Override
                    public void onLocationChanged(Location location) {
                        locationManager.removeUpdates(this);
                        processLocation(location);
                    }
                    
                    @Override
                    public void onStatusChanged(String provider, int status, Bundle extras) {}
                    
                    @Override
                    public void onProviderEnabled(String provider) {}
                    
                    @Override
                    public void onProviderDisabled(String provider) {
                        locationManager.removeUpdates(this);
                        notifyError("Location provider disabled");
                    }
                };
                
                if (ActivityCompat.checkSelfPermission(context,
                    Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    
                    locationManager.requestSingleUpdate(
                        bestProvider,
                        singleListener,
                        Looper.getMainLooper()
                    );
                    
                    // Set timeout
                    mainHandler.postDelayed(() -> {
                        locationManager.removeUpdates(singleListener);
                        if (lastKnownLocation == null) {
                            notifyError("Location timeout - unable to get fix");
                        }
                    }, locationTimeout);
                }
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error requesting single update: " + e.getMessage());
        }
    }
    
    /**
     * Check if location is better than current best
     */
    private boolean isBetterLocation(Location location, Location currentBest) {
        if (currentBest == null) return true;
        
        long timeDelta = location.getTime() - currentBest.getTime();
        boolean isSignificantlyNewer = timeDelta > LOCATION_STALE_THRESHOLD;
        boolean isSignificantlyOlder = timeDelta < -LOCATION_STALE_THRESHOLD;
        boolean isNewer = timeDelta > 0;
        
        if (isSignificantlyNewer) return true;
        if (isSignificantlyOlder) return false;
        
        int accuracyDelta = (int) (location.getAccuracy() - currentBest.getAccuracy());
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyMoreAccurate = accuracyDelta < -50;
        boolean isFromSameProvider = location.getProvider() != null &&
            location.getProvider().equals(currentBest.getProvider());
        
        if (isMoreAccurate) return true;
        if (isNewer && !isMoreAccurate) return false;
        if (isNewer && isSignificantlyMoreAccurate) return true;
        
        return false;
    }
    
    /**
     * Check if location is stale
     */
    private boolean isLocationStale(Location location) {
        return System.currentTimeMillis() - location.getTime() > LOCATION_STALE_THRESHOLD;
    }
    
    /**
     * Check if location services are enabled
     */
    public boolean isLocationEnabled() {
        try {
            return locationManager.isProviderEnabled(PROVIDER_GPS) ||
                   locationManager.isProviderEnabled(PROVIDER_NETWORK);
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Check location permission
     */
    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(context,
            Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    
    // ============================================================
    // LOCATION HISTORY
    // ============================================================
    
    /**
     * Get location history
     */
    public JSONArray getLocationHistory() {
        synchronized (locationHistory) {
            return locationHistory;
        }
    }
    
    /**
     * Clear location history
     */
    public void clearHistory() {
        synchronized (locationHistory) {
            locationHistory = new JSONArray();
            locationCount = 0;
        }
    }
    
    // ============================================================
    // GETTERS
    // ============================================================
    
    public Location getLastKnownLocation() {
        return lastKnownLocation;
    }
    
    public Location getBestLocation() {
        return bestLocation;
    }
    
    public long getLastLocationTime() {
        return lastLocationTime;
    }
    
    public int getLocationCount() {
        return locationCount;
    }
    
    public boolean isTracking() {
        return isTracking.get();
    }
    
    /**
     * Get last location as JSON
     */
    public JSONObject getLastLocationJson() {
        if (lastKnownLocation != null) {
            return locationToJson(lastKnownLocation);
        }
        return null;
    }
    
    // ============================================================
    // UTILITY
    // ============================================================
    
    private String formatTimestamp(long timestamp) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(new Date(timestamp));
        } catch (Exception e) {
            return String.valueOf(timestamp);
        }
    }
    
    private void notifyError(String error) {
        Log.e(TAG, error);
        if (callback != null) {
            mainHandler.post(() -> callback.onLocationError(error));
        }
    }
    
    private void notifyProviderEnabled(String provider) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProviderEnabled(provider));
        }
    }
    
    private void notifyProviderDisabled(String provider) {
        if (callback != null) {
            mainHandler.post(() -> callback.onProviderDisabled(provider));
        }
    }
    
    /**
     * Release all resources
     */
    public void shutdown() {
        stopTracking();
        clearHistory();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}