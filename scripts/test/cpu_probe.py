#!/usr/bin/env python3
"""Measure STOP/CONT only on four disposable owned burners; save JSON evidence."""
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import time

module = Path(__file__).resolve().parents[1] / 'dev/cpu_controls.py'
spec = importlib.util.spec_from_file_location('cpu_controls', module)
cpu = importlib.util.module_from_spec(spec);spec.loader.exec_module(cpu)
proc=cpu.MacProcesses()
children=[subprocess.Popen([sys.executable,'-c','while True: pass']) for _ in range(4)]
controller=None
try:
    time.sleep(.2)
    records=[proc.identity(p.pid) for p in children]
    before=sum(proc.cpu(p.pid) for p in children);start=time.monotonic();time.sleep(3)
    uncapped=(sum(proc.cpu(p.pid) for p in children)-before)/(time.monotonic()-start)
    controller=cpu.Controller(proc)
    duty=.375
    before=sum(proc.cpu(p.pid) for p in children);start=time.monotonic()
    window_start=time.monotonic();window_cpu=before;warm_cpu=before;warm_start=start
    for tick_index in range(70):
        tick=time.monotonic();time.sleep(.1*duty)
        controller.pause(records);time.sleep(max(0,tick+.1-time.monotonic()));controller.resume()
        if tick_index % 10 == 9:
            now=time.monotonic();used=sum(proc.cpu(p.pid) for p in children)
            rate=(used-window_cpu)/(now-window_start)
            duty=min(1,1.5/max(rate/max(duty,.01),.001))
            window_start,window_cpu=now,used
            if tick_index==19:warm_cpu,warm_start=used,now
    capped=(sum(proc.cpu(p.pid) for p in children)-warm_cpu)/(time.monotonic()-warm_start)
    controller.close();controller=None
    result={'scope':'four disposable owned Python CPU burners;3s baseline,7s adaptivecap,2s cap warmup excluded',
            'uncappedCores':round(uncapped,3),'cappedCores':round(capped,3),
            'targetCores':1.5,'cycleSeconds':.1,'allResumed':all(proc.identity(p.pid)['status']!=4 for p in children)}
    print(json.dumps(result,indent=2))
    if capped>1.75 or not result['allResumed']:raise RuntimeError('Fixture CPU limit/recovery check failed')
finally:
    if controller:controller.close()
    for p in children:
        os.kill(p.pid,signal.SIGCONT);p.terminate();p.wait(timeout=3)
