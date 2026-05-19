"""
ATHEX DLP Enterprise - Device Manager
======================================
Manages connected Android devices, their sessions, permissions,
and provides device tracking & monitoring capabilities.
"""

import json
import logging
import threading
import time
from datetime import datetime
from typing import Dict, List, Optional, Callable
from collections import OrderedDict

logger = logging.getLogger(__name__)


class DeviceManager:
    """
    Manages all connected Android devices.
    
    Features:
    - Device registration & tracking
    - Session management
    - Device grouping (by model, OS, etc.)
    - Permission tracking
    - Activity monitoring
    - Data storage per device
    - Device commands queue
    - Offline device tracking
    """
    
    def __init__(self, max_devices: int = 100):
        """
        Initialize Device Manager.
        
        Args:
            max_devices: Maximum number of devices to track
        """
        self.max_devices = max_devices
        
        # Device storage
        self.devices: OrderedDict[str, 'Device'] = OrderedDict()
        self.device_lock = threading.RLock()
        
        # Online/Offline tracking
        self.online_devices: Dict[str, 'Device'] = {}
        self.offline_devices: Dict[str, 'Device'] = {}
        
        # Command queues per device
        self.command_queues: Dict[str, List[str]] = {}
        
        # Callbacks
        self.on_device_connected: Optional[Callable] = None
        self.on_device_disconnected: Optional[Callable] = None
        self.on_device_updated: Optional[Callable] = None
        
        # Statistics
        self.total_devices_seen = 0
        self.total_connections = 0
        self.total_disconnections = 0
        
        logger.info(f"DeviceManager initialized (max: {max_devices} devices)")
    
    def set_callbacks(self,
                      on_connected: Callable = None,
                      on_disconnected: Callable = None,
                      on_updated: Callable = None):
        """Set device event callbacks"""
        self.on_device_connected = on_connected
        self.on_device_disconnected = on_disconnected
        self.on_device_updated = on_updated
    
    def register_device(self, session_id: str, device_info: Dict) -> 'Device':
        """
        Register a new device or update existing.
        
        Args:
            session_id: Unique session identifier
            device_info: Device information dictionary
            
        Returns:
            Device object
        """
        with self.device_lock:
            # Check if device already exists
            if session_id in self.devices:
                device = self.devices[session_id]
                device.update_info(device_info)
                device.last_seen = datetime.now()
                
                if self.on_device_updated:
                    self.on_device_updated(device)
                
                return device
            
            # Check max devices limit
            if len(self.devices) >= self.max_devices:
                # Remove oldest offline device
                oldest_id = None
                for did, dev in self.devices.items():
                    if not dev.is_online:
                        oldest_id = did
                        break
                
                if oldest_id:
                    self.remove_device(oldest_id)
                else:
                    logger.warning("Max devices reached, cannot register new device")
                    return None
            
            # Create new device
            device = Device(
                session_id=session_id,
                device_info=device_info
            )
            
            self.devices[session_id] = device
            self.online_devices[session_id] = device
            self.total_devices_seen += 1
            self.total_connections += 1
            
            # Initialize command queue
            self.command_queues[session_id] = []
            
            logger.info(f"📱 Device registered: {device.model} ({device.device_id[:8]}...)")
            
            if self.on_device_connected:
                self.on_device_connected(device)
            
            return device
    
    def unregister_device(self, session_id: str, reason: str = "Disconnected"):
        """
        Unregister a device (mark as offline).
        
        Args:
            session_id: Device session ID
            reason: Disconnection reason
        """
        with self.device_lock:
            if session_id in self.devices:
                device = self.devices[session_id]
                device.mark_offline(reason)
                
                # Move to offline
                if session_id in self.online_devices:
                    del self.online_devices[session_id]
                
                self.offline_devices[session_id] = device
                self.total_disconnections += 1
                
                logger.info(f"📴 Device offline: {device.model} - {reason}")
                
                if self.on_device_disconnected:
                    self.on_device_disconnected(device)
    
    def remove_device(self, session_id: str):
        """Permanently remove a device"""
        with self.device_lock:
            if session_id in self.devices:
                del self.devices[session_id]
            
            self.online_devices.pop(session_id, None)
            self.offline_devices.pop(session_id, None)
            self.command_queues.pop(session_id, None)
    
    def get_device(self, session_id: str) -> Optional['Device']:
        """Get device by session ID"""
        with self.device_lock:
            return self.devices.get(session_id)
    
    def get_online_devices(self) -> List['Device']:
        """Get all online devices"""
        with self.device_lock:
            return list(self.online_devices.values())
    
    def get_offline_devices(self) -> List['Device']:
        """Get all offline devices"""
        with self.device_lock:
            return list(self.offline_devices.values())
    
    def get_all_devices(self) -> List['Device']:
        """Get all devices (online + offline)"""
        with self.device_lock:
            return list(self.devices.values())
    
    def get_online_count(self) -> int:
        """Get number of online devices"""
        with self.device_lock:
            return len(self.online_devices)
    
    def get_total_count(self) -> int:
        """Get total number of devices"""
        with self.device_lock:
            return len(self.devices)
    
    def get_devices_by_model(self, model: str) -> List['Device']:
        """Get devices by model name"""
        with self.device_lock:
            return [d for d in self.devices.values() 
                   if model.lower() in d.model.lower()]
    
    def get_devices_by_os(self, version: str) -> List['Device']:
        """Get devices by Android version"""
        with self.device_lock:
            return [d for d in self.devices.values() 
                   if d.android_version.startswith(version)]
    
    def queue_command(self, session_id: str, command: str):
        """Queue a command for a device"""
        with self.device_lock:
            if session_id in self.command_queues:
                self.command_queues[session_id].append(command)
                logger.debug(f"Command queued for {session_id}: {command}")
    
    def get_pending_commands(self, session_id: str) -> List[str]:
        """Get and clear pending commands for a device"""
        with self.device_lock:
            commands = self.command_queues.get(session_id, [])
            self.command_queues[session_id] = []
            return commands
    
    def update_device_data(self, session_id: str, key: str, value):
        """Store custom data for a device"""
        with self.device_lock:
            if session_id in self.devices:
                self.devices[session_id].set_data(key, value)
    
    def get_device_data(self, session_id: str, key: str, default=None):
        """Get custom data for a device"""
        with self.device_lock:
            if session_id in self.devices:
                return self.devices[session_id].get_data(key, default)
        return default
    
    def get_stats(self) -> Dict:
        """Get device statistics"""
        with self.device_lock:
            online = len(self.online_devices)
            total = len(self.devices)
            
            # Count by model
            models = {}
            for device in self.devices.values():
                models[device.model] = models.get(device.model, 0) + 1
            
            # Count by Android version
            android_versions = {}
            for device in self.devices.values():
                ver = device.android_version.split('.')[0] if device.android_version else "Unknown"
                android_versions[ver] = android_versions.get(ver, 0) + 1
            
            return {
                "online": online,
                "offline": total - online,
                "total": total,
                "total_seen": self.total_devices_seen,
                "total_connections": self.total_connections,
                "total_disconnections": self.total_disconnections,
                "by_model": models,
                "by_android_version": android_versions
            }
    
    def export_devices_json(self) -> str:
        """Export all devices as JSON string"""
        with self.device_lock:
            devices_list = [device.to_dict() for device in self.devices.values()]
            return json.dumps(devices_list, indent=2, default=str)


