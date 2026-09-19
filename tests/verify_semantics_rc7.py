#!/usr/bin/env python3
from pathlib import Path
import subprocess, tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/io/github/asteroidb612zs/hondatadash/data'
CLOCK = r'''package android.os;
public final class SystemClock {
  public static long now=1000L;
  public static long elapsedRealtime(){return now;}
  public static void step(long ms){now+=ms;}
  public static void set(long ms){now=ms;}
}'''

PROBE = r'''package io.github.asteroidb612zs.hondatadash.data;
public final class Rc7SemanticProbe {
 static SensorData f(double rpm,double spd,double gear,double map,double tp,double inj,double cl,
                     double target,double measured,double closed,double ect){
  SensorData d=new SensorData();
  d.put(HondataProtocol.CID_RPM,rpm); d.put(HondataProtocol.CID_Speed,spd);
  d.put(HondataProtocol.CID_Gear,gear); d.put(HondataProtocol.CID_MAP,map);
  d.put(HondataProtocol.CID_ThrottlePlate,tp); d.put(HondataProtocol.CID_Inj,inj);
  d.put(HondataProtocol.CID_ClutchPos,cl); d.put(HondataProtocol.CID_TargetLambda,target);
  d.put(HondataProtocol.CID_Lambda,measured); d.put(HondataProtocol.CID_ClosedLoop,closed);
  d.put(HondataProtocol.CID_ECT,ect); d.put(HondataProtocol.CID_Ign,10.0);
  d.put(HondataProtocol.CID_STrim,0.0); return d;
 }
 static void step(long ms){android.os.SystemClock.step(ms);}
 static void set(long ms){android.os.SystemClock.set(ms);}
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}

 static void testArmedCancelAndUpshift(){
  set(1000); EngineStateTracker t=new EngineStateTracker();
  t.update(f(2500,50,3,55,8,1.8,0,1,1,1,82));
  step(20); EngineSemanticState s=t.update(f(2490,50,3,55,4,1.8,5,1,1,1,82));
  ok(s.isShiftArmed(),"4% clutch rising must arm immediately");
  for(int i=0;i<7;i++){step(100);s=t.update(f(2490,50,3,55,4,1.8,60,1,1,1,82));}
  ok(!s.isShiftActive(),"unconfirmed held clutch must time out");
  step(100);s=t.update(f(2490,50,3,55,4,1.8,60,1,1,1,82));
  ok(!s.isShiftActive(),"must not rearm while clutch remains held");
  step(20);t.update(f(2490,50,3,55,4,1.8,0,1,1,1,82));
  step(20);s=t.update(f(2480,50,3,55,4,1.8,5,1,1,1,82));
  ok(s.isShiftArmed(),"release permits next arm");

  set(3000); t=new EngineStateTracker();
  t.update(f(3400,80,3,100,35,2.2,0,.82,.82,0,82));
  step(20); s=t.update(f(3380,80,3,95,5,2.0,5,.9,.9,0,82));
  ok(s.isShiftArmed(),"upshift should arm");
  step(80); s=t.update(f(3100,80,3,65,1,0.0,45,2,2,0,82));
  ok(s.isShiftConfirmed() && s.isShiftFuelCut(),"fuel-cut shift confirms and is shift fuel cut");
  ok(!s.isDfco(),"shift fuel cut is not DFCO");
 }

 static void testRevMatchAndLongClutchExit(){
  set(5000); EngineStateTracker t=new EngineStateTracker();
  t.update(f(2200,55,4,45,5,1.4,0,1,1,1,82));
  step(20); EngineSemanticState s=t.update(f(2220,55,4,45,4,1.4,5,1,1,1,82));
  step(80); s=t.update(f(2700,55,4,70,25,2.0,55,1,1,1,82));
  ok(s.isShiftConfirmed(),"positive-rpm rev-match must confirm");

  // Observe the actual gear transition, then keep clutch physically depressed.
  step(100); s=t.update(f(2750,55,3,65,12,1.5,80,1,1,1,82));
  ok(s.isShiftConfirmed(),"gear change keeps short bridge");
  step(450); s=t.update(f(2700,54,3,55,8,1.4,80,1,1,1,82));
  ok(!s.isShiftActive(),"gear-complete shift must end even if clutch is still held");
  ok(s.combustion==EngineSemanticState.CombustionState.RECOVERY
      || s.combustion==EngineSemanticState.CombustionState.FIRING_VALID,
      "held clutch after completed shift is not endless SHIFT fuel cut");
 }

 static void testBt42GearAuthoritativeShift(){
  // BT42 profile: Gear exists, Clutch.Pos is unavailable.
  set(7000); EngineStateTracker t=new EngineStateTracker();
  t.update(f(3200,70,3,120,40,2.2,Double.NaN,.82,.82,0,82));

  // Sharp lift/RPM drop may pre-arm, but must not directly confirm SHIFT.
  step(50); EngineSemanticState s=t.update(f(3000,70,3,80,2,0.0,Double.NaN,2,2,0,82));
  ok(s.isShiftArmed(),"BT42 legacy trajectory may pre-arm");
  ok(!s.isShiftConfirmed(),"BT42 trajectory alone must not confirm SHIFT");
  ok(s.isDfcoFuelCut(),"BT42 armed-only fuel cut must remain DFCO-authoritative");

  // New Gear must remain stable for 100 ms before authoritative confirmation.
  step(50); s=t.update(f(2500,69,4,70,5,1.0,Double.NaN,1,1,1,82));
  ok(!s.isShiftConfirmed(),"first changed-Gear frame is candidate only");
  step(100); s=t.update(f(2450,69,4,70,5,1.0,Double.NaN,1,1,1,82));
  ok(s.isShiftConfirmed(),"100 ms stable Gear change confirms BT42 SHIFT");

  // Ordinary lift with no Gear change must time out instead of becoming confirmed SHIFT.
  set(10000); t=new EngineStateTracker();
  t.update(f(3000,70,4,110,35,2.0,Double.NaN,.9,.9,0,82));
  step(50); s=t.update(f(2700,70,4,60,1,0.0,Double.NaN,2,2,0,82));
  ok(s.isShiftArmed() && !s.isShiftConfirmed(),"lift-only trajectory is armed, not confirmed");
  step(700); s=t.update(f(2200,68,4,40,1,0.0,Double.NaN,2,2,0,82));
  ok(!s.isShiftActive(),"BT42 false arm must time out without Gear transition");
  ok(s.isDfcoFuelCut(),"ordinary overrun remains DFCO after false-arm timeout");
 }

 static void testFuelCutTaxonomy(){
  set(8000); EngineStateTracker t=new EngineStateTracker();
  t.update(f(2200,60,4,50,5,1.2,0,1,1,1,82));
  step(20); EngineSemanticState s=t.update(f(2150,60,4,45,1,0.0,0,2,2,0,82));
  ok(s.isDfcoFuelCut(),"moving high-rpm closed-throttle fuel cut is DFCO");

  set(9000); t=new EngineStateTracker();
  t.update(f(1100,45,4,50,5,1.0,0,1,1,1,82));
  step(20); s=t.update(f(950,45,4,40,1,0.0,0,2,2,0,82));
  ok(s.isOtherFuelCut(),"moving low-rpm/neutral-like fuel cut must still be combustion-invalid");
  ok(!s.isDfco(),"OTHER_FUEL_CUT must not falsely label DFCO");
 }

 static void testAdmissionFrontAndRecovery(){
  set(11000); CombustionDisplayAdmission a=new CombustionDisplayAdmission();
  EngineSemanticState s=new EngineSemanticState();
  SensorData d=f(2500,60,4,55,2,1.0,0,1.0,1.08,1,82);
  s.modifier=EngineSemanticState.Modifier.TIP_OUT;
  CombustionDisplayAdmission.Snapshot x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(x.frontGuard && x.holdAf && x.holdIgn && x.holdStrim,"TIP_OUT front guard must protect fast combustion cards");

  // Guard ends; measured lambda remains far from target. A/F must not release early.
  s.modifier=EngineSemanticState.Modifier.NONE;
  s.combustion=EngineSemanticState.CombustionState.RECOVERY;
  d=f(2400,58,4,55,7,1.2,0,1.0,1.12,1,82);
  // First valid post-guard sample starts the parameter-specific recovery timers.
  step(20); x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(x.holdIgn && x.holdAf && x.holdStrim,"post-guard recovery starts held");
  step(180); x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(!x.holdIgn && x.holdAf && x.holdStrim,"IGN may return first; A/F/S.TRIM remain protected");
  step(250); x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(x.holdAf,"A/F target-relative error must block early release");
  step(750); x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(x.holdAf,"A/F remains held before bounded cap");
  step(220); x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(!x.holdAf,"bounded cap must expose a genuinely persistent mismatch");

  // Fresh episode with lambda back on target should recover by stability, not cap.
  a.reset(); s=new EngineSemanticState();
  s.shiftPhase=EngineSemanticState.ShiftPhase.SHIFT_CONFIRMED;
  s.combustion=EngineSemanticState.CombustionState.SHIFT_FUEL_CUT;
  d=f(3000,70,3,60,1,0,80,2,2,0,82);
  a.update(s,d,android.os.SystemClock.elapsedRealtime());
  step(100); s.shiftPhase=EngineSemanticState.ShiftPhase.NONE;
  s.combustion=EngineSemanticState.CombustionState.RECOVERY;
  d=f(2800,68,4,60,8,1.5,0,1.0,1.02,1,82);
  x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  step(200); x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(!x.holdAf,"A/F near target for 200 ms should recover naturally");
  ok(!x.holdIgn,"IGN recovered earlier");
  step(200); x=a.update(s,d,android.os.SystemClock.elapsedRealtime());
  ok(!x.holdStrim,"S.TRIM should recover after stable closed-loop interval");
 }

 static void testTrustedMemory(){
  TrustedDisplayMemory m=new TrustedDisplayMemory();
  long t=10000;
  // 40 ms cadence: latest samples 14.7, edge sample 18.5 should never be recorded as trusted.
  for(int i=0;i<8;i++){m.record(5,14.6f+i*.02f,t,true);t+=40;}
  m.record(5,18.5f,t,false);
  ok(m.captureHold(5,t),"trusted hold value should be available");
  float v=m.getHoldValue(5);
  ok(v<15.0f,"hold selection must come from pre-edge trusted history");
  m.releaseHold(5); ok(!m.hasHoldValue(5),"release clears hold latch");
  m.reset(); ok(!m.captureHold(5,t),"reset purges previous drive memory");
 }

 static void testEphemeralMemory(){
  EphemeralDiagnosticMemory m=new EphemeralDiagnosticMemory();
  EngineSemanticState s=new EngineSemanticState();
  CombustionDisplayAdmission a=new CombustionDisplayAdmission();
  for(int i=0;i<400;i++){
    long t=20000+i*20; android.os.SystemClock.set(t);
    SensorData d=f(2000,50,4,60,10,1.2,0,1,1,1,82);
    CombustionDisplayAdmission.Snapshot x=a.update(s,d,t);
    m.record(s,d,x,t);
  }
  ok(m.retainedSamples()==EphemeralDiagnosticMemory.CAPACITY,"ring memory must stay hard-bounded");
  ok(m.stats().acceptedSamples==400,"session stats can summarize without retaining raw history");
  EphemeralDiagnosticMemory.SessionStats same=m.stats();
  ok(same==m.stats(),"stats snapshot is reused; no per-call allocation");
  m.clear();
  ok(m.retainedSamples()==0 && m.stats().acceptedSamples==0,"engine-stop clear purges the session working set");
 }

 static void testFuelPressurePersistence(){
  set(30000); FuelPressureAlertTracker fp=new FuelPressureAlertTracker();
  EngineSemanticState s=new EngineSemanticState();
  // Low-demand (~4 MPa) control deviations need longer persistence.
  ok(!fp.update(true,s,3500,4000,android.os.SystemClock.elapsedRealtime()),"first low-demand low frame not alert");
  step(500); ok(!fp.update(true,s,3500,4000,android.os.SystemClock.elapsedRealtime()),"brief low-demand rail transient not alert");
  step(310); ok(fp.update(true,s,3500,4000,android.os.SystemClock.elapsedRealtime()),"sustained low-demand pressure still alerts");
  fp.reset();
  // High-demand protection remains fast.
  step(100); ok(!fp.update(true,s,7000,10000,android.os.SystemClock.elapsedRealtime()),"first high-demand low frame not alert");
  step(310); ok(fp.update(true,s,7000,10000,android.os.SystemClock.elapsedRealtime()),"high-demand pressure deficit alerts after 300 ms");
  s.combustion=EngineSemanticState.CombustionState.OTHER_FUEL_CUT;
  step(20); ok(!fp.update(true,s,6000,10000,android.os.SystemClock.elapsedRealtime()),"any invalid combustion/fuel-cut suppresses normal transient F.P alert");
 }

 public static void main(String[]a){
  testArmedCancelAndUpshift(); testRevMatchAndLongClutchExit(); testBt42GearAuthoritativeShift();
  testFuelCutTaxonomy();
  testAdmissionFrontAndRecovery(); testTrustedMemory(); testEphemeralMemory(); testFuelPressurePersistence();
  System.out.println("PASS: IT3 semantic/BT42-shift/admission/trusted-memory/ephemeral-memory probe");
 }
}'''

with tempfile.TemporaryDirectory(prefix='rc7-sem-') as td:
    p=Path(td); (p/'android/os').mkdir(parents=True)
    (p/'android/os/SystemClock.java').write_text(CLOCK)
    (p/'Rc7SemanticProbe.java').write_text(PROBE)
    src=[p/'android/os/SystemClock.java',p/'Rc7SemanticProbe.java']+[JAVA/n for n in (
        'SensorData.java','HondataProtocol.java','EngineSemanticState.java','EngineStateTracker.java',
        'CombustionDisplayAdmission.java','FuelPressureAlertTracker.java','TrustedDisplayMemory.java',
        'EphemeralDiagnosticMemory.java')]
    subprocess.run(['javac','-source','8','-target','8','-Xlint:-options','-d',str(p/'classes'),*map(str,src)],check=True)
    subprocess.run(['java','-cp',str(p/'classes'),'io.github.asteroidb612zs.hondatadash.data.Rc7SemanticProbe'],check=True)
