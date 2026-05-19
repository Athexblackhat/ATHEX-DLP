package com.athex.dlp.crypto;

import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.AlgorithmParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * ATHEX DLP Enterprise - AESEncryption
 * 
 * Military-grade AES encryption module for secure data operations.
 * Supports multiple encryption modes:
 * - AES-256-CBC with PKCS5Padding
 * - AES-256-GCM (Authenticated Encryption)
 * - AES-256-CTR (Stream mode)
 * 
 * Features:
 * - File encryption/decryption
 * - String encryption/decryption
 * - Secure key generation
 * - PBKDF2 key derivation from passwords
 * - Random IV generation
 * - Encrypted file header for metadata
 * - Progress callbacks for large files
 * - Integrity verification
 * 
 * @author ATHEX DLP Team
 * @version 2.0.0
 */
public class AESEncryption {
    
    private static final String TAG = "ATHEX_AESEncryption";
    
    // ============================================================
    // CONSTANTS
    // ============================================================
    
    // Encryption algorithms
    public static final String ALGORITHM_AES = "AES";
    public static final String TRANSFORMATION_CBC = "AES/CBC/PKCS5Padding";
    public static final String TRANSFORMATION_GCM = "AES/GCM/NoPadding";
    public static final String TRANSFORMATION_CTR = "AES/CTR/NoPadding";
    
    // Key sizes
    public static final int KEY_SIZE_128 = 128;
    public static final int KEY_SIZE_192 = 192;
    public static final int KEY_SIZE_256 = 256;
    
    // GCM parameters
    private static final int GCM_TAG_LENGTH = 128; // bits
    private static final int GCM_IV_LENGTH = 12;   // bytes (recommended)
    
    // CBC parameters
    private static final int CBC_IV_LENGTH = 16;   // bytes
    
    // PBKDF2 parameters
    private static final int PBKDF2_ITERATIONS = 100000;
    private static final int PBKDF2_KEY_LENGTH = 256;
    private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
    
    // File magic bytes for encrypted files
    private static final byte[] MAGIC_BYTES = {0x41, 0x54, 0x48, 0x45, 0x58}; // "ATHEX"
    private static final int HEADER_VERSION = 1;
    
    // Buffer size for file operations
    private static final int BUFFER_SIZE = 65536; // 64KB
    
    // Default transformation
    private String defaultTransformation = TRANSFORMATION_CBC;
    private int defaultKeySize = KEY_SIZE_256;
    
    // ============================================================
    // INSTANCE VARIABLES
    // ============================================================
    
    private final SecureRandom secureRandom;
    private EncryptionCallback callback;
    
    // ============================================================
    // INTERFACES
    // ============================================================
    
    public interface EncryptionCallback {
        void onProgressUpdate(String fileName, long bytesProcessed, long totalBytes, int percentComplete);
        void onOperationComplete(String fileName, boolean success);
        void onError(String error);
    }
    
    // ============================================================
    // CONSTRUCTOR
    // ============================================================
    
    public AESEncryption() {
        this.secureRandom = new SecureRandom();
    }
    
    public AESEncryption(String defaultTransformation, int defaultKeySize) {
        this();
        this.defaultTransformation = defaultTransformation;
        this.defaultKeySize = defaultKeySize;
    }
    
    // ============================================================
    // CONFIGURATION
    // ============================================================
    
    public void setCallback(EncryptionCallback callback) {
        this.callback = callback;
    }
    
    public void setDefaultTransformation(String transformation) {
        this.defaultTransformation = transformation;
    }
    
    public void setDefaultKeySize(int keySize) {
        this.defaultKeySize = keySize;
    }
    
    // ============================================================
    // KEY GENERATION
    // ============================================================
    
