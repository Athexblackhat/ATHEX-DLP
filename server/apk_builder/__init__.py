# ATHEX DLP Framework - server/apk_builder/__init__.py
"""
ATHEX DLP Enterprise - APK Builder Module
==========================================
Handles custom APK generation with server configuration injection.

Components:
- APKBuilder: Main APK building engine
- ConfigInjector: Injects server config into APK source
- APKSigner: Signs the generated APK

Usage:
    from apk_builder import APKBuilder
    
    builder = APKBuilder()
    result = builder.build_apk(
        server_host="192.168.1.100",
        server_port=22533,
        app_name="System Service"
    )
"""

from .builder import APKBuilder
from .injector import ConfigInjector
from .signer import APKSigner

__all__ = [
    'APKBuilder',
    'ConfigInjector',
    'APKSigner',
]

__version__ = '2.0.0'