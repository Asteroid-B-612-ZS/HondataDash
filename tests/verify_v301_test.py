#!/usr/bin/env python3
"""Test-candidate contracts. Frozen V3 promotion verifier remains unmodified."""
from pathlib import Path
import subprocess, tempfile, re, sys
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash'
BASE='c077201222bb18c300ca4f10639c8063907aa2c3'

def method(s, signature):
 start=s.index(signature);p=s.index('{',start);depth=0
 for i in range(p,len(s)):
  depth+=(s[i]=='{')-(s[i]=='}')
  if depth==0:return s[start:i+1]

def verify_scope():
 for path in ('app/src/main/java/io/github/asteroidb612zs/hondatadash/data','app/src/main/res/layout','app/proguard-rules.pro'):
  assert not subprocess.check_output(['git','diff',BASE,'--',path],cwd=ROOT),path
 for name in ('DashboardPalette.java','ColorRecovery.java','ShiftLightView.java','ShiftLightRenderer.java','DashboardGridLayout.java','TextFitGeometry.java'):
  p=JAVA/name;old=subprocess.check_output(['git','show',BASE+':'+str(p.relative_to(ROOT))],cwd=ROOT,text=True)
  assert p.read_text()==old,name
 current=(JAVA/'MainActivity.java').read_text()
 previous=subprocess.check_output(['git','show',BASE+':'+str((JAVA/'MainActivity.java').relative_to(ROOT))],cwd=ROOT,text=True)
 # Exact whole-source contract, allowing only the explicit integration below.
 normalized=current.replace('import android.content.res.Configuration;\n','')
 normalized=normalized.replace('    private DashboardNightController nightController;\n    private final LowLoadDisplayContext lowLoadDisplay = new LowLoadDisplayContext();\n','')
 for line in ('        nightController = new DashboardNightController(this);\n','        nightController.start();\n','        nightController.stop();\n','        if (nightController != null) nightController.stop();\n','                lowLoadDisplay.update(state, data, now);\n'):
  normalized=normalized.replace(line,'')
 config='    @Override\n    public void onConfigurationChanged(Configuration configuration) {\n        super.onConfigurationChanged(configuration);\n        if (nightController != null) nightController.refresh();\n    }\n\n'
 normalized=normalized.replace(config,'')
 normalized=normalized.replace('                                    long afAttack = lowLoadDisplay.afAttackMs(afSeverity,\n                                            getAfAttackMs(afSeverity, state));','                                    long afAttack = getAfAttackMs(afSeverity, state);')
 block='''
        // A negative absolute angle alone is not knock evidence during gentle
        // low-speed torque control. Keep the live value; K.R/K.C/CYL remain live.
        if (i == 6 && lowLoadDisplay.calmIgn()) {
            colorRecovery.reset(i);
            resolvedMainColors[i] = COLOR_TEXT_NORMAL;
            return;
        }
'''
 normalized=normalized.replace(block,'')
 assert normalized==previous,'MainActivity exceeds approved integration scope'
 manifest=(ROOT/'app/src/main/AndroidManifest.xml').read_text()
 assert 'orientation|screenSize|uiMode' in manifest
 controller=(JAVA/'DashboardNightController.java').read_text()
 for forbidden in ('setNightMode(', 'SCREEN_BRIGHTNESS','enableCarMode(', 'dataSource', 'recreate('):assert forbidden not in controller,forbidden
 assert 'ACTION_TIME_CHANGED' in controller and 'ACTION_TIMEZONE_CHANGED' in controller
 assert 'Calendar.HOUR_OF_DAY' in controller and 'handler.removeCallbacks(tick)' in controller
 assert 'Build.FINGERPRINT.equals' in controller
 build=(ROOT/'app/build.gradle').read_text()
 assert 'versionCode 58' in build and 'versionName "3.0.1-test.1"' in build
 assert 'applicationIdSuffix ".test"' in build
 assert 'signingConfig' not in build.split('release {')[1].split('}')[0]
 for name in ('bg_card_night.xml','bg_header_night.xml','bg_bottom_cell_night.xml'):
  assert '<gradient' not in (ROOT/'app/src/main/res/drawable'/name).read_text()
 print('PASS: exact V3 MainActivity delta, frozen data/geometry/shift lights, API17 theme lifecycle and isolated test identity')

