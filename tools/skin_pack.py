#!/usr/bin/env python3
"""Zips a skin folder for upload from the phone: skin_pack.py <skin folder> [out.zip]

Checks skin.json (format 1, id equal to the folder name) and packs only the files RAY accepts.
"""
import json, os, re, sys, zipfile

d = os.path.abspath(sys.argv[1])
sj = json.load(open(os.path.join(d, 'skin.json'), encoding='utf-8'))
sid = sj.get('id')
if sj.get('format') != 1 or sid != os.path.basename(d) or not re.fullmatch(r'[a-z0-9_-]{1,32}', sid or ''):
    sys.exit('skin.json: format must be 1 and id must equal the folder name (a-z0-9_-)')
if sj.get('frame') and not os.path.isfile(os.path.join(d, sj['frame'])):
    sys.exit('frame file missing: ' + sj['frame'])
ok = re.compile(r'skin\.json|frame\.(webp|png)|remote\.webp|preview\.webp|icons/[a-z]{1,16}\.png')
out = sys.argv[2] if len(sys.argv) > 2 else sid + '.zip'
n = total = 0
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk(d):
        for f in sorted(files):
            rel = os.path.relpath(os.path.join(root, f), d).replace(os.sep, '/')
            if ok.fullmatch(rel):
                z.write(os.path.join(root, f), rel); n += 1; total += os.path.getsize(os.path.join(root, f))
            else:
                print('skipped', rel)
if total > 20 << 20:
    sys.exit('over 20 MB: the phone upload will be refused')
print(f'{out}: {n} files, {total // 1024} KB')
