#!/usr/bin/env python3
"""OEM+ grid/clock/palette contracts; not an Android emulator or hardware test."""
from pathlib import Path
import importlib.util
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash"
RES = ROOT / "app/src/main/res"
BASELINE = "6cedcdd14fe0d6c1357129fa3e65388acffc3ebc"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


reg = load("reg_oem", ROOT / "tests/verify_refinement.py")
fit = load("fit_oem", ROOT / "tests/verify_text_fit.py")

PROBE = r'''package io.github.asteroidb612zs.hondatadash;
public class OemProbe {
 static int checks;
 static void check(boolean pass,String why){if(!pass)throw new AssertionError(why);checks++;}
 GRID_METHOD
 BOUNDARY_METHOD
 public static void main(String[] args){
  double[] weights={1.30,1,1,1,1,1,1,1,1,1,1};
  double total=0;for(double w:weights)total+=w;
  for(int width=320;width<=1920;width++)for(int margin=1;margin<=12;margin++){
   int content=gridWidth(width,margin),left=(width-content)/2,right=width-content-left;
   check(content>0,"positive grid width");
   check(Math.abs(left-right)<=1&&left>=margin&&right>=margin,"centred safe margins");
   double used=0;int prior=0;int[] cell=new int[11];
   for(int i=0;i<11;i++){
    used+=weights[i];int edge=boundary(content,used,total);cell[i]=edge-prior;
    check(cell[i]>0,"positive footer cell");check(edge>=prior&&edge<=content,"monotonic footer edge");prior=edge;
   }
   check(prior==content,"full footer width");
   for(int i=2;i<=4;i++)check(Math.abs(cell[i]-cell[1])<=1,"CYL cells equal within pixel rounding");
   check(cell[0]>cell[1]&&cell[0] <= Math.ceil(cell[1]*1.35),"K.C only slightly wider than CYL");
  }
  float last=0;
  java.util.Set<Integer> lamps=new java.util.HashSet<Integer>();
  for(long t=0;t<=3400;t++){
   float reveal=StartupSequence.reveal(t);
   check(reveal>=last&&reveal>=0&&reveal<=1,"monotonic curtain release");last=reveal;
   check(reveal==0 || StartupSequence.placeholders(t)==0 && StartupSequence.checkStatus(t)==0,
         "no placeholder or SYSTEM CHECK superimposed on revealed live content");
   for(int group=0;group<3;group++)check(StartupSequence.shell(t,group)>=0&&StartupSequence.shell(t,group)<=1,"shell opacity");
   int count=StartupSequence.lampStage(t);check(count>=0&&count<=5,"stage sweep bounds");
   if(t>=1750&&t<2300)lamps.add(count);
   else check(count==0,"no simulated lamps outside self-check");
  }
  check(lamps.containsAll(java.util.Arrays.asList(1,2,3,4,5)),"all five self-check stages");
  check(StartupSequence.DURATION_MS==2900&&StartupSequence.reveal(2900)==1,"finite 2.9 second startup");
  check(StartupSequence.SIGNATURE.equals("Designed by ZhouQiZhi"),"exact author case");
  check(StartupSequence.placeholders(2100)>0 && StartupSequence.checkStatus(2100)>0,"check text visible before handoff");
  check(DashboardPalette.BACKGROUND==0xff030609,"OEM background");
  check(DashboardPalette.PRIMARY==0xfff2f5f7,"OEM cold white");
  check(DashboardPalette.SECONDARY==0xffadbdc9,"OEM secondary");
  check(DashboardPalette.CYAN==0xff65c9e8&&DashboardPalette.GREEN==0xff70d65b,"OEM cool accents");
  check(DashboardPalette.AMBER==0xffffbf47&&DashboardPalette.RED==0xfff34a43,"OEM warning colours");
  check(DashboardPalette.common(DashboardPalette.PURPLE)==DashboardPalette.CRITICAL,
        "legacy purple semantic maps to distinct critical coral");
  check(DashboardPalette.common(DashboardPalette.CRITICAL)==DashboardPalette.CRITICAL,
        "critical presentation mapping is idempotent");
  for(int card=0;card<8;card++){
   check(DashboardPalette.main(card,0xff3fb950)==DashboardPalette.PRIMARY,
         "normal/safe main digit stays cold white");
   check(DashboardPalette.main(card,0xffd29922)==DashboardPalette.AMBER,"warning survives palette");
   check(DashboardPalette.main(card,0xffff4444)==DashboardPalette.RED,"danger survives palette");
   for(int color:new int[]{0xff3fb950,0xffff4444,0xffd29922,0xff00d8ff,0xffe8eef2,0xffb040ff,0xffa0a0a0}){
    int mapped=DashboardPalette.main(card,color);
    check(mapped==DashboardPalette.main(card,mapped),"mapping idempotent across repeated visual frames");
   }
  }
  System.out.println("PASS: "+checks+" reference grid, animation clock and palette assertions (pure production methods)");
 }
}'''

