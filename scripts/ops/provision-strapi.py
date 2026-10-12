#!/usr/bin/env python3
"""Provision a per-environment Strapi service and wire server-only web credentials."""
import argparse, base64, json, re, shlex, subprocess
from pathlib import Path

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action',choices=['prepare','start','verify','wire'])
    parser.add_argument('manifest',type=Path)
    args=parser.parse_args()
    spec=json.loads(args.manifest.read_text())
    host=spec.pop('ssh_host','bux')
    if not re.fullmatch(r'[A-Za-z0-9_.@-]+',host) or host.startswith('-'): parser.error('Invalid SSH host')
    subprocess.run(['bb','site:resolve'],cwd=Path(__file__).resolve().parents[2],stdout=subprocess.DEVNULL,check=True)
    selected=json.loads(subprocess.run(['bb','site:manifest'],cwd=Path(__file__).resolve().parents[2],capture_output=True,text=True,check=True).stdout)
    spec['site']={key:selected[key] for key in ('config','available-modules','pages','modules','content','tables')}
    spec['action']=args.action
    encoded=base64.b64encode(json.dumps(spec).encode()).decode()
    php="<?php $input=json_decode(base64_decode('"+encoded+"'),true); ?>\n"+(Path(__file__).parent / 'coolify/provision-strapi.php').read_text()
    subprocess.run(['ssh','-o','BatchMode=yes','-o','ConnectTimeout=10',host,shlex.join(['docker','exec','-i','coolify','php'])],input=php,text=True,check=True,timeout=120)
if __name__=='__main__': main()
