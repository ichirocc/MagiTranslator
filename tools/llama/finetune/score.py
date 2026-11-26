"""score.py refs.tsv name=cli_output.tsv ...   refs: lang<TAB>source<TAB>reference (dev.tsv) or the phrases file"""
import sys, collections
def chrf(h, r, N=4, beta=2.0):
    strip = lambda s: ''.join(c for c in s if not c.isspace() and c not in '。、！？!?,.।')
    h, r = strip(h), strip(r)
    if not h or not r: return 0.0
    P = R = 0; o = 0
    for n in range(1, N + 1):
        hc = collections.Counter(h[i:i+n] for i in range(len(h)-n+1)); rc = collections.Counter(r[i:i+n] for i in range(len(r)-n+1))
        ht, rt = sum(hc.values()), sum(rc.values())
        if not ht or not rt: continue
        m = sum(min(v, rc[k]) for k, v in hc.items()); P += m/ht; R += m/rt; o += 1
    if not o: return 0.0
    p, q = P/o, R/o
    return 0 if p + q == 0 else (1+beta**2)*p*q/(beta**2*p+q)
refs = {}
for l in open(sys.argv[1], encoding='utf-8'):
    if l.startswith('#') or '\t' not in l: continue
    c = l.rstrip('\n').split('\t')
    if c[0] in ('ja', 'ne') and len(c) >= 3: refs[(c[0], c[1])] = c[2]
    else:
        refs.setdefault(('ja', c[0]), c[1]); refs.setdefault(('ne', c[1]), c[0])
for arg in sys.argv[2:]:
    name, path = arg.split('=')
    s = collections.defaultdict(list)
    for l in open(path, encoding='utf-8'):
        c = l.rstrip('\n').split('\t')
        if len(c) < 3: continue
        s[c[0]].append(chrf(c[2], refs[(c[0], c[1])]))
    print(f'{name:14s} ' + '  '.join(f'{d} chrF {100*sum(v)/len(v):5.1f} off {sum(x < 0.1 for x in v):2d}/{len(v)}' for d, v in (('ja→ne', s['ja']), ('ne→ja', s['ne']))))
