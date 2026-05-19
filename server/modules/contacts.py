"""
ATHEX DLP Enterprise - Contacts Module
=======================================
Processes contact data from Android devices.
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class ContactsHandler:
    """Handles contact data processing"""
    
    def __init__(self):
        self.cached_contacts: Dict[str, List[Dict]] = {}
        logger.info("ContactsHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process raw contact data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw data from device (JSON string or list)
            
        Returns:
            Processed contacts data
        """
        try:
            # Parse JSON if string
            if isinstance(raw_data, str):
                contacts = json.loads(raw_data)
            else:
                contacts = raw_data
            
            # Ensure it's a list
            if not isinstance(contacts, list):
                contacts = [contacts]
            
            # Process each contact
            processed = []
            for contact in contacts:
                processed.append(self._process_contact(contact))
            
            # Cache
            self.cached_contacts[session_id] = processed
            
            logger.info(f"📇 Processed {len(processed)} contacts from {session_id}")
            
            return {
                "success": True,
                "session_id": session_id,
                "contacts": processed,
                "count": len(processed),
                "timestamp": datetime.now().isoformat()
            }
            
        except Exception as e:
            logger.error(f"Error processing contacts: {e}")
            return {
                "success": False,
                "error": str(e)
            }
    
    def _process_contact(self, contact: Dict) -> Dict:
        """Process individual contact"""
        processed = {
            "name": contact.get("name", "Unknown"),
            "phones": [],
            "emails": [],
            "organization": contact.get("organization", {}),
            "starred": contact.get("starred", False),
            "last_contacted": contact.get("last_contacted_date"),
            "times_contacted": contact.get("times_contacted", 0)
        }
        
        # Process phone numbers
        for phone in contact.get("phones", []):
            processed["phones"].append({
                "number": phone.get("number", ""),
                "type": phone.get("type", "Other"),
                "is_primary": phone.get("is_primary", False)
            })
        
        # Process emails
        for email in contact.get("emails", []):
            processed["emails"].append({
                "address": email.get("address", ""),
                "type": email.get("type", "Other")
            })
        
        return processed
    
    def format_for_dashboard(self, contacts: List[Dict]) -> List[Dict]:
        """Format contacts for dashboard display"""
        dashboard_data = []
        
        for contact in contacts[:100]:
            dashboard_data.append({
                "name": contact.get("name", "Unknown"),
                "phone": contact.get("phones", [{}])[0].get("number", "N/A") if contact.get("phones") else "N/A",
                "email": contact.get("emails", [{}])[0].get("address", "N/A") if contact.get("emails") else "N/A",
                "starred": contact.get("starred", False),
                "total_phones": len(contact.get("phones", [])),
                "total_emails": len(contact.get("emails", []))
            })
        
        return dashboard_data
    
    def get_cached(self, session_id: str) -> List[Dict]:
        """Get cached contacts for a session"""
        return self.cached_contacts.get(session_id, [])
    
    def search(self, session_id: str, query: str) -> List[Dict]:
        """Search contacts by name or number"""
        contacts = self.get_cached(session_id)
        query = query.lower()
        
        results = []
        for contact in contacts:
            # Search by name
            if query in contact.get("name", "").lower():
                results.append(contact)
                continue
            
            # Search by phone
            for phone in contact.get("phones", []):
                if query in phone.get("number", ""):
                    results.append(contact)
                    break
        
        return results
    
    def get_stats(self, session_id: str) -> Dict:
        """Get contact statistics"""
        contacts = self.get_cached(session_id)
        
        total = len(contacts)
        with_phone = sum(1 for c in contacts if c.get("phones"))
        with_email = sum(1 for c in contacts if c.get("emails"))
        starred = sum(1 for c in contacts if c.get("starred"))
        
        return {
            "total": total,
            "with_phone": with_phone,
            "with_email": with_email,
            "starred": starred
        }
    
    def shutdown(self):
        """Clean shutdown"""
        self.cached_contacts.clear()
        logger.info("ContactsHandler shut down")


# ============================================================
# SINGLETON - THIS LINE IS REQUIRED FOR APP.PY TO IMPORT
# ============================================================
contacts_handler = ContactsHandler()