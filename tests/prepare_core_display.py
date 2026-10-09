#!/usr/bin/env python3
"""Explicit frozen Core14 unit profiles; original files are never written."""
from pathlib import Path
import json,hashlib
import numpy as np
import pandas as pd
import argparse
ROOT=Path(__file__).resolve().parents[1]
ap=argparse.ArgumentParser(description="Read-only SHA-verified Core14 normalization; requires numpy and pandas")
ap.add_argument('--raw-dir',type=Path,required=True)
ap.add_argument('--output',type=Path,required=True)
a=ap.parse_args();R=a.raw_dir;OUT=a.output;OUT.mkdir(parents=True,exist_ok=True)
m=json.loads((ROOT/'regression/v2/manifest_v1.0.json').read_text())
rows=[]
cols=['time_ms','RPM','VSS','Gear','MAP','PA','TPedal','TPlate','ECT','INJ','AF','AFCMD','S.TRIM','Fuel Status','Clutch.Pos','K.Retard','K.Control','K.Count','IGN']
for item in m['core_files']:
 name=item['file_name'];label=name[:8]+('_OEM' if 'OEM' in name else '_002' if '_002' in name else '')
 p=R/name
 if not p.exists():p=R/(label+'.csv')
 sha=hashlib.sha256(p.read_bytes()).hexdigest();assert sha==item['sha256']
 with p.open(encoding='latin1') as f: h=[f.readline() for _ in range(5)]
 offset=next(i for i,x in enumerate(h) if x.startswith('frame,time_ms,'))
 raw=pd.read_csv(p,skiprows=offset,encoding='latin1',usecols=lambda c:c in cols).apply(pd.to_numeric,errors='coerce')
 assert len(raw)==item['raw_frames'], (label,len(raw),item['raw_frames'])
 raw=raw.sort_values('time_ms',kind='stable');t=raw.time_ms.to_numpy();grid=np.arange(t[0],t[-1]+.001,20);ix=np.searchsorted(t,grid,side='right')-1
 f=raw.iloc[ix].reset_index(drop=True).copy();f['t_ms']=grid;f['fresh']=grid-t[ix]<=500
 f['speed']=f.VSS;f['map']=f.MAP;f['ect']=f.ECT
 # Explicit per-file units: metric speed/temperature; two files use absolute psi MAP.
 factor=14.7 if label in ('20260518','20260605','20260609','20260611') else 1
 if label in ('20260603_002','20260605'):f['map']=f.MAP*6.894757
 assert f['map'].between(10,400).mean()>.99,(name,'MAP profile invalid')
 assert f.ECT.median()<120 and f.MAP.min()>=0,(name,'unit profile changed')
 assert (f.AF.median()<3)==(factor==1),(name,'AF units changed')
 f['lambda']=f.AF/factor;f['target']=f.AFCMD/factor;f['cl']=(f['Fuel Status']==2).astype(int)
 f['low']=f.fresh&f.speed.between(0,10)&f.RPM.between(600,1500)&(f.TPedal<=5)&(f['map']-f.PA<=5)&(f.INJ>.30)&(f.ect>=72)
 order=['t_ms','RPM','speed','Gear','map','TPlate','INJ','cl','target','lambda','ect','IGN','S.TRIM','TPedal','Clutch.Pos','K.Retard','K.Control','K.Count','PA','fresh','low']
 inp=f[order].copy();inp[['fresh','low']]=inp[['fresh','low']].astype(int)
 target=OUT/(label+'_replay.csv')
 inp.to_csv(target,index=False,float_format='%.8g')
 with target.open('a') as fd:
  fd.flush();__import__('os').fsync(fd.fileno())
 assert sum(1 for _ in target.open())==len(inp)+1,(label,'truncated derivative')
 row={'file_name':name,'label':label,'sha256':sha,'raw_frames':len(raw),'replay_frames':len(f),'source_seconds':float(t[-1]-t[0])/1000,'profile':('psi-absolute-map-' if label in ('20260603_002','20260605') else 'kpa-map-')+('lambda' if factor==1 else 'afr')};rows.append(row);print(json.dumps(row),flush=True)
(OUT/'core_inputs.json').write_text(json.dumps(rows,indent=2))
