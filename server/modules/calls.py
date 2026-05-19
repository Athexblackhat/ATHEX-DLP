"""
ATHEX DLP Enterprise - Calls Module
====================================
Processes call log data from Android devices.
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class CallsHandler:
    """Handles call log data processing"""
    
    # Call type mapping
    CALL_TYPES = {
        1: "incoming",
        2: "outgoing",
        3: "missed",
        4: "voicemail",
        5: "rejected",
        6: "blocked"
    }
    
    def __init__(self):
        self.cached_calls: Dict[str, List[Dict]] = {}
        logger.info("CallsHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process call log data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw call log data
            
        Returns:
            Processed call log data
        """
        try:
            if isinstance(raw_data, str):
                calls = json.loads(raw_data)
            else:
                calls = raw_data
            
            if not isinstance(calls, list):
                calls = [calls]
            
            processed = []
            for call in calls:
                processed.append(self._process_call(call))
            
            # Sort by date (newest first)
            processed.sort(key=lambda x: x.get("date", 0), reverse=True)
            
            self.cached_calls[session_id] = processed
            
            # Statistics
            incoming = sum(1 for c in processed if c.get("type") == "incoming")
            outgoing = sum(1 for c in processed if c.get("type") == "outgoing")
            missed = sum(1 for c in processed if c.get("type") == "missed")
            total_duration = sum(c.get("duration", 0) for c in processed)
            
            logger.info(f"📞 Processed {len(processed)} calls from {session_id}")
            
            return {
                "success": True,
                "session_id": session_id,
                "calls": processed,
                "count": len(processed),
                "stats": {
                    "incoming": incoming,
                    "outgoing": outgoing,
                    "missed": missed,
                    "total_duration": total_duration,
                    "total_duration_formatted": self._format_duration(total_duration)
                },
                "timestamp": datetime.now().isoformat()
            }
            
        except Exception as e:
            logger.error(f"Error processing calls: {e}")
            return {"success": False, "error": str(e)}
    
    def _process_call(self, call: Dict) -> Dict:
        """Process individual call record"""
        call_type = call.get("call_type", 0)
        
        return {
            "id": call.get("_id", 0),
            "number": call.get("number", "Unknown"),
            "contact_name": call.get("contact_name", ""),
            "type": self.CALL_TYPES.get(call_type, "unknown"),
            "type_code": call_type,
            "date": call.get("date", 0),
            "date_formatted": call.get("date_formatted", ""),
            "duration": call.get("duration_seconds", 0),
            "duration_formatted": self._format_duration(call.get("duration_seconds", 0)),
            "is_read": call.get("is_read", True),
            "geocoded_location": call.get("geocoded_location", ""),
            "via_number": call.get("via_number", "")
        }
    
    def _format_duration(self, seconds: int) -> str:
        """Format duration in seconds to readable string"""
        if seconds < 60:
            return f"{seconds}s"
        minutes = seconds // 60
        secs = seconds % 60
        if minutes < 60:
            return f"{minutes}m {secs}s"
        hours = minutes // 60
        mins = minutes % 60
        return f"{hours}h {mins}m {secs}s"
    
    def get_contacts_summary(self, session_id: str) -> List[Dict]:
        """Get call summary grouped by contact"""
        calls = self.cached_calls.get(session_id, [])
        
        contacts = {}
        for call in calls:
            number = call.get("number", "Unknown")
            if number not in contacts:
                contacts[number] = {
                    "number": number,
                    "contact_name": call.get("contact_name", ""),
                    "total_calls": 0,
                    "incoming": 0,
                    "outgoing": 0,
                    "missed": 0,
                    "total_duration": 0,
                    "last_call": None
                }
            
            c = contacts[number]
            c["total_calls"] += 1
            c["total_duration"] += call.get("duration", 0)
            
            if call.get("type") == "incoming":
                c["incoming"] += 1
            elif call.get("type") == "outgoing":
                c["outgoing"] += 1
            elif call.get("type") == "missed":
                c["missed"] += 1
            
            if not c["last_call"] or call.get("date", 0) > c["last_call"].get("date", 0):
                c["last_call"] = call
        
        return sorted(contacts.values(), key=lambda x: x["total_calls"], reverse=True)
    
    def format_for_dashboard(self, calls: List[Dict]) -> List[Dict]:
        """Format calls for dashboard display"""
        dashboard = []
        
        for call in calls[:50]:
            dashboard.append({
                "number": call.get("number", "Unknown"),
                "name": call.get("contact_name", "N/A"),
                "type": call.get("type", "unknown"),
                "date": call.get("date_formatted", ""),
                "duration": call.get("duration_formatted", "0s"),
                "icon": "📞" if call.get("type") == "incoming" else 
                        "📤" if call.get("type") == "outgoing" else 
                        "❌" if call.get("type") == "missed" else "📞"
            })
        
        return dashboard


calls_handler = CallsHandler()