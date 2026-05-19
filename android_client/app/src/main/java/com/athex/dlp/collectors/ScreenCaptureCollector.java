package com.athex.dlp.collectors;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * ATHEX DLP Enterprise - ScreenCaptureCollector
 * 
 * Advanced screen capture and recording module.
 * Uses MediaProjection API for:
 * - Single screenshot capture
 * - Continuous screen recording
 * - Configurable quality and resolution
 * - Background capture
 * - Base64 image output
 * 
 * Note: Requires MediaProjection permission (user consent on Android 5+)
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class ScreenCaptureCollector {
    
    private static final String TAG = "ATHEX_ScreenCapture";
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    public static final int QUALITY_LOW = 30;
    public static final int QUALITY_MEDIUM = 60;
    public static final int QUALITY_HIGH = 85;
    public static final int QUALITY_MAX = 100;
    
    private static final int SCREENSHOT_REQUEST_CODE = 2001;
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final Context context;
    private final WindowManager windowManager;
    private final MediaProjectionManager projectionManager;
    private final ExecutorService executor;
    private final Handler mainHandler;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    
    // MediaProjection
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    
    // Options
    private int screenshotQuality = QUALITY_HIGH;
    private int screenshotScale = 50; // percent (50 = half size)
    private int recordingBitrate = 2000000; // 2 Mbps
    private int recordingFPS = 15;
    private String outputDirectory;
    
    // Statistics
    private int screenshotsTaken = 0;
    private long lastScreenshotTime = 0;
    private long recordingStartTime = 0;
    private long totalRecordingDuration = 0;
    
    // Callbacks
    private CaptureCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface CaptureCallback {
        void onScreenshotReady(String base64Image, int width, int height, long fileSize);
        void onScreenshotSaved(File file);
        void onRecordingStarted(File outputFile);
        void onRecordingStopped(File outputFile, long durationMs);
        void onCaptureError(String error);
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public ScreenCaptureCollector(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.projectionManager = (MediaProjectionManager) context.getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        );
        this.executor = Executors.newSingleThreadExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.outputDirectory = Environment.getExternalStorageDirectory() + "/ATHEX_Screenshots/";
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public ScreenCaptureCollector setScreenshotQuality(int quality) {
        this.screenshotQuality = Math.max(1, Math.min(100, quality));
        return this;
    }
    
    public ScreenCaptureCollector setScreenshotScale(int percent) {
        this.screenshotScale = Math.max(10, Math.min(100, percent));
        return this;
    }
    
    public ScreenCaptureCollector setRecordingBitrate(int bitrate) {
        this.recordingBitrate = Math.max(500000, Math.min(10000000, bitrate));
        return this;
    }
    
    public ScreenCaptureCollector setRecordingFPS(int fps) {
        this.recordingFPS = Math.max(5, Math.min(60, fps));
        return this;
    }
    
    public ScreenCaptureCollector setOutputDirectory(String path) {
        this.outputDirectory = path;
        return this;
    }
    
    public ScreenCaptureCollector setCallback(CaptureCallback callback) {
        this.callback = callback;
        return this;
    }
    
    // ============================================================
    // INITIALIZATION
    // ============================================================
    
    /**
     * Initialize MediaProjection (requires user consent)
     * Call this from an Activity with the result from the permission intent
     */
    public void initialize(Intent data, int resultCode) {
        if (data == null || resultCode != Activity.RESULT_OK) {
            notifyError("MediaProjection permission denied");
            return;
        }
        
        executor.execute(() -> {
            try {
                mediaProjection = projectionManager.getMediaProjection(resultCode, data);
                
                if (mediaProjection == null) {
                    notifyError("Failed to create MediaProjection");
                    return;
                }
                
                // Start background thread
                backgroundThread = new HandlerThread("ScreenCapture");
                backgroundThread.start();
                backgroundHandler = new Handler(backgroundThread.getLooper());
                
                Log.i(TAG, "ScreenCapture initialized successfully");
                
            } catch (Exception e) {
                Log.e(TAG, "Error initializing MediaProjection: " + e.getMessage());
                notifyError("Initialization failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Create the permission intent (call from Activity)
     */
    public static Intent createPermissionIntent(Context context) {
        MediaProjectionManager manager = (MediaProjectionManager) 
            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        return manager.createScreenCaptureIntent();
    }
    
    // ============================================================
    // SCREENSHOT CAPTURE
    // ============================================================
    
    /**
     * Take a single screenshot
     */
    public void takeScreenshot() {
        if (mediaProjection == null) {
            notifyError("MediaProjection not initialized. Call initialize() first.");
            return;
        }
        
        if (isCapturing.get()) {
            Log.w(TAG, "Screenshot already in progress");
            return;
        }
        
        isCapturing.set(true);
        
        executor.execute(() -> {
            try {
                // Get display metrics
                DisplayMetrics metrics = new DisplayMetrics();
                Display display = windowManager.getDefaultDisplay();
                display.getRealMetrics(metrics);
                
                int screenWidth = metrics.widthPixels;
                int screenHeight = metrics.heightPixels;
                
                // Calculate scaled dimensions
                int scaledWidth = (screenWidth * screenshotScale) / 100;
                int scaledHeight = (screenHeight * screenshotScale) / 100;
                
                // Create ImageReader
                imageReader = ImageReader.newInstance(
                    screenWidth, screenHeight,
                    PixelFormat.RGBA_8888, 2
                );
                
                // Create virtual display
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenshotCapture",
                    screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.getSurface(),
                    null, backgroundHandler
                );
                
                // Wait for image (with timeout)
                Image image = null;
                long startTime = System.currentTimeMillis();
                
                while (image == null && (System.currentTimeMillis() - startTime) < 5000) {
                    image = imageReader.acquireLatestImage();
                    if (image == null) {
                        Thread.sleep(100);
                    }
                }
                
                if (image == null) {
                    notifyError("Timeout waiting for screenshot");
                    return;
                }
                
                // Convert to Bitmap
                Image.Plane[] planes = image.getPlanes();
                ByteBuffer buffer = planes[0].getBuffer();
                int pixelStride = planes[0].getPixelStride();
                int rowStride = planes[0].getRowStride();
                int rowPadding = rowStride - pixelStride * screenWidth;
                
                Bitmap bitmap = Bitmap.createBitmap(
                    screenWidth + rowPadding / pixelStride,
                    screenHeight,
                    Bitmap.Config.ARGB_8888
                );
                bitmap.copyPixelsFromBuffer(buffer);
                
                // Scale if needed
                if (screenshotScale < 100) {
                    bitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
                }
                
                image.close();
                
                // Compress to JPEG
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, screenshotQuality, baos);
                byte[] imageBytes = baos.toByteArray();
                baos.close();
                
                // Convert to Base64
                String base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
                
                // Save to file
                File outputFile = saveScreenshotToFile(imageBytes);
                
                // Cleanup
                bitmap.recycle();
                
                screenshotsTaken++;
                lastScreenshotTime = System.currentTimeMillis();
                
                Log.i(TAG, "Screenshot taken: " + 
                    scaledWidth + "x" + scaledHeight + 
                    " (" + imageBytes.length + " bytes)");
                
                // Notify
                if (callback != null) {
                    final String finalBase64 = base64Image;
                    final int finalWidth = scaledWidth;
                    final int finalHeight = scaledHeight;
                    final long finalSize = imageBytes.length;
                    
                    mainHandler.post(() -> {
                        callback.onScreenshotReady(finalBase64, finalWidth, finalHeight, finalSize);
                        if (outputFile != null) {
                            callback.onScreenshotSaved(outputFile);
                        }
                    });
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Error taking screenshot: " + e.getMessage());
                notifyError("Screenshot failed: " + e.getMessage());
            } finally {
                cleanupCapture();
                isCapturing.set(false);
            }
        });
    }
    
    /**
     * Save screenshot bytes to file
     */
    private File saveScreenshotToFile(byte[] imageBytes) {
        try {
            File dir = new File(outputDirectory);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
            File outputFile = new File(dir, "screenshot_" + timestamp + ".jpg");
            
            FileOutputStream fos = new FileOutputStream(outputFile);
            fos.write(imageBytes);
            fos.close();
            
            Log.d(TAG, "Screenshot saved: " + outputFile.getAbsolutePath());
            return outputFile;
            
        } catch (IOException e) {
            Log.e(TAG, "Error saving screenshot: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Take screenshot and return Base64 immediately
     */
    public void takeScreenshotBase64(ScreenshotCallback callback) {
        CaptureCallback tempCallback = new CaptureCallback() {
            @Override
            public void onScreenshotReady(String base64Image, int width, int height, long fileSize) {
                if (callback != null) {
                    callback.onScreenshotReady(base64Image, width, height, fileSize);
                }
            }
            
            @Override
            public void onScreenshotSaved(File file) {
                // Ignore
            }
            
            @Override
            public void onRecordingStarted(File outputFile) {}
            
            @Override
            public void onRecordingStopped(File outputFile, long durationMs) {}
            
            @Override
            public void onCaptureError(String error) {
                if (callback != null) {
                    callback.onScreenshotError(error);
                }
            }
        };
        
        this.callback = tempCallback;
        takeScreenshot();
    }
    
    public interface ScreenshotCallback {
        void onScreenshotReady(String base64Image, int width, int height, long fileSize);
        void onScreenshotError(String error);
    }
    
    // ============================================================
    // SCREEN RECORDING
    // ============================================================
    
    /**
     * Start screen recording
     */
    public void startRecording() {
        if (mediaProjection == null) {
            notifyError("MediaProjection not initialized");
            return;
        }
        
        if (isRecording.get()) {
            Log.w(TAG, "Already recording");
            return;
        }
        
        executor.execute(() -> {
            try {
                DisplayMetrics metrics = new DisplayMetrics();
                Display display = windowManager.getDefaultDisplay();
                display.getRealMetrics(metrics);
                
                int screenWidth = metrics.widthPixels;
                int screenHeight = metrics.heightPixels;
                
                // Create output file
                File dir = new File(outputDirectory);
                if (!dir.exists()) dir.mkdirs();
                
                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(new Date());
                File outputFile = new File(dir, "recording_" + timestamp + ".mp4");
                
                // Setup MediaCodec for encoding
                MediaFormat format = MediaFormat.createVideoFormat(
                    MediaFormat.MIME_TYPE_AVC,
                    screenWidth, screenHeight
                );
                format.setInteger(MediaFormat.KEY_BIT_RATE, recordingBitrate);
                format.setInteger(MediaFormat.KEY_FRAME_RATE, recordingFPS);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                
                MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIME_TYPE_AVC);
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                
                Surface inputSurface = encoder.createInputSurface();
                encoder.start();
                
                // Setup MediaMuxer
                MediaMuxer muxer = new MediaMuxer(
                    outputFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                );
                
                // Create virtual display for recording
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "ScreenRecording",
                    screenWidth, screenHeight, metrics.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    inputSurface,
                    null, backgroundHandler
                );
                
                isRecording.set(true);
                recordingStartTime = System.currentTimeMillis();
                
                Log.i(TAG, "Screen recording started: " + outputFile.getAbsolutePath());
                
                if (callback != null) {
                    mainHandler.post(() -> callback.onRecordingStarted(outputFile));
                }
                
                // Store references for stopping
                // (In production, these would be stored and used in stopRecording)
                
            } catch (Exception e) {
                Log.e(TAG, "Error starting recording: " + e.getMessage());
                notifyError("Recording failed: " + e.getMessage());
            }
        });
    }
    
    /**
     * Stop screen recording
     */
    public void stopRecording() {
        if (!isRecording.get()) {
            return;
        }
        
        isRecording.set(false);
        long duration = System.currentTimeMillis() - recordingStartTime;
        totalRecordingDuration += duration;
        
        cleanupCapture();
        
        Log.i(TAG, "Screen recording stopped. Duration: " + (duration / 1000) + "s");
        
        // Note: Full implementation would properly stop encoder and muxer
        // and provide the output file through callback
    }
    
    // ============================================================
    // DISPLAY INFORMATION
    // ============================================================
    
    /**
     * Get display information
     */
    public JSONObject getDisplayInfo() {
        JSONObject info = new JSONObject();
        
        try {
            DisplayMetrics metrics = new DisplayMetrics();
            Display display = windowManager.getDefaultDisplay();
            display.getRealMetrics(metrics);
            
            info.put("width_pixels", metrics.widthPixels);
            info.put("height_pixels", metrics.heightPixels);
            info.put("density_dpi", metrics.densityDpi);
            info.put("density", metrics.density);
            info.put("scaled_density", metrics.scaledDensity);
            info.put("xdpi", metrics.xdpi);
            info.put("ydpi", metrics.ydpi);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                info.put("screen_width_dp", 
                    (int) (metrics.widthPixels / metrics.density));
                info.put("screen_height_dp", 
                    (int) (metrics.heightPixels / metrics.density));
            }
            
            info.put("refresh_rate", display.getRefreshRate());
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                info.put("display_id", display.getDisplayId());
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting display info: " + e.getMessage());
        }
        
        return info;
    }
    
    // ============================================================
    // CLEANUP
    // ============================================================
    
    private void cleanupCapture() {
        try {
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
            
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error during cleanup: " + e.getMessage());
        }
    }
    
    /**
     * Release all resources
     */
    public void release() {
        stopRecording();
        cleanupCapture();
        
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
        }
        
        Log.i(TAG, "ScreenCapture released");
    }
    
    // ============================================================
    // STATISTICS
    // ============================================================
    
    public int getScreenshotsTaken() {
        return screenshotsTaken;
    }
    
    public long getLastScreenshotTime() {
        return lastScreenshotTime;
    }
    
    public boolean isRecording() {
        return isRecording.get();
    }
    
    public long getTotalRecordingDuration() {
        return totalRecordingDuration;
    }
    
    // ============================================================
    // CALLBACKS
    // ============================================================
    
    private void notifyError(String error) {
        Log.e(TAG, error);
        if (callback != null) {
            mainHandler.post(() -> callback.onCaptureError(error));
        }
    }
    
    public void shutdown() {
        release();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdown();
        }
    }
}