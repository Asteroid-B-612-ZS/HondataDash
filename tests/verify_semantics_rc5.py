#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash/data'
CLOCK='''package android.os; public final class SystemClock { public static long now=1000; public static long elapsedRealtime(){return now;} public static void step(long ms){now+=ms;} }'''
PROBE=r'''package io.github.asteroidb612zs.hondatadash.data;
public final class Rc5SemanticProbe {
 static SensorData f(double rpm,double spd,double gear,double map,double tp,double inj,double cl,double lam,double closed,double ect){
  SensorData d=new SensorData(); d.put(HondataProtocol.CID_RPM,rpm);d.put(HondataProtocol.CID_Speed,spd);d.put(HondataProtocol.CID_Gear,gear);d.put(HondataProtocol.CID_MAP,map);d.put(HondataProtocol.CID_ThrottlePlate,tp);d.put(HondataProtocol.CID_Inj,inj);d.put(HondataProtocol.CID_ClutchPos,cl);d.put(HondataProtocol.CID_TargetLambda,lam);d.put(HondataProtocol.CID_Lambda,lam);d.put(HondataProtocol.CID_ClosedLoop,closed);d.put(HondataProtocol.CID_ECT,ect);return d;
 }
 static void step(long ms){android.os.SystemClock.step(ms);} static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]a){
  EngineStateTracker t=new EngineStateTracker();
  t.update(f(3000,80,4,100,30,2.0,0,1.0,1,82)); step(50);
  EngineSemanticState s=t.update(f(2700,80,4,60,0,0.8,65,1.0,1,82)); ok(s.isShift(),"clutch/rpm shift enter"); step(50);
  s=t.update(f(2200,80,5,35,0,0.0,100,1.0,1,82)); ok(s.isShift(),"gear change shift latch"); step(600);
  s=t.update(f(2100,78,5,55,1,1.5,0,1.0,1,82)); step(80); s=t.update(f(2080,77,5,55,1,1.5,0,1.0,1,82)); ok(s.isCoast(),"fuel-on coast distinct from DFCO");
  step(120); s=t.update(f(2050,76,5,35,1,0.0,0,2.0,0,82)); step(120); s=t.update(f(2000,75,5,30,1,0.0,0,2.0,0,82)); ok(s.isDfco(),"DFCO priority after sustain");
  System.out.println("PASS: legacy SHIFT/COAST/DFCO semantic compatibility probe");
 }
}'''
with tempfile.TemporaryDirectory(prefix='rc5-sem-') as td:
 p=Path(td); (p/'android/os').mkdir(parents=True); (p/'android/os/SystemClock.java').write_text(CLOCK); (p/'Rc5SemanticProbe.java').write_text(PROBE)
 src=[p/'android/os/SystemClock.java', p/'Rc5SemanticProbe.java']+[JAVA/n for n in ('SensorData.java','HondataProtocol.java','EngineSemanticState.java','EngineStateTracker.java')]
 subprocess.run(['javac','-source','8','-target','8','-d',str(p/'classes'),*map(str,src)],check=True,stdout=subprocess.DEVNULL)
 subprocess.run(['java','-cp',str(p/'classes'),'io.github.asteroidb612zs.hondatadash.data.Rc5SemanticProbe'],check=True)
