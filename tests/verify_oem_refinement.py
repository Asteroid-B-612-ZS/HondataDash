#!/usr/bin/env python3
"""Visual.3 preservation and real view no-op checks; JVM doubles, not device FPS."""
from pathlib import Path
import importlib.util
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash"
BASELINE = "db607ab745767f019465ae173aca05f6639af9fc"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fit = load("refine_fit", ROOT / "tests/verify_text_fit.py")
reg = load("refine_reg", ROOT / "tests/verify_refinement.py")


def freeze():
    # A missing baseline is a failure here, including in CI. Never report PASS
    # based on a shallow checkout that silently bypassed the comparison.
    path = "app/src/main/java/io/github/asteroidb612zs/hondatadash/MainActivity.java"
    old = subprocess.check_output(["git", "show", BASELINE + ":" + path], cwd=ROOT, text=True)
    new = (ROOT / path).read_text()
    for signature in ("private void applyOemPalette(", "private void setConnectionStatus("):
        old = old.replace(reg.method(old, signature), signature + "APPROVED_PRESENTATION_BODY")
        new = new.replace(reg.method(new, signature), signature + "APPROVED_PRESENTATION_BODY")
    assert old == new, "MainActivity changed outside the two reviewed presentation methods"
    protected = [
        "app/src/main/java/io/github/asteroidb612zs/hondatadash/data",
        "app/src/main/res", "app/src/main/AndroidManifest.xml", "app/proguard-rules.pro",
    ] + ["app/src/main/java/io/github/asteroidb612zs/hondatadash/" + name for name in (
        "ColorRecovery.java", "StartupSequence.java", "DashboardPalette.java",
        "TextFitGeometry.java", "ShiftLightView.java", "ShiftLightRenderer.java",
    )]
    changed = subprocess.check_output(["git", "diff", BASELINE, "--", *protected], cwd=ROOT, text=True)
    assert not changed, "Frozen semantics, layout, palette, alarm/RPM/startup clocks changed"
    print("PASS: visual.3 full MainActivity freeze except two presentation setters; data/resources/clocks identical")


PROBE = r'''package io.github.asteroidb612zs.hondatadash;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
public class RefinementProbe {
 static int checks;
 static void check(boolean p,String s){if(!p)throw new AssertionError(s);checks++;}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static float number(Object o,String n)throws Exception{return ((Number)field(o,n)).floatValue();}
 static Canvas render(FittedTextView v){Canvas c=new Canvas(v.getWidth(),v.getHeight());v.onDraw(c);return c;}
 public static void main(String[] args)throws Exception{
  Typeface.current=java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT,Paths.get(args[0]).toFile());
  Typeface.scale=Typeface.current;Context ctx=new Context(1,1);
  ScaleBarView b=new ScaleBarView(ctx);b.setStatic();b.setRange(0,100);b.setAnchor(0);
  b.layout(0,0,192,42);b.setValue(25);int before=b.invalidations;
  for(int i=0;i<1000;i++){SystemClock.testNow=1000+i*20;b.setValue(25);}
  check(b.invalidations==before,"1000 identical static samples must not invalidate");
  check(number(b,"curVal")==25,"static value truthful");
  b.setValue(30);check(b.invalidations==before+1&&number(b,"curVal")==30,"new sample applies immediately");
  b.setTargetTracking(true);before=b.invalidations;
  b.setTargetValue(35,DashboardPalette.AMBER);
  check(b.invalidations==before+1,"target independently repaints at unchanged actual");
  before=b.invalidations;b.setValue(Float.NaN);
  check(b.invalidations==before+1&&Float.isNaN(number(b,"curVal")),"valid to invalid immediately clears");
  before=b.invalidations;
  for(int i=0;i<1000;i++){b.setValue(Float.NaN);b.setValue(Float.POSITIVE_INFINITY);}
  check(b.invalidations==before,"repeated invalid samples do not repaint empty track");
  b.setValue(30);b.setMonitoringActive(false);b.setMonitoringActive(true);
  check(Float.isNaN(number(b,"curVal")),"monitoring cannot restore a stale sample");
  b.setValue(30);check(number(b,"curVal")==30,"fresh sample restores after monitoring gap");
  b.setMechanical(8,.7f,.7f);SystemClock.testNow=30000;b.setValue(10);
  SystemClock.testNow=30050;b.setValue(30);float position=number(b,"mechPosition");
  before=b.invalidations;SystemClock.testNow=30100;b.setValue(30);
  check(b.invalidations==before+1&&number(b,"mechPosition")!=position,"legacy dynamic profiles still advance");
  b.setValue(Float.NaN);
  for(String name:new String[]{"heatPos","heatNeg","peakPos","peakNeg","envHigh","envLow","mechVelocity","emotionCurrent"})
   check(number(b,name)==0,"invalid clears "+name);

  FittedTextView v=new FittedTextView(ctx);v.layout(0,0,145,114);v.setTextSize(106);
  String[] refs={"E99","--"};v.setFitReference(true,false,refs);v.setText("E25");
  Canvas initial=render(v);Object cachedRefs=field(v,"references");Object digit=((Map<?,?>)field(v,"glyphs")).get('2');
  before=v.invalidations;
  for(int i=0;i<1000;i++)v.setFitReference(true,false,new String[]{"E99","--"});
  check(v.invalidations==before&&field(v,"references")==cachedRefs,"identical profiles reuse copied references");
  Canvas again=render(v);
  check(((Map<?,?>)field(v,"glyphs")).get('2')==digit,"identical profile preserves measured glyph cache");
  check(initial.boxes.equals(again.boxes),"no-op profile preserves exact glyph bounds");
  refs[0]="E888";check(((String[])field(v,"references"))[0].equals("E99"),"caller cannot mutate cached profile");
  v.setFitReference(true,false,refs);check(v.invalidations==before+1,"changed reference invalidates");
  render(v);check(((Map<?,?>)field(v,"glyphs")).get('2')!=digit,"changed reference remeasures");
  v.setFitReference(false,false,"DFCO","SYNC");v.setTextSize(80);v.setText("SYNC");render(v);
  v.setFitReference(true,false,"E99","--");v.setTextSize(106);v.setText("E25");
  check(render(v).boxes.equals(initial.boxes),"semantic to numeric restores exact bounds");
  v.setFitReference(false,false,(String[])null);before=v.invalidations;
  v.setFitReference(false,false,(String[])null);check(v.invalidations==before,"null profile is idempotent");
  System.out.println("PASS: "+checks+" actual FittedTextView/ScaleBarView cache, redraw and transition checks (JVM doubles)");
 }
}'''