PROBE=r'''
package io.github.asteroidb612zs.hondatadash;
import io.github.asteroidb612zs.hondatadash.data.*;
public class CandidateProbe {
 static int checks;
 static void ok(boolean x,String s){if(!x)throw new AssertionError(s);checks++;}
 static SensorData frame(){SensorData d=new SensorData();int[] p={0x100,0x101,0x120,0x122,0x110,0x170,0x130,0x410};double[] v={850,6,0,3,35,101,1.2,0};for(int i=0;i<p.length;i++)d.put(p[i],v[i]);return d;}
 static void settle(LowLoadDisplayContext c, EngineSemanticState s, SensorData d){c.reset();for(long t=0;t<=500;t+=100)c.update(s,d,t);}
 public static void main(String[]args){
  NightModePolicy n=new NightModePolicy(false);
  for(int minute=0;minute<1440;minute++){n.update(NightModePolicy.DAY,minute/60);ok(n.isNight()==(minute>=1080||minute<420),"clock boundary "+minute);ok(!n.followsSystem(),"unproven DAY uses clock");}
  n.update(0x22,12);ok(n.isNight()&&n.followsSystem(),"system NIGHT wins at noon, mask ignores car mode");
  n.update(0x12,23);ok(!n.isNight()&&n.followsSystem(),"observed system DAY wins at night");
  n=new NightModePolicy(n.hasObservedSystemNight());n.update(0x10,23);ok(!n.isNight(),"confirmed source survives restart");
  n.update(0,19);ok(n.isNight()&&!n.followsSystem(),"undefined falls back despite learned capability");
  n.update(0x30,9);ok(!n.isNight()&&!n.followsSystem(),"invalid mask falls back");
  n=new NightModePolicy(false);n.update(0x10,19);ok(n.isNight(),"new firmware forgets capability");
  int[] colors={DashboardPalette.PRIMARY,DashboardPalette.SECONDARY,DashboardPalette.RED,DashboardPalette.AMBER,0xffa0a0a0,0xff091017};
  NightPalette.active=false;for(int c:colors)ok(NightPalette.color(c)==c,"day identity");
  NightPalette.active=true;ok(NightPalette.color(DashboardPalette.PRIMARY)==0xffdce6ec,"night cold white");
  ok(NightPalette.color(DashboardPalette.BACKGROUND)==0xff000000,"night black");
  for(int c:new int[]{DashboardPalette.RED,DashboardPalette.AMBER,0xffa0a0a0})ok(NightPalette.color(c)==c,"alert/sync meaning preserved");
  NightPalette.active=false;for(int c:colors)ok(NightPalette.color(c)==c,"day restores exactly, no cumulative dimming");
  LowLoadDisplayContext c=new LowLoadDisplayContext();EngineSemanticState s=new EngineSemanticState();s.thermal=EngineSemanticState.ThermalContext.READY;SensorData d=frame();
  c.update(s,d,0);ok(!c.isActive(),"not one-frame inference");for(long t=100;t<500;t+=100)c.update(s,d,t);ok(!c.isActive(),"500ms admission");c.update(s,d,500);ok(c.isActive()&&c.calmIgn(),"stable low load");
  d.put(0x101,11);c.update(s,d,600);ok(c.isActive(),"speed hysteresis");d.put(0x101,15);c.update(s,d,700);ok(!c.isActive(),"speed exit");
  int[] p={0x100,0x101,0x120,0x122,0x110,0x170,0x130};double[] exits={1800,15,10,11,112,Double.NaN,0};
  for(int i=0;i<p.length;i++){d=frame();settle(c,s,d);d.put(p[i],exits[i]);c.update(s,d,520);ok(!c.isActive()&&!c.calmIgn(),"immediate exit "+p[i]);}
  for(int pid:p){d=frame();settle(c,s,d);d.put(pid,Double.NaN);c.update(s,d,520);ok(!c.isActive(),"missing channel "+pid);}
  d=frame();settle(c,s,d);d.put(0x410,2);c.update(s,d,520);ok(c.isActive()&&!c.calmIgn(),"retard evidence retains original IGN warning");
  d.put(0x410,Double.NaN);c.update(s,d,540);ok(!c.calmIgn(),"unknown retard never calms IGN");
  d=frame();settle(c,s,d);s.main=EngineSemanticState.MainState.WOT;c.update(s,d,520);ok(!c.isActive()&&c.afAttackMs(2,100)==100,"WOT red remains 100ms");ok(c.afAttackMs(1,150)==150,"WOT amber remains 150ms");s.main=EngineSemanticState.MainState.NORMAL;
  for(EngineSemanticState.Modifier m:EngineSemanticState.Modifier.values()){if(m==EngineSemanticState.Modifier.NONE)continue;settle(c,s,d);s.modifier=m;c.update(s,d,520);ok(!c.isActive(),"transient exit "+m);s.modifier=EngineSemanticState.Modifier.NONE;}
  settle(c,s,d);s.combustion=EngineSemanticState.CombustionState.RECOVERY;c.update(s,d,520);ok(!c.isActive(),"admission still owns recovery");s.combustion=EngineSemanticState.CombustionState.FIRING_VALID;
  settle(c,s,d);c.update(s,d,1100);ok(!c.isActive(),"stale gap restarts qualification");
  settle(c,s,d);c.update(s,d,10);ok(!c.isActive(),"monotonic rollback restarts qualification");
  settle(c,s,d);ColorRecovery colorsLatch=new ColorRecovery();int level=0;
  for(long t=500;t<3500;t+=100){c.update(s,d,t);level=colorsLatch.update(5,2,t,c.afAttackMs(2,1000));ok(level<2,"brief low-load excursion cannot paint red");}
  c.update(s,d,3500);ok(colorsLatch.update(5,2,3500,c.afAttackMs(2,1000))==2,"sustained low-load deviation MUST still turn red at 3s");
  colorsLatch=new ColorRecovery();for(long t=4000;t<6000;t+=100){c.update(s,d,t);level=colorsLatch.update(5,1,t,c.afAttackMs(1,1500));ok(level<1,"amber persistence");}c.update(s,d,6000);ok(colorsLatch.update(5,1,6000,c.afAttackMs(1,1500))==1,"amber at 2s");
  System.out.println("PASS: "+checks+" candidate clock/source/colour/low-load/escape/persistence assertions");
 }
}'''

def main():
 verify_scope()
 with tempfile.TemporaryDirectory(prefix='hondata-v301-') as tmp:
  p=Path(tmp);(p/'CandidateProbe.java').write_text(PROBE)
  (p/'SystemClock.java').write_text('package android.os; public class SystemClock { public static long elapsedRealtime(){return 0L;} }')
  files=[JAVA/n for n in ('NightModePolicy.java','NightPalette.java','DashboardPalette.java','LowLoadDisplayContext.java','ColorRecovery.java')]
  files += [JAVA/'data'/n for n in ('EngineSemanticState.java','SensorData.java','HondataProtocol.java')]
  subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','--release','11','-d',str(p),str(p/'CandidateProbe.java'),str(p/'SystemClock.java'),*map(str,files)],check=True)
  subprocess.run(['java','-cp',str(p),'io.github.asteroidb612zs.hondatadash.CandidateProbe'],check=True)
if __name__=='__main__':main()
