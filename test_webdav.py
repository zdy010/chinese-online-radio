"""Test WebDAV using curl output to verify XML parsing"""
import subprocess
import xml.etree.ElementTree as ET

# Test 2: Depth:1 to get children
print("=== PROPFIND Depth:1 on root (list children) ===")
result = subprocess.run([
    "curl.exe", "-s", "-X", "PROPFIND",
    "https://webdav-1855080734.pd1.123pan.cn/webdav",
    "-u", "13826527554:gw7ym269",
    "-H", "Depth: 1",
    "-H", "Content-Type: application/xml; charset=utf-8",
    "-d", '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getcontenttype/></D:prop></D:propfind>',
    "--insecure"
], capture_output=True, timeout=10)

# Force UTF-8 decoding
xml_text = result.stdout.decode('utf-8')
print(f"Response length: {len(xml_text)} chars")

# Save raw to file for inspection
with open('webdav_response.xml', 'w', encoding='utf-8') as f:
    f.write(xml_text)
print("Saved to webdav_response.xml")

# Parse XML
print("\n=== Parsing XML ===")
root = ET.fromstring(xml_text)
ns = {'D': 'DAV:'}

entries = []
for resp in root.findall('D:response', ns):
    href_el = resp.find('D:href', ns)
    if href_el is None or not href_el.text:
        continue
    href = href_el.text
    propstat = resp.find('D:propstat', ns)
    if propstat is None:
        continue
    prop = propstat.find('D:prop', ns)
    if prop is None:
        continue
    name_el = prop.find('D:displayname', ns)
    name = name_el.text if name_el is not None and name_el.text else href.strip('/').split('/')[-1]
    is_dir = prop.find('D:resourcetype/D:collection', ns) is not None
    size_el = prop.find('D:getcontentlength', ns)
    size = int(size_el.text) if size_el is not None and size_el.text else 0
    entries.append({'href': href, 'name': name, 'is_dir': is_dir, 'size': size})
    tag = "[DIR]" if is_dir else "[FILE]"
    print(f"  {tag} {name} href={href} size={size}")

print(f"\nTotal entries: {len(entries)}")
filtered = [e for e in entries if e['is_dir'] and e['href'].strip('/') != '/webdav']
print(f"Filtered categories: {[e['name'] for e in filtered]}")

# Test 3: List a subdirectory
if filtered:
    cat = filtered[0]
    print(f"\n=== List '{cat['name']}' ===")
    result = subprocess.run([
        "curl.exe", "-s", "-X", "PROPFIND",
        f"https://webdav-1855080734.pd1.123pan.cn{cat['href']}",
        "-u", "13826527554:gw7ym269",
        "-H", "Depth: 1",
        "-H", "Content-Type: application/xml; charset=utf-8",
        "-d", '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getcontenttype/></D:prop></D:propfind>',
        "--insecure"
    ], capture_output=True, timeout=10)
    xml_text = result.stdout.decode('utf-8')
    print(f"Response length: {len(xml_text)} chars")
    with open('webdav_subdir.xml', 'w', encoding='utf-8') as f:
        f.write(xml_text)

    root = ET.fromstring(xml_text)
    entries = []
    for resp in root.findall('D:response', ns):
        href_el = resp.find('D:href', ns)
        if href_el is None: continue
        href = href_el.text
        prop = resp.find('D:propstat/D:prop', ns)
        if prop is None: continue
        name_el = prop.find('D:displayname', ns)
        name = name_el.text if name_el is not None and name_el.text else ""
        is_dir = prop.find('D:resourcetype/D:collection', ns) is not None
        tag = "[DIR]" if is_dir else "[FILE]"
        print(f"  {tag} {name} href={href}")
        entries.append({'href': href, 'name': name, 'is_dir': is_dir})
    print(f"\nEntries: {len(entries)}")