MOTION = r'''public class MotionProbe {
 static class Context { Object getContentResolver(){return null;} }
 Context getContext(){return new Context();}
 static class Settings { static class Global {
  static final String ANIMATOR_DURATION_SCALE="animator_duration_scale";
  static float scale=1;static boolean denied;
  static float getFloat(Object resolver,String name,float fallback){if(denied)throw new SecurityException();return scale;}
 }}
 METHOD
 public static void main(String[] args){MotionProbe p=new MotionProbe();
  if(!p.animationsEnabled())throw new AssertionError("default startup lost");
  Settings.Global.scale=0;if(p.animationsEnabled())throw new AssertionError("disabled animation must skip");
  Settings.Global.scale=.5f;if(!p.animationsEnabled())throw new AssertionError("nonzero preference remains enabled");
  Settings.Global.denied=true;if(!p.animationsEnabled())throw new AssertionError("OEM denial fallback");
  System.out.println("PASS: startup animation-disable preference and restricted OEM fallback");
 }
}'''


def main():
    freeze()
    # The path is explicit so a missing pinned asset cannot silently use a system font.
    font = ROOT / "app/src/main/assets/fonts/RobotoCondensed-BoldItalic.ttf"
    assert font.is_file(), "Restore the pinned production BoldItalic font before running"
    stubs = dict(fit.STUBS)
    stubs["android/view/View.java"] = stubs["android/view/View.java"].replace(
        "public void invalidate(){}", "public int invalidations;public void invalidate(){invalidations++;}")
    stubs["android/os/SystemClock.java"] = reg.STUBS["android/os/SystemClock.java"]
    overlay = (JAVA / "StartupOverlayView.java").read_text()
    assert "getFontMetrics(valueMetrics)" in overlay and "paint.getFontMetrics()" not in overlay
    assert "if (!animationsEnabled()) { finish(); return; }" in reg.method(overlay, "public void start(")
    assert "if (placeholderAlpha <= 0f) continue;" in overlay
    with tempfile.TemporaryDirectory(prefix="hondata-refinement-") as tmp:
        temp = Path(tmp)
        for name, source in stubs.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
        (temp / "RefinementProbe.java").write_text(PROBE)
        (temp / "MotionProbe.java").write_text(MOTION.replace("METHOD", reg.method(overlay, "private boolean animationsEnabled(")))
        sources = list(temp.rglob("*.java")) + [JAVA / n for n in (
            "FittedTextView.java", "TextFitGeometry.java", "DashboardTypeface.java", "ScaleBarView.java", "DashboardPalette.java")]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11", "-d", str(temp / "classes"),
                        *map(str, sources)], check=True)
        for name, args in (("io.github.asteroidb612zs.hondatadash.RefinementProbe", [str(font)]), ("MotionProbe", [])):
            subprocess.run(["java", "-Djava.awt.headless=true", "-cp", str(temp / "classes"), name, *args], check=True)


if __name__ == "__main__":
    main()
