#!/usr/bin/env python3
"""How the sound is spread over frequency, renders against a reference.

    spectrum.py <renders folder> <set> [from to]

The decays, the stiffness and the brightness can all match while two pianos
sound like different instruments: what tells them apart is where the energy
is, partials or not. For each note, the level in half-octave bands over the
first 300 ms (or [from] to [to] seconds), against the note's own total; then
the median of reference minus render, by register (B 21-45, M 46-72, T 73-108)
and velocity. This is what found the knock under every note, the board's
thump that lives below a treble note's fundamental, and the felt hardening
too fast with velocity. The renders are hammer_render's at velocities
30,60,90,120 against the reference's 30,60,92,124.
"""
import sys, json, numpy as np, os, warnings
from multiprocessing import Pool
import measure
import tables
EDGES=2**np.arange(np.log2(28),np.log2(14000),1/2)
def bands(job):
    warnings.simplefilter('ignore')
    path,key,vel,t0,t1=job
    x,sr=measure.load(path,48000); st=measure.onset(x,sr)
    seg=x[st+int(t0*sr):st+int(t1*sr)]
    S=np.abs(np.fft.rfft(seg*np.hanning(len(seg))))**2; f=np.fft.rfftfreq(len(seg),1/sr)
    e=np.array([S[(f>=a)&(f<b)].sum() for a,b in zip(EDGES[:-1],EDGES[1:])])
    db=10*np.log10(e+1e-20); return key,vel,list(db-10*np.log10(e.sum()))
def run(ham_dir, ref_set, t0=0.0, t1=0.3, ref_vels=(30,60,92,124)):
    ref=[r for r in json.load(open(os.path.join(tables.folder_of(ref_set), 'survey.json'))) if 'error' not in r and r['velocity'] in ref_vels]
    rj=[(r['file'],r['key'],r['velocity'],t0,t1) for r in ref]
    hj=[(f'{ham_dir}/{f}',int(f[1:4]),int(f[6:9]),t0,t1) for f in os.listdir(ham_dir) if f.endswith('.wav')]
    with Pool(12) as p: R=p.map(bands,rj); H=p.map(bands,hj)
    vm={30:30,60:60,92:90,124:120}
    Hd={(k,v):b for k,v,b in H}
    groups=[(21,45),(46,72),(73,108)]
    out={}
    for gi,(a,b) in enumerate(groups):
        for v in ref_vels:
            diffs=[np.array(rb)-np.array(Hd[(k,vm[v])]) for k,vv,rb in R if vv==v and a<=k<=b and (k,vm[v]) in Hd]
            if diffs: out[(gi,v)]=np.median(diffs,axis=0)
    return out
if __name__=='__main__':
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    out=run(sys.argv[1], sys.argv[2], float(sys.argv[3]) if len(sys.argv)>3 else 0.0, float(sys.argv[4]) if len(sys.argv)>4 else 0.3)
    c=np.sqrt(EDGES[:-1]*EDGES[1:])
    print('ref - hammer, dB, band level against the note total')
    print('   Hz '+' '.join('%5s'%f'{["B","M","T"][g]}{v}' for g,v in sorted(out)))
    for i,hz in enumerate(c):
        print('%5.0f '%hz+' '.join('%+5.0f'%out[k][i] for k in sorted(out)))
    tot=np.sqrt(np.mean([np.mean(np.square(v[(c>50)&(c<10000)])) for v in out.values()]))
    print('rms %.1f dB'%tot)
