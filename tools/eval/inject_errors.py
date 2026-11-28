# Recall of the validator: inject a known error (number, negation, tense, time, direction, ...) into the
# phrasebook reference translations that pass, and count how many the validator misses. Run from the repo root.
import sys,re,random,collections
sys.path.insert(0,'tools/eval'); import metrics as M
random.seed(1)
rows=[l.rstrip('\n').split('\t') for l in open('dictionaries/phrases.tsv') if not l.startswith('#')]
rows=[r for r in rows if len(r)>1]
NEDIG='०१२३४५६७८९'
def mut_ne(ne):
    out=[]
    m=re.search(r'[0-9०-९]+',ne)
    if m:
        d=m.group(); n=str(int(d.translate(str.maketrans(NEDIG,'0123456789')))+1)
        out.append(('num',ne.replace(d,n,1)))
    for a,b,k in [('छैन','छ','neg'),('हुँदैन','हुन्छ','neg'),('नगर्नुहोस्','गर्नुहोस्','neg'),('होइन','हो','neg'),
                  ('भयो','हुन्छ','past'),('गरें','गर्छु','past'),('थियो','छ','past'),('गर्नुहोस्','गर्छु','req'),
                  ('हिजो','भोलि','time'),('भोलि','हिजो','time'),('दायाँ','बायाँ','dir'),('बायाँ','दायाँ','dir'),
                  ('माथि','तल','dir'),('बिहान','बेलुका','time'),('म ','तपाईं ','person'),('मैले','तपाईंले','person'),
                  ('धेरै','अलिकति','qty'),('पहिले','पछि','order'),('पछि','पहिले','order'),('तातो','चिसो','temp'),('चिसो','तातो','temp')]:
        if a in ne: out.append((k,ne.replace(a,b,1)))
    return out
def mut_ja(ja):
    out=[]
    m=re.search(r'[0-9０-９]+',ja)
    if m: out.append(('num',ja.replace(m.group(),str(int(m.group().translate(str.maketrans('０１２３４５６７８９','0123456789')))+1),1)))
    for a,b,k in [('ません','ます','neg'),('ないで','て','neg'),('ない','る','neg'),('ました','ます','past'),('昨日','明日','time'),('明日','昨日','time'),
                  ('右','左','dir'),('左','右','dir'),('上','下','dir'),('朝','夜','time'),('前','後','order'),('後','前','order'),
                  ('熱い','冷たい','temp'),('冷たい','熱い','temp'),('私','あなた','person'),('ください','ます','req'),('多','少','qty')]:
        if a in ja: out.append((k,ja.replace(a,b,1)))
    return out
tot=collections.Counter(); miss=collections.Counter(); ex=collections.defaultdict(list)
for r in rows:
    ja,ne=r[0],r[1]
    if M.hard_count(M.validate(ja,ne,'ja-ne')): pass
    else:
        for k,h in mut_ne(ne):
            tot['ja-ne',k]+=1
            if not M.validate(ja,h,'ja-ne'): miss['ja-ne',k]+=1; ex['ja-ne',k].append((ja,h))
    if not M.hard_count(M.validate(ne,ja,'ne-ja')):
        for k,h in mut_ja(ja):
            tot['ne-ja',k]+=1
            if not M.validate(ne,h,'ne-ja'): miss['ne-ja',k]+=1; ex['ne-ja',k].append((ne,h))
for key in sorted(tot): print(key, tot[key], 'missed', miss[key], '%.0f%%'%(100*miss[key]/tot[key]))
import json; json.dump({' '.join(k):v[:6] for k,v in ex.items()},open('/tmp/inject_errors_examples.json','w'),ensure_ascii=False,indent=0)