class Device:
    """Represents a single Android device"""
    
    def __init__(self, session_id: str, device_info: Dict):
        """
        Initialize device.
        
        Args:
            session_id: Unique session ID
            device_info: Device information dictionary
        """
        self.session_id = session_id
        
        # Basic info
        self.model = device_info.get('device_model', 'Unknown')
        self.android_version = device_info.get('android_version', 'Unknown')
        self.device_id = device_info.get('device_id', 'Unknown')
        self.ip_address = device_info.get('ip_address', '0.0.0.0')
        self.manufacturer = device_info.get('manufacturer', 'Unknown')
        self.product = device_info.get('product', 'Unknown')
        
        # State
        self.is_online = True
        self.first_seen = datetime.now()
        self.last_seen = datetime.now()
        self.offline_since: Optional[datetime] = None
        self.offline_reason: str = ""
        
        # Statistics
        self.connection_count = 1
        self.disconnection_count = 0
        self.notification_count = 0
        self.messages_received = 0
        self.messages_sent = 0
        self.data_collected = 0
        
        # Permissions
        self.permissions: Dict[str, bool] = {}
        
        # Custom data storage
        self.custom_data: Dict[str, any] = {}
        
        # GPS
        self.last_latitude: Optional[float] = None
        self.last_longitude: Optional[float] = None
        self.last_location_time: Optional[datetime] = None
    
    def update_info(self, info: Dict):
        """Update device information"""
        if 'device_model' in info:
            self.model = info['device_model']
        if 'android_version' in info:
            self.android_version = info['android_version']
        if 'device_id' in info:
            self.device_id = info['device_id']
        if 'ip_address' in info:
            self.ip_address = info['ip_address']
        if 'manufacturer' in info:
            self.manufacturer = info['manufacturer']
        if 'product' in info:
            self.product = info['product']
        
        self.last_seen = datetime.now()
    
    def mark_online(self):
        """Mark device as online"""
        self.is_online = True
        self.offline_since = None
        self.offline_reason = ""
        self.connection_count += 1
        self.last_seen = datetime.now()
    
    def mark_offline(self, reason: str = "Disconnected"):
        """Mark device as offline"""
        self.is_online = False
        self.offline_since = datetime.now()
        self.offline_reason = reason
        self.disconnection_count += 1
    
    def update_location(self, latitude: float, longitude: float):
        """Update device GPS location"""
        self.last_latitude = latitude
        self.last_longitude = longitude
        self.last_location_time = datetime.now()
    
    def set_permission(self, permission: str, granted: bool):
        """Set permission status"""
        self.permissions[permission] = granted
    
    def has_permission(self, permission: str) -> bool:
        """Check if permission is granted"""
        return self.permissions.get(permission, False)
    
    def set_data(self, key: str, value):
        """Store custom data"""
        self.custom_data[key] = value
    
    def get_data(self, key: str, default=None):
        """Get custom data"""
        return self.custom_data.get(key, default)
    
    def increment_notifications(self, count: int = 1):
        """Increment notification count"""
        self.notification_count += count
    
    def increment_messages(self, received: int = 0, sent: int = 0):
        """Increment message counts"""
        self.messages_received += received
        self.messages_sent += sent
    
    def get_uptime(self) -> float:
        """Get device session uptime in seconds"""
        if self.is_online:
            return (datetime.now() - self.last_seen).total_seconds()
        return 0
    
    def get_offline_duration(self) -> float:
        """Get offline duration in seconds"""
        if not self.is_online and self.offline_since:
            return (datetime.now() - self.offline_since).total_seconds()
        return 0
    
    def to_dict(self) -> Dict:
        """Convert device to dictionary"""
        return {
            "session_id": self.session_id,
            "model": self.model,
            "android_version": self.android_version,
            "device_id": self.device_id,
            "ip_address": self.ip_address,
            "manufacturer": self.manufacturer,
            "product": self.product,
            "is_online": self.is_online,
            "first_seen": self.first_seen.isoformat() if self.first_seen else None,
            "last_seen": self.last_seen.isoformat() if self.last_seen else None,
            "offline_since": self.offline_since.isoformat() if self.offline_since else None,
            "offline_reason": self.offline_reason,
            "connection_count": self.connection_count,
            "disconnection_count": self.disconnection_count,
            "notification_count": self.notification_count,
            "messages_received": self.messages_received,
            "messages_sent": self.messages_sent,
            "permissions": self.permissions,
            "location": {
                "latitude": self.last_latitude,
                "longitude": self.last_longitude,
                "updated": self.last_location_time.isoformat() if self.last_location_time else None
            } if self.last_latitude else None
        }
    
    def __repr__(self):
        status = "🟢" if self.is_online else "🔴"
        return f"{status} {self.model} ({self.android_version}) - {self.ip_address}"


# ============================================================
# TEST
# ============================================================
if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    
    manager = DeviceManager(max_devices=50)
    
    # Register test devices
    d1 = manager.register_device("sess_001", {
        "device_model": "Samsung S24 Ultra",
        "android_version": "14.0",
        "device_id": "abc123def456",
        "ip_address": "192.168.1.100",
        "manufacturer": "Samsung",
        "product": "galaxy_s24"
    })
    
    d2 = manager.register_device("sess_002", {
        "device_model": "Xiaomi 14 Pro",
        "android_version": "14.0",
        "device_id": "xyz789ghi012",
        "ip_address": "192.168.1.101",
        "manufacturer": "Xiaomi"
    })
    
    print(f"\n📱 Online Devices: {manager.get_online_count()}")
    print(f"📊 Stats: {json.dumps(manager.get_stats(), indent=2)}")
    
    # Simulate offline
    manager.unregister_device("sess_002", "User disconnected")
    print(f"\n📱 Online Devices: {manager.get_online_count()}")
    print(f"📴 Offline Devices: {len(manager.get_offline_devices())}")