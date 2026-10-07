import numpy as np, wave
sr=44100; T=12; n=sr*T; t=np.arange(n)/sr
rng=np.random.default_rng(1)
def saw(f,ph=0): return 2*((t*f+ph)%1)-1
L=np.zeros(n); R=np.zeros(n)
# pad chord, detuned, stereo
for f in [220,277.18,329.63,440]:
    L+=0.06*(saw(f*1.003)+saw(f*0.997,0.3)); R+=0.06*(saw(f*1.002,0.5)+saw(f*0.998,0.7))
# simple one-pole lowpass on pad
def lp(x,fc):
    a=np.exp(-2*np.pi*fc/sr); y=np.zeros_like(x); s=0
    for i in range(len(x)): s=x[i]+a*(s-x[i]); y[i]=s
    return y
L=lp(L,3000); R=lp(R,3000)
beat=sr//2
for k in range(0,n-sr,beat):
    m=min(sr//4,n-k); e=np.exp(-np.arange(m)/(0.08*sr))
    kick=np.sin(2*np.pi*(50+80*np.exp(-np.arange(m)/(0.02*sr)))*np.arange(m)/sr)*e*0.5
    L[k:k+m]+=kick; R[k:k+m]+=kick
for k in range(beat//2,n-sr,beat//2):
    m=sr//20; h=rng.standard_normal(m); h=h-lp(h,7000); e=np.exp(-np.arange(m)/(0.01*sr))*0.25
    L[k:k+m]+=h*e*0.8; R[k:k+m]+=h*e
# bass
L+=0.15*np.sin(2*np.pi*55*t); R+=0.15*np.sin(2*np.pi*55*t)
# last 2 s: decaying quiet tail (stereo noise)
s0=n-2*sr; nl=rng.standard_normal((2,2*sr))*np.exp(-np.arange(2*sr)/(0.5*sr))*0.05
L[s0:]=lp(nl[0],6000); R[s0:]=lp(nl[1],6000)
x=np.stack([L,R],1); x/=np.abs(x).max()/0.7
w=wave.open('test.wav','wb'); w.setnchannels(2); w.setsampwidth(2); w.setframerate(sr)
w.writeframes((x*32767).astype('<i2').tobytes()); w.close()