PRESENTATION_PROBE = r'''package io.github.asteroidb612zs.hondatadash;
public class PresentationProbe {
 static class SystemClock {static long now=10000;static long elapsedRealtime(){return now;}}
 static class TextView {
  int color=0xff555555;void setTextColor(int c){color=c;}int getCurrentTextColor(){return color;}
 }
 static class ScaleBarView {int liveColor;void setLiveColor(int c){liveColor=c;}}
 TextView[] valueIntViews=new TextView[8];
 ScaleBarView[] scaleBars=new ScaleBarView[8];
 TextView[] auxiliaryViews=new TextView[11];
 boolean[] auxiliaryValid=new boolean[11];
 boolean cylRedFlashing;
 long[] cylYellowEnd=new long[4];
 static void check(boolean p,String s){if(!p)throw new AssertionError(s);}
 PresentationProbe(){
  for(int i=0;i<8;i++){valueIntViews[i]=new TextView();scaleBars[i]=new ScaleBarView();}
  for(int i=0;i<11;i++)auxiliaryViews[i]=new TextView();
 }
 PRESENTATION_METHOD
 public static void main(String[] args){
  PresentationProbe p=new PresentationProbe();
  for(int i=0;i<8;i++)p.valueIntViews[i].color=0xff3fb950;
  p.applyOemPalette();
  for(int i=0;i<8;i++)check(p.valueIntViews[i].color==DashboardPalette.PRIMARY,"safe main must be white "+i);

  p.valueIntViews[1].color=0xffd29922;p.valueIntViews[4].color=0xffff4444;p.applyOemPalette();
  check(p.valueIntViews[1].color==DashboardPalette.AMBER,"warning preserved");
  check(p.valueIntViews[4].color==DashboardPalette.RED,"danger preserved");

  p.auxiliaryViews[0].color=0xff3fb950;p.applyOemPalette();
  check(p.auxiliaryViews[0].color==DashboardPalette.PRIMARY,"KC normal white");
  p.auxiliaryViews[0].color=0xffd29922;p.applyOemPalette();
  check(p.auxiliaryViews[0].color==DashboardPalette.AMBER,"KC amber preserved");

  p.auxiliaryViews[1].color=0xff555555;p.auxiliaryValid[1]=false;p.cylYellowEnd[0]=0;p.applyOemPalette();
  check(p.auxiliaryViews[1].color==0xff555555,"invalid CYL placeholder remains grey");

  p.auxiliaryViews[1].color=0xffff4444;p.auxiliaryValid[1]=true;p.cylYellowEnd[0]=0;p.applyOemPalette();
  check(p.auxiliaryViews[1].color==DashboardPalette.PRIMARY,"historical CYL total returns white");

  p.auxiliaryViews[2].color=0xffd29922;p.auxiliaryValid[2]=true;p.cylYellowEnd[1]=11000;p.applyOemPalette();
  check(p.auxiliaryViews[2].color==DashboardPalette.AMBER,"new CYL event keeps amber window");

  p.cylRedFlashing=true;p.auxiliaryViews[3].color=0xffff4444;p.auxiliaryValid[3]=true;p.applyOemPalette();
  check(p.auxiliaryViews[3].color==DashboardPalette.RED,"rapid CYL accumulation keeps red flash colour");
  System.out.println("PASS: visual.3 final-presentation state transitions");
 }
}'''

