# ATHEX DLP Framework - server/core/__init__.py
"""
ATHEX DLP Enterprise - Core Module
===================================
Handles TCP connections, device management, 
command dispatching, and Socket.IO events.

Components:
- TCPServer: Multi-threaded TCP server for Android connections
- DeviceManager: Connected device tracking & session management
- CommandDispatcher: Routes commands between server and devices
- SocketManager: Real-time WebSocket event handling
"""

from .tcp_server import TCPServer
from .device_manager import DeviceManager
from .command_dispatcher import CommandDispatcher
from .socket_manager import SocketManager

__all__ = [
    'TCPServer',
    'DeviceManager',
    'CommandDispatcher',
    'SocketManager',
]

__version__ = '2.0.0'
__author__ = 'ATHEX DLP Team'