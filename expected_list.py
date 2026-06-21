"""Generate expected file lists for verification"""
import subprocess
import re
import json
import urllib.parse

AUTH = "13826527554:gw7ym269"
BASE = "https://webdav-1855080734.pd1.123pan.cn"
HEADERS = ["-H", "Depth: 1", "-H", "Content-Type: application/xml; charset=utf-8"]
BODY = '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getcontenttype/></D:prop></D:propfind>'

def propfind(path):
    """Perform PROPFIND and return list of (href, name, is_folder, size)"""
    url = BASE + path
    result = subprocess.run(
        ["curl.exe", "-s", "-X", "PROPFIND", url, "-u", AUTH,
         "-H", "Depth: 1", "-H", "Content-Type: application/xml; charset=utf-8",
         "-d", BODY, "--insecure"],
        capture_output=True, timeout=15)
    xml = result.stdout.decode('utf-8')
    
    entries = []
    for match in re.finditer(r'<D:response>(.*?)</D:response>', xml, re.DOTALL):
        block = match.group(1)
        href_m = re.search(r'<D:href>([^<]+)</D:href>', block)
        name_m = re.search(r'<D:displayname>([^<]*)</D:displayname>', block)
        size_m = re.search(r'<D:getcontentlength>([^<]*)</D:getcontentlength>', block)
        is_folder = '<D:collection' in block
        
        href = href_m.group(1) if href_m else ''
        name = name_m.group(1) if name_m else ''
        size = int(size_m.group(1)) if size_m and size_m.group(1) else 0
        
        if href and href.strip('/') != path.strip('/'):
            decoded_href = urllib.parse.unquote(href)
            entries.append({
                'href': decoded_href,
                'name': name,
                'is_folder': is_folder,
                'size': size
            })
    return entries

# Get categories
print("=== Categories ===")
cats = propfind("/webdav/")
for c in cats:
    print(f"  {'[DIR]' if c['is_folder'] else '[FILE]'} {c['name']} size={c['size']}")

# Get audio files from one category
print("\n=== Audio files in first category with files ===")
for cat in cats:
    if cat['is_folder']:
        files = propfind(cat['href'])
        if files:
            print(f"Category: {cat['name']}")
            for f in files:
                print(f"  {'[DIR]' if f['is_folder'] else '[FILE]'} {f['name']} size={f['size']}")
            break

# Save full expected output
output = {
    'categories': cats,
    'first_category_with_files': None
}
for cat in cats:
    if cat['is_folder']:
        files = propfind(cat['href'])
        if files:
            output['first_category_with_files'] = {
                'category': cat,
                'files': files
            }
            break

with open('expected_webdav.json', 'w', encoding='utf-8') as f:
    json.dump(output, f, ensure_ascii=False, indent=2)
print("\nSaved to expected_webdav.json")