STATUS_PROBE = r'''package io.github.asteroidb612zs.hondatadash;
public class StatusProbe {
 static int checks;
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class Source {boolean connected=true;boolean isConnected(){return connected;}}
 static class TextView {String label;int color;void setText(String s){label=s;}void setTextColor(int c){color=c;}}
 static class PorterDuff {enum Mode {SRC_IN}}
 static class Drawable {Drawable mutate(){return this;}void setColorFilter(int c,PorterDuff.Mode m){}}
 static class Dot {Drawable getBackground(){return null;}}
 Source dataSource=new Source();TextView statusText=new TextView();Dot statusDot;
 boolean foreground=true,rpmFrameValid=true;
 boolean[] frameValid=new boolean[8];long lastValidFrameTimeMs,connectedSinceMs=10000;
 static final int COLOR_SAFE=0xff3fb950,COLOR_WARN=0xffd29922,COLOR_DANGER=0xffff4444;
 void applyAlertAndFreshnessVisuals(){}void updateFlashState(){}
 static void check(boolean p,String s){if(!p)throw new AssertionError(s);checks++;}
 void expect(String text,int color){updateFreshnessStatus();check(text.equals(statusText.label),text);check(statusText.color==color,"status colour");}
 STATUS_METHODS
 public static void main(String[] args){
  StatusProbe p=new StatusProbe();
  SystemClock.now=10000;p.expect("INITIALIZING",DashboardPalette.SECONDARY);
  SystemClock.now=14999;p.expect("INITIALIZING",DashboardPalette.SECONDARY);
  SystemClock.now=15000;p.expect("NO DATA",DashboardPalette.AMBER);
  SystemClock.now=10100;p.lastValidFrameTimeMs=9999;p.frameValid[1]=true;
  p.expect("INITIALIZING",DashboardPalette.SECONDARY);
  check(!p.isDataFresh(),"old session cannot reactivate live bars or alarms");
  p.lastValidFrameTimeMs=10100;p.expect("LIVE",DashboardPalette.LIVE);
  check(p.isDataFresh(),"current session sample is fresh");
  p.rpmFrameValid=false;p.expect("NO DATA",DashboardPalette.AMBER);
  p.rpmFrameValid=true;p.frameValid[1]=false;p.expect("NO DATA",DashboardPalette.AMBER);
  for(int card:new int[]{1,2,4}){
   p.frameValid[card]=true;p.expect("LIVE",DashboardPalette.LIVE);p.frameValid[card]=false;
  }
  p.frameValid[1]=true;SystemClock.now=10599;p.expect("LIVE",DashboardPalette.LIVE);
  SystemClock.now=10600;p.expect("STALE",DashboardPalette.AMBER);
  check(!p.isDataFresh(),"500ms freshness boundary");
  SystemClock.now=11599;p.expect("STALE",DashboardPalette.AMBER);
  SystemClock.now=11600;p.expect("DATA LOST",DashboardPalette.AMBER);
  p.dataSource.connected=false;p.setConnectionStatus("PAIR",0);p.expect("PAIR",DashboardPalette.AMBER);
  p.dataSource=null;p.setConnectionStatus("RECONNECT",0);p.expect("RECONNECTING",DashboardPalette.SECONDARY);
  p.setConnectionStatus("CONNECT",0);check("CONNECTING".equals(p.statusText.label),"normalized connecting label");
  check(p.statusText.color==DashboardPalette.SECONDARY,"quiet connecting colour");
  System.out.println("PASS: "+checks+" production status/freshness assertions; no timer-only LIVE");
 }
}'''


def static_contracts():
    ns = fit.NS
    tree = ET.parse(RES / "layout/activity_main.xml").getroot()
    by_id = {n.attrib.get(ns + "id", "").split("/")[-1]: n for n in tree.iter()}
    for ident in ("mainRowOne", "mainRowTwo", "bottomRow"):
        row = by_id[ident]
        assert row.tag.endswith("AlignedRowLayout")
        weights = [float(n.attrib[ns + "layout_weight"]) for n in row]
        assert weights == ([1.30] + [1.0] * 10 if ident == "bottomRow" else [1.0] * 4)
        assert all(n.attrib[ns + "layout_width"] == "0dp" for n in row)
    assert by_id["dashboardContent"].tag.endswith("DashboardGridLayout")
    assert by_id["startupOverlay"].attrib[ns + "visibility"] == "gone"
    keep = (ROOT / "app/proguard-rules.pro").read_text()
    for name in ("AlignedRowLayout", "DashboardGridLayout", "HeaderLayout", "HondaBrandView", "StartupOverlayView"):
        assert "-keep class io.github.asteroidb612zs.hondatadash." + name in keep
    main = (JAVA / "MainActivity.java").read_text()
    overlay = (JAVA / "StartupOverlayView.java").read_text()
    assert '{"E99", "--"}' in main, "Ethanol normal-width reference must be E99"
    assert 'bar.setRange(0, 100);' in main, "Ethanol data scale must still preserve E100"
    assert '{0, 100},       // 0: Ethanol %' in main, "Ethanol sensor validity must remain 0..100"
    assert 'now >= cylYellowEnd[i - 1]' in main and '!cylRedFlashing' in main, "CYL history/event presentation split missing"
    assert 'i >= 1 && i <= 4 && auxiliaryValid[i] && !cylRedFlashing' in main, "invalid CYL placeholders must not be promoted to normal white"
    assert "!startupShown && savedInstanceState == null" in main
    for signature in ("protected void onPause(", "protected void onDestroy("):
        assert "startupOverlay.finish()" in reg.method(main, signature)
    resume = reg.method(main, "protected void onResume(")
    assert "startupOverlay.start" not in resume and "dataSource.startPolling()" in resume
    assert "import io.github.asteroidb612zs.hondatadash.data" not in overlay and "setRpm(" not in overlay
    assert "removeCallbacks(frame)" in reg.method(overlay, "public void finish(")
    assert "setText(" not in overlay and "value.setAlpha" not in overlay
    assert "drawScaleOnly" in overlay and "postOnAnimation" in overlay
    assert "StartupSequence.placeholders(elapsed)" in overlay and "StartupSequence.checkStatus(elapsed)" in overlay
    assert '"LIVE"' not in overlay  # The curtain cannot manufacture a live link.
    status = reg.method(main, "private void updateFreshnessStatus(")
    assert "rpmFrameValid" in status and "connectedSinceMs" in status and '"NO DATA"' in status
    assert "Math.round(getMeasuredWidth() * .18f)" in (JAVA / "HeaderLayout.java").read_text()
    print("PASS: production reference-lock XML, shrinking rules and non-blocking startup lifecycle static contracts")