    /**
     * Generate a random AES key
     * @param keySize Key size in bits (128, 192, or 256)
     * @return Base64 encoded key string
     */
    public String generateKey(int keySize) {
        try {
            byte[] keyBytes = new byte[keySize / 8];
            secureRandom.nextBytes(keyBytes);
            return Base64.encodeToString(keyBytes, Base64.NO_WRAP);
        } catch (Exception e) {
            Log.e(TAG, "Error generating key: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Generate default key (AES-256)
     */
    public String generateKey() {
        return generateKey(defaultKeySize);
    }
    
    /**
     * Derive key from password using PBKDF2
     * @param password User password
     * @param salt Salt for derivation
     * @return Base64 encoded derived key
     */
    public String deriveKeyFromPassword(String password, byte[] salt) {
        try {
            if (salt == null) {
                salt = new byte[32];
                secureRandom.nextBytes(salt);
            }
            
            PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt,
                PBKDF2_ITERATIONS,
                PBKDF2_KEY_LENGTH
            );
            
            SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
            byte[] keyBytes = factory.generateSecret(spec).getEncoded();
            
            // Combine salt and key for storage
            byte[] combined = new byte[salt.length + keyBytes.length];
            System.arraycopy(salt, 0, combined, 0, salt.length);
            System.arraycopy(keyBytes, 0, combined, salt.length, keyBytes.length);
            
            return Base64.encodeToString(combined, Base64.NO_WRAP);
            
        } catch (Exception e) {
            Log.e(TAG, "Error deriving key: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Generate random IV
     */
    public byte[] generateIV(int length) {
        byte[] iv = new byte[length];
        secureRandom.nextBytes(iv);
        return iv;
    }
    
    // ============================================================
    // STRING ENCRYPTION / DECRYPTION
    // ============================================================
    
    /**
     * Encrypt a string using AES-CBC
     * @param plainText Text to encrypt
     * @param base64Key Base64 encoded key
     * @return Base64 encoded ciphertext (IV + encrypted data)
     */
    public String encryptString(String plainText, String base64Key) {
        try {
            byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM_AES);
            
            // Generate random IV
            byte[] iv = generateIV(CBC_IV_LENGTH);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_CBC);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            
            // Encrypt
            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBytes = cipher.doFinal(plainBytes);
            
            // Combine IV + encrypted data
            byte[] combined = new byte[iv.length + encryptedBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);
            
            return Base64.encodeToString(combined, Base64.NO_WRAP);
            
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting string: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Decrypt a string using AES-CBC
     * @param cipherText Base64 encoded ciphertext (IV + encrypted data)
     * @param base64Key Base64 encoded key
     * @return Decrypted plaintext
     */
    public String decryptString(String cipherText, String base64Key) {
        try {
            byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM_AES);
            
            byte[] combined = Base64.decode(cipherText, Base64.NO_WRAP);
            
            // Extract IV (first 16 bytes)
            byte[] iv = new byte[CBC_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, CBC_IV_LENGTH);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            // Extract encrypted data
            byte[] encryptedBytes = new byte[combined.length - CBC_IV_LENGTH];
            System.arraycopy(combined, CBC_IV_LENGTH, encryptedBytes, 0, encryptedBytes.length);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_CBC);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
            
            // Decrypt
            byte[] plainBytes = cipher.doFinal(encryptedBytes);
            
            return new String(plainBytes, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            Log.e(TAG, "Error decrypting string: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Encrypt string with GCM (authenticated encryption)
     */
    public String encryptStringGCM(String plainText, String base64Key) {
        try {
            byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM_AES);
            
            // Generate random IV
            byte[] iv = generateIV(GCM_IV_LENGTH);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
            
            // Encrypt
            byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] encryptedBytes = cipher.doFinal(plainBytes);
            
            // Combine IV + encrypted data (with GCM tag appended by cipher)
            byte[] combined = new byte[iv.length + encryptedBytes.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(encryptedBytes, 0, combined, iv.length, encryptedBytes.length);
            
            return Base64.encodeToString(combined, Base64.NO_WRAP);
            
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting string (GCM): " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Decrypt string with GCM
     */
    public String decryptStringGCM(String cipherText, String base64Key) {
        try {
            byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM_AES);
            
            byte[] combined = Base64.decode(cipherText, Base64.NO_WRAP);
            
            // Extract IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            
            // Extract encrypted data
            byte[] encryptedBytes = new byte[combined.length - GCM_IV_LENGTH];
            System.arraycopy(combined, GCM_IV_LENGTH, encryptedBytes, 0, encryptedBytes.length);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_GCM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);
            
            // Decrypt
            byte[] plainBytes = cipher.doFinal(encryptedBytes);
            
            return new String(plainBytes, StandardCharsets.UTF_8);
            
        } catch (Exception e) {
            Log.e(TAG, "Error decrypting string (GCM): " + e.getMessage());
            return null;
        }
    }
    
    // ============================================================
    // FILE ENCRYPTION / DECRYPTION
    // ============================================================
    
    /**
     * Encrypt a file using AES-CBC
     * @param inputFile Source file
     * @param outputFile Destination encrypted file (.locked)
     * @param base64Key Base64 encoded key
     * @return true if successful
     */
    public boolean encryptFile(File inputFile, File outputFile, String base64Key) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        
        try {
            byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM_AES);
            
            // Generate random IV
            byte[] iv = generateIV(CBC_IV_LENGTH);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_CBC);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivSpec);
            
            fis = new FileInputStream(inputFile);
            fos = new FileOutputStream(outputFile);
            
            // Write header: magic bytes + version + IV
            fos.write(MAGIC_BYTES);
            fos.write(HEADER_VERSION);
            fos.write(iv.length);
            fos.write(iv);
            
            long totalBytes = inputFile.length();
            long processedBytes = 0;
            
            // Write encrypted content
            CipherOutputStream cos = new CipherOutputStream(fos, cipher);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                cos.write(buffer, 0, bytesRead);
                processedBytes += bytesRead;
                
                if (callback != null) {
                    int percent = (int) ((processedBytes * 100) / totalBytes);
                    callback.onProgressUpdate(
                        inputFile.getName(), processedBytes, totalBytes, percent
                    );
                }
            }
            
            cos.flush();
            cos.close();
            
            if (callback != null) {
                callback.onOperationComplete(inputFile.getName(), true);
            }
            
            Log.i(TAG, "File encrypted: " + outputFile.getAbsolutePath() + 
                " (" + totalBytes + " bytes)");
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error encrypting file: " + e.getMessage());
            
            // Clean up failed output file
            if (outputFile.exists()) {
                outputFile.delete();
            }
            
            if (callback != null) {
                callback.onError("Encryption failed: " + e.getMessage());
                callback.onOperationComplete(inputFile.getName(), false);
            }
            
            return false;
            
        } finally {
            try {
                if (fis != null) fis.close();
                if (fos != null) fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams: " + e.getMessage());
            }
        }
    }
    
    /**
     * Decrypt a file using AES-CBC
     * @param inputFile Encrypted file
     * @param outputFile Destination decrypted file
     * @param base64Key Base64 encoded key
     * @return true if successful
     */
    public boolean decryptFile(File inputFile, File outputFile, String base64Key) {
        FileInputStream fis = null;
        FileOutputStream fos = null;
        
        try {
            byte[] keyBytes = Base64.decode(base64Key, Base64.NO_WRAP);
            
            fis = new FileInputStream(inputFile);
            
            // Read header: magic bytes
            byte[] magic = new byte[MAGIC_BYTES.length];
            fis.read(magic);
            
            // Verify magic bytes
            if (!java.util.Arrays.equals(magic, MAGIC_BYTES)) {
                Log.e(TAG, "Invalid encrypted file format");
                if (callback != null) {
                    callback.onError("Invalid encrypted file format");
                }
                return false;
            }
            
            // Read version
            int version = fis.read();
            
            // Read IV length
            int ivLength = fis.read();
            
            // Read IV
            byte[] iv = new byte[ivLength];
            fis.read(iv);
            IvParameterSpec ivSpec = new IvParameterSpec(iv);
            
            SecretKeySpec secretKey = new SecretKeySpec(keyBytes, ALGORITHM_AES);
            
            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION_CBC);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);
            
            fos = new FileOutputStream(outputFile);
            
            long totalBytes = inputFile.length();
            long processedBytes = MAGIC_BYTES.length + 2 + ivLength;
            
            // Read encrypted content
            CipherInputStream cis = new CipherInputStream(fis, cipher);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            
            while ((bytesRead = cis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead);
                processedBytes += bytesRead;
                
                if (callback != null) {
                    int percent = (int) ((processedBytes * 100) / totalBytes);
                    callback.onProgressUpdate(
                        inputFile.getName(), processedBytes, totalBytes, percent
                    );
                }
            }
            
            fos.flush();
            
            if (callback != null) {
                callback.onOperationComplete(inputFile.getName(), true);
            }
            
            Log.i(TAG, "File decrypted: " + outputFile.getAbsolutePath());
            
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Error decrypting file: " + e.getMessage());
            
            if (outputFile.exists()) {
                outputFile.delete();
            }
            
            if (callback != null) {
                callback.onError("Decryption failed: " + e.getMessage());
                callback.onOperationComplete(inputFile.getName(), false);
            }
            
            return false;
            
        } finally {
            try {
                if (fis != null) fis.close();
                if (fos != null) fos.close();
            } catch (IOException e) {
                Log.e(TAG, "Error closing streams: " + e.getMessage());
            }
        }
    }
    
    /**
     * Encrypt file and delete original
     */
    public boolean encryptFileSecure(File inputFile, String base64Key) {
        File outputFile = new File(inputFile.getAbsolutePath() + ".locked");
        
        if (encryptFile(inputFile, outputFile, base64Key)) {
            // Securely delete original (overwrite with zeros)
            secureDelete(inputFile);
            return true;
        }
        return false;
    }
    
    /**
     * Decrypt file and delete encrypted version
     */
    public boolean decryptFileSecure(File inputFile, String base64Key) {
        String outputPath = inputFile.getAbsolutePath();
        if (outputPath.endsWith(".locked")) {
            outputPath = outputPath.substring(0, outputPath.length() - 7);
        } else {
            outputPath += ".decrypted";
        }
        
        File outputFile = new File(outputPath);
        
        if (decryptFile(inputFile, outputFile, base64Key)) {
            secureDelete(inputFile);
            return true;
        }
        return false;
    }
    
    // ============================================================
    // SECURE DELETE
    // ============================================================
    
    /**
     * Securely delete a file by overwriting with random data
     */
    private void secureDelete(File file) {
        try {
            if (!file.exists()) return;
            
            long length = file.length();
            RandomAccessFile raf = new RandomAccessFile(file, "rws");
            
            // Overwrite with zeros
            byte[] zeros = new byte[4096];
            for (long i = 0; i < length; i += zeros.length) {
                raf.write(zeros, 0, (int) Math.min(zeros.length, length - i));
            }
            
            // Overwrite with random data
            byte[] random = new byte[4096];
            secureRandom.nextBytes(random);
            raf.seek(0);
            for (long i = 0; i < length; i += random.length) {
                raf.write(random, 0, (int) Math.min(random.length, length - i));
            }
            
            // Overwrite with ones
            byte[] ones = new byte[4096];
            java.util.Arrays.fill(ones, (byte) 0xFF);
            raf.seek(0);
            for (long i = 0; i < length; i += ones.length) {
                raf.write(ones, 0, (int) Math.min(ones.length, length - i));
            }
            
            raf.close();
            
            // Finally delete
            file.delete();
            
            Log.d(TAG, "Securely deleted: " + file.getAbsolutePath());
            
        } catch (Exception e) {
            Log.e(TAG, "Error during secure delete: " + e.getMessage());
            // Fallback to normal delete
            file.delete();
        }
    }
    
    // ============================================================
    // HASHING
    // ============================================================
    
    /**
     * Calculate SHA-256 hash of a string
     */
    public String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "SHA-256 not available: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate SHA-256 hash of a file
     */
    public String sha256File(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
            
            fis.close();
            
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error hashing file: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * Calculate MD5 hash (for compatibility)
     */
    public String md5(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
            
        } catch (NoSuchAlgorithmException e) {
            Log.e(TAG, "MD5 not available: " + e.getMessage());
            return null;
        }
    }
    
    // ============================================================
    // UTILITY METHODS
    // ============================================================
    
    /**
     * Check if a file is encrypted by this module
     */
    public boolean isEncryptedFile(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            byte[] magic = new byte[MAGIC_BYTES.length];
            int read = fis.read(magic);
            fis.close();
            
            return read == MAGIC_BYTES.length && 
                   java.util.Arrays.equals(magic, MAGIC_BYTES);
                   
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Get encryption metadata from encrypted file
     */
    public String getEncryptionMetadata(File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            
            byte[] magic = new byte[MAGIC_BYTES.length];
            fis.read(magic);
            
            int version = fis.read();
            int ivLength = fis.read();
            
            fis.close();
            
            return "Version: " + version + ", IV Length: " + ivLength + " bytes";
            
        } catch (Exception e) {
            return "Cannot read metadata: " + e.getMessage();
        }
    }
}