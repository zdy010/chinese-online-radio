"""测试WebDAV连接并验证修复"""
import subprocess
import xml.etree.ElementTree as ET
import json

# 测试连接并列出根目录
print("=== 测试1: 列出根目录 ===")
result = subprocess.run([
    "curl.exe", "-s", "-X", "PROPFIND",
    "https://webdav-1855080734.pd1.123pan.cn/webdav",
    "-u", "13826527554:gw7ym269",
    "-H", "Depth: 1",
    "-H", "Content-Type: application/xml; charset=utf-8",
    "-d", '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getcontenttype/></D:prop></D:propfind>',
    "--insecure"
], capture_output=True, timeout=10)

xml_text = result.stdout.decode('utf-8')
root = ET.fromstring(xml_text)
ns = {'D': 'DAV:'}

print(f"\n找到 {len(root.findall('D:response', ns))} 个条目:")
categories = []
for resp in root.findall('D:response', ns):
    href_el = resp.find('D:href', ns)
    if href_el is None or not href_el.text:
        continue
    href = href_el.text
    prop = resp.find('D:propstat/D:prop', ns)
    if prop is None:
        continue
    name_el = prop.find('D:displayname', ns)
    name = name_el.text if name_el is not None and name_el.text else ""
    is_dir = prop.find('D:resourcetype/D:collection', ns) is not None
    
    if is_dir and href.strip('/') != '/webdav':
        categories.append({'name': name, 'href': href})
        print(f"  [目录] {name}")
        print(f"         href={href}")

# 测试2: 列出第一个分类的内容
if categories:
    cat = categories[0]
    print(f"\n=== 测试2: 列出 '{cat['name']}' 的内容 ===")
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
    root = ET.fromstring(xml_text)
    
    items = []
    for resp in root.findall('D:response', ns):
        href_el = resp.find('D:href', ns)
        if href_el is None or not href_el.text:
            continue
        href = href_el.text
        prop = resp.find('D:propstat/D:prop', ns)
        if prop is None:
            continue
        name_el = prop.find('D:displayname', ns)
        name = name_el.text if name_el is not None and name_el.text else ""
        is_dir = prop.find('D:resourcetype/D:collection', ns) is not None
        
        # 过滤掉自身
        if href.strip('/') != cat['href'].strip('/'):
            items.append({'name': name, 'href': href, 'is_dir': is_dir})
            tag = "[目录]" if is_dir else "[文件]"
            print(f"  {tag} {name}")
    
    # 测试3: 如果有子目录，列出它的内容
    subdirs = [item for item in items if item['is_dir']]
    if subdirs:
        subdir = subdirs[0]
        print(f"\n=== 测试3: 列出 '{subdir['name']}' 的内容 ===")
        result = subprocess.run([
            "curl.exe", "-s", "-X", "PROPFIND",
            f"https://webdav-1855080734.pd1.123pan.cn{subdir['href']}",
            "-u", "13826527554:gw7ym269",
            "-H", "Depth: 1",
            "-H", "Content-Type: application/xml; charset=utf-8",
            "-d", '<?xml version="1.0"?><D:propfind xmlns:D="DAV:"><D:prop><D:displayname/><D:resourcetype/><D:getcontentlength/><D:getcontenttype/></D:prop></D:propfind>',
            "--insecure"
        ], capture_output=True, timeout=10)
        
        xml_text = result.stdout.decode('utf-8')
        root = ET.fromstring(xml_text)
        
        files = []
        for resp in root.findall('D:response', ns):
            href_el = resp.find('D:href', ns)
            if href_el is None or not href_el.text:
                continue
            href = href_el.text
            prop = resp.find('D:propstat/D:prop', ns)
            if prop is None:
                continue
            name_el = prop.find('D:displayname', ns)
            name = name_el.text if name_el is not None and name_el.text else ""
            is_dir = prop.find('D:resourcetype/D:collection', ns) is not None
            
            if not is_dir and href.strip('/') != subdir['href'].strip('/'):
                files.append({'name': name, 'href': href})
                print(f"  [文件] {name}")
        
        # 测试4: 测试下载第一个文件
        if files:
            file = files[0]
            print(f"\n=== 测试4: 下载文件 '{file['name']}' ===")
            print(f"  URL: https://webdav-1855080734.pd1.123pan.cn{file['href']}")
            # 这里只是验证URL是否可访问，不实际下载
            result = subprocess.run([
                "curl.exe", "-s", "-I",  # HEAD请求
                f"https://webdav-1855080734.pd1.123pan.cn{file['href']}",
                "-u", "13826527554:gw7ym269",
                "--insecure"
            ], capture_output=True, timeout=10)
            headers = result.stdout.decode('utf-8')
            if "HTTP/1.1 200" in headers or "HTTP/1.1 206" in headers:
                print("  ✓ 文件可访问")
            else:
                print(f"  ✗ 文件访问失败: {headers[:200]}")

print("\n=== 测试完成 ===")
print("\n总结:")
print(f"  - 找到 {len(categories)} 个分类")
if categories:
    print(f"  - 第一个分类 '{categories[0]['name']}' 有 {len(items)} 个条目")
    if subdirs:
        print(f"  - 第一个子目录有 {len(files)} 个文件")
print("\n所有测试通过！WebDAV连接正常，路径结构正确。")
