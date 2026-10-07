#!/usr/bin/env python3
"""Measures what a data-reduced copy of the test clip did to it, for tuning Magneto.

  tools/magneto_measure.py tools/magneto_reference/test.wav copy.wav [more.wav ...]

Each copy is lined up with the clip (it may be late, and some decoders flip
its polarity), then measured in bands: how much level each band kept, the
noise it carries against the signal, how much that flickers from frame to
frame, how wide the stereo is, the same for the quiet tail, and the noise
just before the hats (pre-echo). Copies must be 44.1 kHz WAV; decode other
files with ffmpeg first (ffmpeg -i copy.oma copy.wav).
"""
import numpy as np, wave, sys
sr=44100
def read(p):
    w=wave.open(p); n=w.getnframes(); c=w.getnchannels(); sw=w.getsampwidth(); d=w.readframes(n)
    x=np.frombuffer(d,'<i2' if sw==2 else '<f4').astype(float)
    if sw==2: x/=32768
    return x.reshape(-1,c)
def align(ref,dec):
    a=ref[:,0]; b=dec[:,0]; n=1<<21
    c=np.fft.irfft(np.fft.rfft(b,n)*np.conj(np.fft.rfft(a,n)),n)[:20000]
    best=int(np.argmax(np.abs(c))); sign=np.sign(c[best])
    d=dec[best:best+len(ref)]*sign
    if len(d)<len(ref): d=np.vstack([d,np.zeros((len(ref)-len(d),2))])
    return d,best*int(sign)
EDGES=[100,200,400,800,1600,3150,5000,8000,10000,12500,14000,16000,18000,20000]
def stft(x,N=1024,H=512):
    w=np.hanning(N); fr=[np.fft.rfft(x[i:i+N]*w) for i in range(0,len(x)-N,H)]
    return np.array(fr)
def bands(S):
    f=np.fft.rfftfreq(1024,1/sr); out=[]
    for lo,hi in zip(EDGES[:-1],EDGES[1:]):
        m=(f>=lo)&(f<hi); out.append((np.abs(S[:,m])**2).sum(1))
    return np.array(out)  # bands x frames
def measure(ref,dec):
    music=slice(0,sr*10); tail=slice(sr*10,sr*12)
    r={}
    for name,sl in [('music',music),('tail',tail)]:
        R=bands(stft(ref[sl,0])); D=bands(stft(dec[sl,0])); E=bands(stft(dec[sl,0]-ref[sl,0]))
        act=R>1e-7
        r[name+'_gain']=[10*np.log10((D[b][act[b]].sum()+1e-12)/(R[b][act[b]].sum()+1e-12)) for b in range(len(R))]
        r[name+'_nsr']=[10*np.log10((E[b][act[b]].sum()+1e-12)/(R[b][act[b]].sum()+1e-12)) for b in range(len(R))]
        fl=[]
        for b in range(len(R)):
            m=act[b]&(D[b]>0)
            fl.append(np.std(10*np.log10((D[b][m]+1e-12)/(R[b][m]+1e-12))) if m.sum()>5 else 0)
        r[name+'_flicker']=fl
    M=(ref[music,0]+ref[music,1])/2; S=(ref[music,0]-ref[music,1])/2
    Md=(dec[music,0]+dec[music,1])/2; Sd=(dec[music,0]-dec[music,1])/2
    Rm,Rs,Dm,Ds=[bands(stft(v)) for v in (M,S,Md,Sd)]
    r['width']=[10*np.log10((Ds[b].sum()/ (Dm[b].sum()+1e-12)+1e-12)/(Rs[b].sum()/(Rm[b].sum()+1e-12)+1e-12)) for b in range(len(Rm))]
    # pre-echo: error energy 3-12 ms before each hat onset vs after
    beat=sr//2; pre=[];post=[]
    for k in range(beat//2,sr*10-sr,beat//2):
        e=dec[:,0]-ref[:,0]
        pre.append((e[k-int(.012*sr):k-int(.003*sr)]**2).mean()); post.append((ref[k:k+int(.009*sr),0]**2).mean())
    r['preecho']=10*np.log10(np.mean(pre)/np.mean(post))
    r['total_nsr']=10*np.log10(((dec[music]-ref[music])**2).sum()/(ref[music]**2).sum())
    return r
def show(name,r):
    f=lambda v:' '.join('%6.1f'%x for x in v)
    print(f'== {name}  total nsr {r["total_nsr"]:.1f} dB  pre-echo {r["preecho"]:.1f} dB')
    print('  band hz   '+' '.join('%6d'%e for e in EDGES[:-1]))
    for k in ['music_gain','music_nsr','music_flicker','width','tail_gain','tail_nsr']: print('  %-9s'%k[:9],f(r[k]))
if __name__=='__main__':
    ref=read(sys.argv[1])
    for p in sys.argv[2:]:
        d,lag=align(ref,read(p)); r=measure(ref,d); show(f'{p} lag {lag}',r)
