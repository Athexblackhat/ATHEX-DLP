"""
ATHEX DLP Enterprise - APK Signer
==================================
Handles APK signing using jarsigner, apksigner, or uber-apk-signer.
"""

import os
import logging
import subprocess
import tempfile
from pathlib import Path
from typing import Optional

logger = logging.getLogger(__name__)


class APKSigner:
    """
    Signs APK files using available signing tools.
    
    Priority:
    1. uber-apk-signer (easiest)
    2. apksigner (modern)
    3. jarsigner (legacy fallback)
    """
    
    def __init__(self):
        """Initialize APK Signer"""
        self.signer_tool = self._detect_signer_tool()
        logger.info(f"APKSigner initialized (using: {self.signer_tool})")
    
    def _detect_signer_tool(self) -> str:
        """Detect available signing tool"""
        # Check for uber-apk-signer
        if self._command_exists("java") and self._find_jar("uber-apk-signer"):
            return "uber-apk-signer"
        
        # Check for apksigner
        if self._command_exists("apksigner"):
            return "apksigner"
        
        # Check for jarsigner
        if self._command_exists("jarsigner"):
            return "jarsigner"
        
        # Fallback: try to generate self-signed
        logger.warning("No signing tool found. APK will be unsigned.")
        return "none"
    
    def _command_exists(self, command: str) -> bool:
        """Check if command exists in PATH"""
        try:
            subprocess.run(
                [command, "--version"] if command != "java" else [command, "-version"],
                capture_output=True,
                timeout=5
            )
            return True
        except (subprocess.TimeoutExpired, FileNotFoundError, PermissionError):
            return False
        except Exception:
            return False
    
    def _find_jar(self, jar_name: str) -> Optional[Path]:
        """Find a JAR file"""
        search_paths = [
            Path.cwd() / "tools",
            Path.cwd() / ".." / "tools",
            Path(__file__).parent.parent.parent / "tools",
        ]
        
        for search_path in search_paths:
            jar_path = search_path / f"{jar_name}.jar"
            if jar_path.exists():
                return jar_path
        
        return None
    
    def sign_apk(self,
                 input_apk: Path,
                 output_apk: Path,
                 keystore_path: Optional[Path] = None,
                 keystore_pass: str = "android",
                 key_alias: str = "athex",
                 key_pass: str = "android"):
        """
        Sign an APK file.
        
        Args:
            input_apk: Path to unsigned APK
            output_apk: Path for signed APK
            keystore_path: Path to keystore
            keystore_pass: Keystore password
            key_alias: Key alias
            key_pass: Key password
        """
        
        if not input_apk.exists():
            raise FileNotFoundError(f"APK not found: {input_apk}")
        
        logger.info(f"Signing APK: {input_apk}")
        logger.info(f"  Output: {output_apk}")
        logger.info(f"  Tool: {self.signer_tool}")
        
        if self.signer_tool == "uber-apk-signer":
            self._sign_with_uber(input_apk, output_apk, keystore_path, 
                                keystore_pass, key_alias, key_pass)
        
        elif self.signer_tool == "apksigner":
            self._sign_with_apksigner(input_apk, output_apk, keystore_path,
                                      keystore_pass, key_alias, key_pass)
        
        elif self.signer_tool == "jarsigner":
            self._sign_with_jarsigner(input_apk, output_apk, keystore_path,
                                      keystore_pass, key_alias, key_pass)
        
        else:
            # No signing tool - just copy
            logger.warning("No signing tool available. Copying unsigned APK.")
            import shutil
            shutil.copy2(input_apk, output_apk)
    
    def _sign_with_uber(self, input_apk: Path, output_apk: Path,
                        keystore_path: Optional[Path], keystore_pass: str,
                        key_alias: str, key_pass: str):
        """Sign using uber-apk-signer"""
        jar_path = self._find_jar("uber-apk-signer")
        
        if not jar_path:
            raise FileNotFoundError("uber-apk-signer.jar not found")
        
        cmd = [
            "java", "-jar", str(jar_path),
            "--apks", str(input_apk),
            "--out", str(output_apk.parent),
        ]
        
        if keystore_path and keystore_path.exists():
            cmd.extend([
                "--ks", str(keystore_path),
                "--ksAlias", key_alias,
                "--ksPass", keystore_pass,
                "--ksKeyPass", key_pass,
            ])
        
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            
            if result.returncode != 0:
                raise RuntimeError(f"uber-apk-signer failed:\n{result.stderr}")
            
            # Find signed APK (uber-apk-signer may rename it)
            signed_files = list(output_apk.parent.glob("*.apk"))
            if signed_files:
                import shutil
                shutil.copy2(signed_files[0], output_apk)
            
            logger.info("✓ Signed with uber-apk-signer")
            
        except subprocess.TimeoutExpired:
            raise RuntimeError("uber-apk-signer timed out (60s)")
        except FileNotFoundError:
            raise RuntimeError("Java not found. Please install Java JDK.")
    
    def _sign_with_apksigner(self, input_apk: Path, output_apk: Path,
                             keystore_path: Optional[Path], keystore_pass: str,
                             key_alias: str, key_pass: str):
        """Sign using apksigner"""
        cmd = [
            "apksigner", "sign",
            "--out", str(output_apk),
        ]
        
        if keystore_path and keystore_path.exists():
            cmd.extend([
                "--ks", str(keystore_path),
                "--ks-pass", f"pass:{keystore_pass}",
                "--ks-key-alias", key_alias,
                "--key-pass", f"pass:{key_pass}",
            ])
        
        cmd.append(str(input_apk))
        
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            
            if result.returncode != 0:
                raise RuntimeError(f"apksigner failed:\n{result.stderr}")
            
            logger.info("✓ Signed with apksigner")
            
        except subprocess.TimeoutExpired:
            raise RuntimeError("apksigner timed out (60s)")
        except FileNotFoundError:
            raise RuntimeError("apksigner not found. Please install Android SDK build-tools.")
    
    def _sign_with_jarsigner(self, input_apk: Path, output_apk: Path,
                             keystore_path: Optional[Path], keystore_pass: str,
                             key_alias: str, key_pass: str):
        """Sign using jarsigner"""
        cmd = [
            "jarsigner", "-verbose",
            "-sigalg", "SHA256withRSA",
            "-digestalg", "SHA-256",
        ]
        
        if keystore_path and keystore_path.exists():
            cmd.extend([
                "-keystore", str(keystore_path),
                "-storepass", keystore_pass,
                "-keypass", key_pass,
            ])
        
        cmd.append(str(input_apk))
        cmd.append(key_alias)
        
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            
            if result.returncode != 0:
                raise RuntimeError(f"jarsigner failed:\n{result.stderr}")
            
            # Copy signed APK
            import shutil
            shutil.copy2(input_apk, output_apk)
            
            logger.info("✓ Signed with jarsigner")
            
        except subprocess.TimeoutExpired:
            raise RuntimeError("jarsigner timed out (60s)")
        except FileNotFoundError:
            raise RuntimeError("jarsigner not found. Please install Java JDK.")
    
    def generate_keystore(self, 
                          output_path: Path,
                          password: str = "android",
                          alias: str = "athex",
                          validity_days: int = 10000) -> Path:
        """
        Generate a new keystore for signing.
        
        Args:
            output_path: Path to save keystore
            password: Keystore password
            alias: Key alias
            validity_days: Certificate validity in days
            
        Returns:
            Path to generated keystore
        """
        
        if not self._command_exists("keytool"):
            raise RuntimeError("keytool not found. Please install Java JDK.")
        
        dname = "CN=ATHEX DLP, OU=Security, O=ATHEX, L=Unknown, ST=Unknown, C=US"
        
        cmd = [
            "keytool", "-genkey", "-v",
            "-keystore", str(output_path),
            "-alias", alias,
            "-keyalg", "RSA",
            "-keysize", "2048",
            "-validity", str(validity_days),
            "-storepass", password,
            "-keypass", password,
            "-dname", dname,
        ]
        
        try:
            result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
            
            if result.returncode != 0:
                raise RuntimeError(f"keytool failed:\n{result.stderr}")
            
            logger.info(f"✓ Keystore generated: {output_path}")
            return output_path
            
        except subprocess.TimeoutExpired:
            raise RuntimeError("keytool timed out (30s)")
        except FileNotFoundError:
            raise RuntimeError("keytool not found. Please install Java JDK.")
    
    def verify_apk(self, apk_path: Path) -> bool:
        """Verify APK signature"""
        if not apk_path.exists():
            return False
        
        if self._command_exists("jarsigner"):
            try:
                cmd = ["jarsigner", "-verify", "-verbose", str(apk_path)]
                result = subprocess.run(cmd, capture_output=True, text=True, timeout=30)
                return result.returncode == 0
            except (subprocess.TimeoutExpired, FileNotFoundError, Exception):
                return False
        
        return True  # Can't verify


# ============================================================
# TEST
# ============================================================
if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    
    signer = APKSigner()
    
    print(f"\n✍️ APKSigner ready")
    print(f"  Signer tool: {signer.signer_tool}")
    print(f"  Tools available:")
    print(f"    keytool:     {signer._command_exists('keytool')}")
    print(f"    apksigner:   {signer._command_exists('apksigner')}")
    print(f"    jarsigner:   {signer._command_exists('jarsigner')}")