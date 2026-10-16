#!/usr/bin/env python3
"""Read allowlisted local Codex names and working directories for explicit IDs."""
import json
import os
from pathlib import Path
import re
import sqlite3
import sys

UUID = re.compile(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}')


def records(path):
    try:
        with path.open() as stream:
            for line in stream:
                try:
                    yield json.loads(line)
                except ValueError:
                    continue
    except OSError:
        return


def literal_arguments(source):
    """Read an object with literal keys and JSON values; reject expressions."""
    decoder = json.JSONDecoder()
    remaining = source.lstrip()
    if not remaining.startswith('{'):
        raise ValueError('Expected literal object')
    remaining = remaining[1:].lstrip()
    result = {}
    while not remaining.startswith('}'):
        if remaining.startswith('"'):
            key, end = decoder.raw_decode(remaining)
        else:
            match = re.match(r'[a-zA-Z_$][a-zA-Z0-9_$]*', remaining)
            if not match:
                raise ValueError('Expected literal key')
            key, end = match[0], match.end()
        remaining = remaining[end:].lstrip()
        if not remaining.startswith(':'):
            raise ValueError('Expected colon')
        remaining = remaining[1:].lstrip()
        value, end = decoder.raw_decode(remaining)
        result[key] = value
        remaining = remaining[end:].lstrip()
        if remaining.startswith(','):
            remaining = remaining[1:].lstrip()
        elif not remaining.startswith('}'):
            raise ValueError('Nonliteral property value')
    if not remaining[1:].lstrip().startswith(')'):
        raise ValueError('Nonliteral tool arguments')
    return result


def command_directories(source):
    """Read literal exec_command arguments, skipping JS strings/comments.

    Dynamic expressions and non-JSON values are intentionally omitted.
    Never evaluate session code to recover metadata.
    """
    index = 0
    while index < len(source):
        char = source[index]
        if source.startswith('//', index):
            newline = source.find('\n', index)
            index = len(source) if newline < 0 else newline + 1
        elif source.startswith('/*', index):
            end = source.find('*/', index + 2)
            index = len(source) if end < 0 else end + 2
        elif char in ["'", '"', '`', '/']:
            # Slash literals are skipped conservatively too; division can cause
            # omission, never execution or publication of an ambiguous fragment.
            quote = char
            index += 1
            while index < len(source):
                if source[index] == '\\':
                    index += 2
                elif source[index] == quote:
                    index += 1
                    break
                else:
                    index += 1
        elif source.startswith('tools.exec_command(', index) and (index == 0 or not (source[index - 1].isalnum() or source[index - 1] in '_$.')):
            start = index + len('tools.exec_command(')
            index = start
            try:
                yield literal_arguments(source[start:]).get('workdir')
            except ValueError:
                pass
        else:
            index += 1


def metadata(home, ids):
    result = {ident: {'titles': [], 'worktrees': []} for ident in ids}

    def add(ident, key, value):
        if isinstance(value, str) and value.strip():
            if key == 'worktrees':
                if not Path(value).is_absolute() or any(ord(c) < 32 for c in value):
                    return
                match = re.match(r'(.*/\.codex/worktrees/[^/]+/[^/]+)(?:/.*)?$', value)
                value = match[1] if match else value
            if value not in result[ident][key]:
                result[ident][key].append(value)

    for item in records(home / 'session_index.jsonl'):
        ident = item.get('id')
        if ident in result:
            add(ident, 'titles', item.get('thread_name'))
            result[ident]['title'] = item.get('thread_name')

    databases = sorted(home.glob('state_*.sqlite'), key=lambda p: int(p.stem.split('_')[-1]), reverse=True)
    for database in databases:
        try:
            with sqlite3.connect(database.as_uri() + '?mode=ro', uri=True, timeout=1) as connection:
                connection.row_factory = sqlite3.Row
                columns = {r[1] for r in connection.execute('pragma table_info(threads)')}
                selected = [k for k in ['id', 'name', 'title', 'first_user_message', 'preview', 'cwd', 'rollout_path'] if k in columns]
                placeholders = ','.join('?' for _ in ids)
                rows = connection.execute(f"select {','.join(selected)} from threads where id in ({placeholders})", ids).fetchall()
            for row in rows:
                item = dict(row)
                ident = item['id']
                # Legacy title/preview fields often contain the complete prompt.
                prompts = [item.get('first_user_message'), item.get('preview')]
                if not item.get('first_user_message'):
                    prompts.append(item.get('title'))
                result[ident]['titles'] = [v for v in result[ident]['titles'] if v not in prompts]
                if result[ident].get('title') in prompts:
                    result[ident].pop('title', None)
                title = item.get('title')
                if title and title not in prompts:
                    add(ident, 'titles', title)
                name = item.get('name')
                add(ident, 'titles', name)
                if name:
                    result[ident]['title'] = name
                elif title in result[ident]['titles'] and not result[ident].get('title'):
                    result[ident]['title'] = title
                add(ident, 'worktrees', item.get('cwd'))
                rollout = Path(item.get('rollout_path') or '')
                # Never follow a path outside Codex's session archives.
                if not any(rollout.resolve().is_relative_to((home / folder).resolve()) for folder in ['sessions', 'archived_sessions']):
                    continue
                for event in records(rollout):
                    payload = event.get('payload', {})
                    if not isinstance(payload, dict):
                        continue
                    if event.get('type') in ['session_meta', 'turn_context']:
                        add(ident, 'worktrees', payload.get('cwd'))
                    if event.get('type') == 'response_item' and payload.get('type') in ['function_call', 'custom_tool_call']:
                        arguments = payload.get('arguments', payload.get('input', ''))
                        if not isinstance(arguments, str):
                            continue
                        try:
                            arguments_map = json.loads(arguments)
                        except ValueError:
                            arguments_map = None
                        if isinstance(arguments_map, dict):
                            add(ident, 'worktrees', arguments_map.get('workdir'))
                        elif payload.get('name', '').split('.')[-1] == 'exec':
                            for directory in command_directories(arguments):
                                add(ident, 'worktrees', directory)
            break
        except (sqlite3.Error, OSError, ValueError):
            continue
    return result


if __name__ == '__main__':
    ids = sys.argv[1:]
    if not ids or not all(UUID.fullmatch(ident) for ident in ids):
        raise SystemExit('Expected explicit Codex thread UUIDs')
    home = Path(os.environ.get('CODEX_HOME', Path.home() / '.codex')).resolve()
    result = metadata(home, ids)
    current = os.environ.get('CODEX_THREAD_ID') or os.environ.get('CODEX_SESSION_ID')
    if current in result and os.getcwd() not in result[current]['worktrees']:
        result[current]['worktrees'].append(os.getcwd())
    print(json.dumps(result))
