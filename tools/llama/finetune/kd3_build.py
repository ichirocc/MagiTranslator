# KD round 3 (2026-11-28): 2B+adapter teacher translations of the kd2 ja sources (+ the app's ne->hai rule),
# filtered (no hard issue, length ratio 0.8-4.5, 3-gram Jaccard <= 0.5 to the two 80-sentence test sets),
# mixed 1:2 with the original train.jsonl. Paths are those of the working machine (see docs/eval note).
import sys,json,random,re,subprocess
sys.path.insert(0,'tools/eval');import metrics as M
D='tools/llama/finetune/data/'
T=[l.rstrip('\n').split('\t') for l in open(D+'kd2_teacher_translations.tsv',encoding='utf-8')]
T=[t for t in T if t[0]=='ja' and len(t)>2]
# है rule via the app's Java (same code as the app)
open('/home/user/ft/kd_t.tsv','w').write(''.join(f"{a}\t{b}\t{c}\n" for a,b,c in T))
out=subprocess.run(['java','-Dstdout.encoding=UTF-8','-cp','/tmp/jt:/tmp/pt','P','/home/user/ft/kd_t.tsv'],capture_output=True,text=True).stdout
T=[l.split('\t') for l in out.strip('\n').split('\n')]
ev=[l.rstrip('\n').split('\t')[1] for f in ['/home/user/ft/refs.tsv','/home/user/ft/t80_ref.tsv'] for l in open(f) if l.startswith('ja')]
g3=lambda s:{s[i:i+3] for i in range(len(s)-2)}
evg=[g3(e) for e in ev]
keep=[];why={}
for _,ja,ne in T:
    ne=ne.strip()
    r=len(ne)/max(1,len(ja))
    bad=None
    if M.hard_count(M.validate(ja,ne,'ja-ne')): bad='hard'
    elif not (0.8<=r<=4.5): bad='len'
    elif any(len(g3(ja)&e)/max(1,len(g3(ja)|e))>0.5 for e in evg): bad='near-test'
    if bad: why[bad]=why.get(bad,0)+1; continue
    keep.append((ja,ne))
print('teacher',len(T),'kept',len(keep),why)
P=lambda s,t,x:"<|plamo:tag|>dataset<|plamo:msg|>translation<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>%s<|plamo:msg|>%s<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>%s<|plamo:msg|>"%(s,x,t)
kd=[{"prompt":P('ja','ne',ja),"target":ne+"<|plamo:tag|>","dir":"jane-kd"} for ja,ne in keep]
orig=[json.loads(l) for l in open(D+'train.jsonl')]
random.seed(7); mix=random.sample(orig,2*len(kd))+kd; random.shuffle(mix)
with open('/home/user/ft/kd_train.jsonl','w') as f:
    for x in mix: f.write(json.dumps(x,ensure_ascii=False)+'\n')
print('train examples',len(mix))
