"""Comprehensive WebDAV flow verification for 戏曲 module"""
import subprocess
import re
import urllib.parse
import os
import json

AUTH = "13826527554:gw7ym269"
BASE_URL = "https://webdav-1855080734.pd1.123pan.cn"
BODY = '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getcontenttype/></D:prop></D:propfind>'

def propfind(path, depth=1):
    """Perform PROPFIND and return list of entries"""
    url = BASE_URL + path
    result = subprocess.run(
        ["curl.exe", "-s", "-X", "PROPFIND", url, "-u", AUTH,
         "-H", f"Depth: {depth}", "-H", "Content-Type: application/xml; charset=utf-8",
         "-d", BODY, "--insecure"],
        capture_output=True, timeout=30)
    
    if result.returncode != 0:
        print(f"  ERROR: curl failed with code {result.returncode}")
        return []
    
    xml = result.stdout.decode('utf-8')
    print(f"  Response length: {len(xml)} chars")
    
    # Parse entries
    entries = []
    base_href_decoded = urllib.parse.unquote(path).rstrip('/')
    
    for match in re.finditer(r'<D:response>(.*?)</D:response>', xml, re.DOTALL):
        block = match.group(1)
        href_m = re.search(r'<D:href>([^<]+)</D:href>', block)
        name_m = re.search(r'<D:displayname>([^<]*)</D:displayname>', block)
        size_m = re.search(r'<D:getcontentlength>([^<]*)</D:getcontentlength>', block)
        is_folder = '<D:collection' in block
        
        if not href_m:
            continue
            
        href = href_m.group(1)
        decoded_href = urllib.parse.unquote(href)
        name = name_m.group(1) if name_m else decoded_href.rstrip('/').split('/')[-1]
        size = int(size_m.group(1)) if size_m and size_m.group(1) else 0
        
        # Filter out self-referencing entry
        href_decoded_trimmed = decoded_href.rstrip('/')
        if href_decoded_trimmed == base_href_decoded:
            print(f"  [FILTERED] Self-entry: {decoded_href}")
            continue
        
        entries.append({
            'href': href,
            'decoded_href': decoded_href,
            'name': name,
            'is_folder': is_folder,
            'size': size
        })
    
    return entries

def download_file(url, save_path):
    """Download a file using curl"""
    result = subprocess.run(
        ["curl.exe", "-s", "-o", save_path, "-u", AUTH,
         "--insecure", url],
        capture_output=True, timeout=60)
    
    if result.returncode != 0:
        print(f"  ERROR: Download failed with code {result.returncode}")
        return False
    
    if os.path.exists(save_path):
        size = os.path.getsize(save_path)
        print(f"  Downloaded: {save_path} ({size} bytes)")
        return size > 0
    return False

print("=" * 80)
print("WebDAV Flow Verification for 戏曲 Module")
print("=" * 80)

# Step 1: Verify connection
print("\n[Step 1] Verify WebDAV connection")
result = subprocess.run(
    ["curl.exe", "-s", "-X", "PROPFIND", BASE_URL + "/webdav/", "-u", AUTH,
     "-H", "Depth: 0", "-H", "Content-Type: application/xml; charset=utf-8",
     "-d", BODY, "--insecure"],
    capture_output=True, timeout=15)
print(f"  curl returncode: {result.returncode}")
print(f"  stdout length: {len(result.stdout)}")
print(f"  stdout first 200: {result.stdout[:200]}")
if result.returncode == 0 and b'multistatus' in result.stdout[:500]:
    print("  [OK] Connection successful")
else:
    print("  [FAIL] Connection failed")
    exit(1)

# Step 2: List root categories
print("\n[Step 2] List root categories (/webdav/)")
categories = propfind("/webdav/")
print(f"  Found {len(categories)} categories:")
for cat in categories:
    print(f"    {'[DIR]' if cat['is_folder'] else '[FILE]'} {cat['name']} ({cat['decoded_href']})")

if not categories:
    print("  ERROR: No categories found")
    exit(1)

# Step 3: Navigate into first category with subfolders
print("\n[Step 3] Navigate into first category")
first_cat = None
for cat in categories:
    if cat['is_folder']:
        first_cat = cat
        break

if not first_cat:
    print("  ERROR: No folder category found")
    exit(1)

cat_path = first_cat['decoded_href']
print(f"  Selected: {first_cat['name']} ({cat_path})")
print(f"\n  Listing contents of {cat_path}:")
cat_contents = propfind(cat_path)
print(f"  Found {len(cat_contents)} entries:")
for entry in cat_contents:
    print(f"    {'[DIR]' if entry['is_folder'] else '[FILE]'} {entry['name']} ({entry['decoded_href']}) size={entry['size']}")

# Step 4: Find first opera folder (subfolder in category)
print("\n[Step 4] Navigate into first opera folder")
first_opera = None
for entry in cat_contents:
    if entry['is_folder']:
        first_opera = entry
        break

if first_opera:
    opera_path = first_opera['decoded_href']
    print(f"  Selected: {first_opera['name']} ({opera_path})")
    print(f"\n  Listing audio files in {opera_path}:")
    audio_files = propfind(opera_path)
    print(f"  Found {len(audio_files)} entries:")
    for af in audio_files:
        print(f"    {'[DIR]' if af['is_folder'] else '[FILE]'} {af['name']} size={af['size']}")
else:
    # Category has no subfolders, use files directly
    print("  No opera subfolder found, using category files directly")
    audio_files = [e for e in cat_contents if not e['is_folder']]
    print(f"  Found {len(audio_files)} audio files")

# Step 5: Test download
print("\n[Step 5] Test download")
first_audio = None
for af in audio_files:
    if not af['is_folder'] and af['size'] > 0:
        first_audio = af
        break

if first_audio:
    download_url = BASE_URL + first_audio['href']
    save_path = f"test_download_{first_audio['name']}"
    print(f"  Downloading: {first_audio['name']}")
    print(f"  URL: {download_url}")
    print(f"  Save to: {save_path}")
    
    if download_file(download_url, save_path):
        print(f"  [OK] Download successful")
        # Clean up
        try:
            os.remove(save_path)
            print(f"  Cleaned up: {save_path}")
        except:
            pass
    else:
        print(f"  [FAIL] Download failed")
else:
    print("  No audio file available for download test")

print("\n" + "=" * 80)
print("Verification complete")
print("=" * 80)