def protect_data():
    # The repository history is optional in a source ZIP. Compare when available.
    probe = subprocess.run(["git", "cat-file", "-e", BASELINE + "^{commit}"], cwd=ROOT, capture_output=True)
    if probe.returncode:
        print("SKIP: baseline history absent; use source archive's recorded scope for manual review")
        return
    changes = subprocess.check_output(["git", "diff", BASELINE, "--", "app/src/main/java/io/github/asteroidb612zs/hondatadash/data"], cwd=ROOT, text=True)
    assert not changes, "communication / protocol / engine state files changed"
    path = "app/src/main/java/io/github/asteroidb612zs/hondatadash/MainActivity.java"
    previous = subprocess.check_output(["git", "show", BASELINE + ":" + path], cwd=ROOT, text=True)
    current = (ROOT / path).read_text()
    protected = ("public void onDataReceived(", "private String formatMainText(", "private String formatExtremeText(",
                 "private int getTrimSemanticColor(", "private int getIgnSemanticColor(", "private int getMapSemanticColor(",
                 "private int getEthanolColor(", "private int getEctColor(", "private int getIatColor(",
                 "private int getAfSeverity(", "private boolean isAfColorContext(",
                 "private long getAfAttackMs(", "private boolean isSemanticFramePlausible(",
                 "private void updateEngineRunningGate(",
                 "private void updateMainColorState(", "private void applyMainValueSemanticColor(",
                 "private void applyConfidenceVisual(", "private void applyStrimInterpretabilityVisual(",
                 "private void renderHeldCombustionCard(")
    # V2.1.1 intentionally changes onDataReceived and getAfAttackMs. The dedicated
    # verify_v211_stability.py exact-blob lock is stricter for those reviewed deltas.
    build_text = (ROOT / "app/build.gradle").read_text()
    if ('2.1.1-stability.1' in build_text) or ('versionName "3.0.0"' in build_text):
        protected = tuple(name for name in protected
                          if name not in ("public void onDataReceived(", "private long getAfAttackMs("))
    for name in protected:
        assert reg.method(previous, name) == reg.method(current, name), name
    print(f"PASS: all data-layer files and {len(protected)} protected parsing/formatting/threshold/state/display-admission methods unchanged")


def main():
    static_contracts()
    protect_data()
    grid = reg.method((JAVA / "DashboardGridLayout.java").read_text(), "static int gridWidth(")
    boundary = reg.method((JAVA / "AlignedRowLayout.java").read_text(), "static int boundary(")
    with tempfile.TemporaryDirectory(prefix="hondata-oem-") as tmp:
        temp = Path(tmp)
        path = temp / "OemProbe.java"
        path.write_text(PROBE.replace("GRID_METHOD", grid).replace("BOUNDARY_METHOD", boundary))
        main_source = (JAVA / "MainActivity.java").read_text()
        status = temp / "StatusProbe.java"
        status.write_text(STATUS_PROBE.replace("STATUS_METHODS", "\n".join(
            reg.method(main_source, s) for s in ("private void updateFreshnessStatus(",
                                                "private void setConnectionStatus(", "private boolean isDataFresh("))))
        presentation = temp / "PresentationProbe.java"
        presentation.write_text(PRESENTATION_PROBE.replace(
            "PRESENTATION_METHOD", reg.method(main_source, "private void applyOemPalette(")))
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11", "-d", str(temp),
                        str(path), str(status), str(presentation), str(JAVA / "StartupSequence.java"),
                        str(JAVA / "DashboardPalette.java")], check=True)
        subprocess.run(["java", "-cp", str(temp), "io.github.asteroidb612zs.hondatadash.OemProbe"], check=True)
        subprocess.run(["java", "-cp", str(temp), "io.github.asteroidb612zs.hondatadash.StatusProbe"], check=True)
        subprocess.run(["java", "-cp", str(temp), "io.github.asteroidb612zs.hondatadash.PresentationProbe"], check=True)


if __name__ == "__main__":
    main()
