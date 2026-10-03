#!/usr/bin/env python3
"""Keep one production and one staging site runtime on bux.

Install as the accompanying systemd service. Only the two listed Coolify
application UUIDs are in scope; build helpers and other services are excluded.
"""
import json
import re
import subprocess
import time

PRODUCTION = "bsok8o8csgso8c00g00k0csg"
STAGING = "o84wgo08wcs048ss8sokgkgw"


def role(name):
    for uuid, environment in ((PRODUCTION, "production"), (STAGING, "staging")):
        if re.fullmatch(re.escape(uuid) + r"(?:-\d+|-pr-\d+)?", name):
            return "disabled-preview" if environment == "production" and "-pr-" in name else environment
    return None


def obsolete(containers):
    groups = {"production": [], "staging": []}
    stopped = []
    for container in containers:
        name = container["Name"].lstrip("/")
        environment = role(name)
        if environment == "disabled-preview":
            stopped.append(container)
        elif environment in groups:
            groups[environment].append(container)
    for group in groups.values():
        # Creation order represents deployment order; restarting an old container
        # after a host reboot must not make that old release win.
        stopped.extend(sorted(group, key=lambda item: item["Created"], reverse=True)[1:])
    return stopped


def docker(*args):
    return subprocess.check_output(["docker", *args], text=True, timeout=45)


def reconcile():
    names = [name for name in docker("ps", "--format", "{{.Names}}").splitlines() if role(name)]
    if not names:
        return
    containers = json.loads(docker("inspect", *names))
    for container in obsolete(containers):
        name = container["Name"].lstrip("/")
        print("Stopping superseded site runtime:", name, flush=True)
        docker("update", "--restart=no", container["Id"])
        docker("stop", "--time", "10", container["Id"])


if __name__ == "__main__":
    while True:
        try:
            reconcile()
        except (subprocess.SubprocessError, ValueError, OSError) as error:
            print("Runtime policy retry:", type(error).__name__, flush=True)
        time.sleep(2)
