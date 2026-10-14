#!/usr/bin/env python3
"""Run on the CapRover host: export the existing index without printing its key."""
import hashlib
import json
import os
import re
import subprocess
import sys
import tarfile
import urllib.parse
import urllib.request
from pathlib import Path


def main():
    directory = Path(sys.argv[1])
    directory.mkdir(parents=True, mode=0o700, exist_ok=False)
    service = json.loads(subprocess.check_output(
        ['docker', 'service', 'inspect', 'srv-captain--typesense']))[0]
    container = service['Spec']['TaskTemplate']['ContainerSpec']
    environment = dict(item.split('=', 1) for item in container.get('Env', []))
    key = environment['TYPESENSE_API_KEY']

    def get(path):
        request = urllib.request.Request('http://127.0.0.1:8108' + path,
                                        headers={'X-TYPESENSE-API-KEY': key})
        return urllib.request.urlopen(request, timeout=60)

    def value(path):
        with get(path) as response:
            return json.load(response)

    collections = value('/collections')
    metadata = {'image': container['Image'], 'collections': [], 'aliases': value('/aliases')['aliases']}
    for schema in collections:
        name = schema['name']
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', name):
            raise RuntimeError('Unexpected collection name')
        path = '/collections/' + urllib.parse.quote(name, safe='')
        target = directory / (name + '.jsonl')
        hashes = []
        with get(path + '/documents/export') as response, target.open('wb') as output:
            for line in response:
                if line.strip():
                    document = json.loads(line)
                    canonical = json.dumps(document, sort_keys=True, ensure_ascii=False, separators=(',', ':'))
                    hashes.append(hashlib.sha256(canonical.encode()).hexdigest())
                    output.write(line if line.endswith(b'\n') else line + b'\n')
        after = value(path)
        if schema['num_documents'] != after['num_documents'] or len(hashes) != after['num_documents']:
            raise RuntimeError('Source changed during export; repeat before migration')
        metadata['collections'].append({
            'schema': schema,
            'count': len(hashes),
            'document_digest': hashlib.sha256(''.join(sorted(hashes)).encode()).hexdigest(),
            'overrides': value(path + '/overrides')['overrides'],
            'synonyms': value(path + '/synonyms')['synonyms']})
    (directory / 'manifest.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2))
    os.chmod(directory / 'manifest.json', 0o600)
    archive = directory.with_suffix('.tar.gz')
    with tarfile.open(archive, 'w:gz') as output:
        output.add(directory, arcname=directory.name)
    os.chmod(archive, 0o600)
    print(json.dumps({'archive': str(archive), 'image': metadata['image'],
                      'collections': [{'name': x['schema']['name'], 'count': x['count'],
                                       'digest': x['document_digest']} for x in metadata['collections']],
                      'aliases': len(metadata['aliases'])}))


if __name__ == '__main__':
    main()
