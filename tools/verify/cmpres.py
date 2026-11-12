import sys,re
def old(f):
    d={}
    for l in open(f):
        if ': sup =' not in l or not l.startswith('<'): continue
        p,s=l.rsplit(': sup =',1)
        its=re.findall(r'\(([^)]*)\)',p)
        d['|'.join(' '.join(sorted(x.replace(',',' ').split(),key=int)) for x in its)]=int(s)
    return d
def new(f):
    d={}
    for l in open(f):
        if ': sup =' not in l: continue
        p,s=l.rsplit(': sup =',1)
        d['|'.join(' '.join(sorted(x.split(),key=int)) for x in p.strip().split('|'))]=int(s)
    return d
a=old(sys.argv[1]); b=new(sys.argv[2])
print('old',len(a),'new',len(b),'onlyOld',len(a.keys()-b.keys()),'onlyNew',len(b.keys()-a.keys()),'supDiff',sum(1 for k in a.keys()&b.keys() if a[k]!=b[k]))
