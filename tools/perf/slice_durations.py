import re,sys,collections
f=sys.argv[1]; names=sys.argv[2:] or ['VAP.thumb.disk','VAP.thumb.extract','VAP.thumb.gen','VAP.thumb.mem']
stack=collections.defaultdict(list); dur=collections.defaultdict(list)
pat=re.compile(r'^\s*(\S.*?)-(\d+)\s+\(\s*\d+\)\s+\[\d+\]\s+\S+\s+([\d.]+): tracing_mark_write: ([BE])\|?(\d+)?\|?(.*)$')
for line in open(f,errors='ignore'):
    if 'tracing_mark_write' not in line: continue
    m=pat.match(line)
    if not m: continue
    tid=m.group(2); t=float(m.group(3)); k=m.group(4)
    if k=='B': stack[tid].append((m.group(6).strip(),t))
    else:
        if stack[tid]:
            n,t0=stack[tid].pop()
            if n in names: dur[n].append((t-t0)*1000)
for n in names:
    d=sorted(dur[n])
    if d: print(f"{n}: n={len(d)} mean={sum(d)/len(d):.2f}ms p50={d[len(d)//2]:.2f} p90={d[int(len(d)*.9)]:.2f} max={d[-1]:.2f} total={sum(d):.0f}ms")
    else: print(n,'none')
