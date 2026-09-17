#!/usr/bin/env python3
"""Production scale/LED geometry and color-policy regressions using JVM doubles.

No ECU, real Android rasterizer or Bluetooth hardware is simulated by this test.
Requires Python 3 and a JDK with java.desktop. No extra Python packages.
"""
import argparse
import importlib.util
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


fit = load("fit", ROOT / "tests/verify_text_fit.py")
reg = load("reg", ROOT / "tests/verify_refinement.py")

SHAPES = r'''
public final java.util.List<float[]> shapes=new java.util.ArrayList<>();
public final java.util.List<Integer> colors=new java.util.ArrayList<>();
private void shape(float l,float t,float r,float b,float rx,float ry,Paint p){
 if(!Float.isFinite(l)||!Float.isFinite(t)||!Float.isFinite(r)||!Float.isFinite(b)
 ||l<-.01||t<-.01||r>width+.01||b>height+.01||r<l||b<t)
 throw new AssertionError("shape outside "+width+"x"+height+" : "+java.util.Arrays.toString(new float[]{l,t,r,b}));
 shapes.add(new float[]{l,t,r,b,rx,ry});colors.add(p.getColor());
}
public void drawRect(float l,float t,float r,float b,Paint p){shape(l,t,r,b,0,0,p);}
public void drawRoundRect(RectF r,float rx,float ry,Paint p){shape(r.l,r.t,r.r,r.b,rx,ry,p);}
public void drawLine(float a,float b,float c,float d,Paint p){shape(Math.min(a,c),Math.min(b,d),Math.max(a,c),Math.max(b,d),0,0,p);}
'''

PROBE = r'''package io.github.asteroidb612zs.hondatadash;
import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import java.awt.Font;
import java.nio.file.*;
import java.lang.reflect.*;
import java.util.*;
public class InstrumentProbe {
 static int checks,draws; static long clock=1000;
 static void check(boolean c,String s){if(!c)throw new AssertionError(s);checks++;}
 static Object field(Object o,String n)throws Exception{Field f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}
 static float number(Object o,String n)throws Exception{return ((Number)field(o,n)).floatValue();}
 static Canvas scale(ScaleBarView b){Canvas c=new Canvas(b.getWidth(),b.getHeight());b.onDraw(c);draws++;return c;}
 static int cursor(Canvas c,float height){int found=-1;for(int j=0;j<c.shapes.size();j++)
  if(c.colors.get(j)==0xfff4f8f8&&Math.abs(c.shapes.get(j)[3]-c.shapes.get(j)[1]-height)<.01)found=j;return found;}
 static float expectedX(int card,float value,float left,float width,float min,float max){
  float v=Math.max(min,Math.min(max,value));
  float start=card==0?0:0,end=card==0?40:1.5f,factor=2;
  if(card!=0&&card!=4)return left+(v-min)/(max-min)*width;
  float before=Math.min(v,start)-min;
  float enlarged=Math.max(0,Math.min(v,end)-start)*factor;
  float after=Math.max(0,v-end);
  return left+(before+enlarged+after)/((start-min)+(end-start)*factor+(max-end))*width;
 }
 static void scaleChecks()throws Exception{
  String[][] expected={{"0","20","40","60","80","100"},{"20","40","60","80","100","120"},
   {"−20","0","20","40","60","80"},{"−25","−15","0","+15","+25"},
   {"−1.0","0","+0.5","+1.0","+2.0"},{"7","10","12","14","16","18","20"},
   {"−40","−20","0","+20","+40"},{"−25","−15","0","+15","+25"}};
  for(float d:new float[]{.75f,1,1.25f,1.5f,2})for(int logicalWidth:new int[]{180,192})for(int card=0;card<8;card++){
   ScaleBarView b=new ScaleBarView(new Context(d,1));configure(card,b);
   b.layout(0,0,Math.round(logicalWidth*d),Math.round(42*d));int pad=Math.round(3*d);b.setPadding(pad,0,pad,0);
   float min=number(b,"minVal"),max=number(b,"maxVal"),anchor=number(b,"anchorVal"),height=Math.round(18*d);
   Canvas before=scale(b);
   check(!before.texts.isEmpty() && before.texts.get(0).equals(expected[card][0])
       && before.texts.get(before.texts.size()-1).equals(expected[card][expected[card].length-1]),
       "scale endpoints retained "+card+" "+d+" "+logicalWidth+" "+before.texts);
   if(d>=1f && logicalWidth>=192) check(before.texts.equals(Arrays.asList(expected[card])),
       "full production labels at nominal-or-larger geometry "+card+" "+d+" "+logicalWidth+" "+before.texts);
   for(int i=0;i<before.boxes.size();i++)for(int j=i+1;j<before.boxes.size();j++)check(!before.boxes.get(i).intersects(before.boxes.get(j)),"scale text collision");
   for(float v:new float[]{min-(max-min)*.2f,min,min+(max-min)*.1f,min+(max-min)*.2f,anchor,max,max+(max-min)*.2f}){
    SystemClock.testNow=clock+=50;b.setValue(v);Canvas c=scale(b);int ci=cursor(c,height);
    check(ci>=0,"current cursor must exist");float stroke=Math.max(3,Math.round(3*d));
    float x=expectedX(card,v,pad,b.getWidth()-2*pad,min,max);
    float px=Math.max(pad,Math.min(b.getWidth()-pad-stroke,Math.round(x-stroke/2)));
    check(Math.abs(c.shapes.get(ci)[0]-px)<.01,"live cursor follows value, not spring "+card+" "+v);
    check(c.shapes.get(ci)[2]-c.shapes.get(ci)[0]==stroke,"cursor width");
    int after=c.shapes.size()-ci-1;check(after==(v<min||v>max?7:0),"only overflow chevron follows cursor");
    check(c.texts.equals(before.texts),"labels stable across updates");
   }
   for(float invalid:new float[]{Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY}){
    b.setValue(invalid);check(cursor(scale(b),height)<0,"invalid cannot show live bar");
    for(String name:new String[]{"heatPos","heatNeg","peakPos","peakNeg","envHigh","envLow","mechVelocity","emotionCurrent"})check(number(b,name)==0,"invalid clears "+name);
   }
   b.setValue(max);b.setMonitoringActive(false);check(cursor(scale(b),height)<0,"stale cursor cleared");
   b.setMonitoringActive(true);check(cursor(scale(b),height)<0,"reenable cannot revive old sample");
   b.setValue(anchor);check(cursor(scale(b),height)>=0,"new sample restores live bar");
   b.layout(0,0,Math.round((logicalWidth+8)*d),Math.round(42*d));Canvas resized=scale(b);
   check(!resized.texts.isEmpty() && resized.texts.get(0).equals(expected[card][0])
       && resized.texts.get(resized.texts.size()-1).equals(expected[card][expected[card].length-1]),
       "resize retains scale endpoints");
   check(resized.texts.size()>=before.texts.size(),"wider resize may restore, never lose, labels");
  }
 }
 static void ledChecks()throws Exception{
  for(float d:new float[]{.75f,1,1.25f,1.5f,2})for(int logicalWidth:new int[]{400,427,449,500,640,792,800}){
   ShiftLightView s=new ShiftLightView(new Context(d,1));s.layout(0,0,Math.round(logicalWidth*d),Math.round(42*d));
   s.setMonitoringActive(true);
   for(float shift:new float[]{5500,7000}){
    s.setShiftRpm(shift);
    java.util.List<Float> samples=new ArrayList<Float>();samples.add(0f);
    for(float ratio:ShiftLightView.STAGE_RATIOS){samples.add((float)Math.round(shift*ratio)-1f);samples.add((float)Math.round(shift*ratio));}
    float t3=(float)Math.round(shift*ShiftLightView.STAGE_RATIOS[2]);
    samples.add(t3-10f);samples.add(t3+5f);samples.add(t3-3f);samples.add(t3+2f);
    // Explicitly exercise the wider flash-exit hysteresis: this is below the
    // stage-5 fall threshold but must still visually retain stage 5 while latched.
    samples.add(shift-Math.max(60f,shift*.015f)-5f);
    samples.add(shift-Math.max(120f,shift*.025f)+5f);
    samples.add(shift-Math.max(120f,shift*.025f)-5f);
    for(float ratio:ShiftLightView.STAGE_RATIOS)samples.add((float)Math.round(shift*ratio)-1f);
    int expStage=0; boolean expFlash=false;
    for(float rpm:samples){
     s.setRpm(rpm);
     if(rpm>=shift) expFlash=true;
     else if(expFlash && rpm<shift-Math.max(120f,shift*.025f)) expFlash=false;
     if(expFlash) expStage=5;
     else {
      while(expStage<5&&rpm>=ShiftLightView.stageThreshold(expStage,shift))expStage++;
      while(expStage>0&&rpm<ShiftLightView.stageThreshold(expStage-1,shift)-Math.max(60f,shift*.015f))expStage--;
     }
     Canvas c=new Canvas(s.getWidth(),s.getHeight());s.onDraw(c);draws++;
     int lit=0,bezels=0,lamp=0;Float firstX=null,lastRight=null,priorX=null,priorD=null;Integer observedGap=null;
     for(int i=0;i<c.shapes.size();i++){
      int color=c.colors.get(i);float[] box=c.shapes.get(i);
      for(int token:DashboardPalette.RPM_PAIR)if(color==token)lit++;
      if(color==0xff78909a||color==0xff30434d){
       bezels++;lamp++;float width=box[2]-box[0],height=box[3]-box[1];
       check(width>height,"FL5 lamp is a horizontal capsule");
       check(Math.abs(box[4]-height/2f)<.01,"capsule end radius");
       check(width<=Math.round(ShiftLightRenderer.LAMP_WIDTH_DP*d)+1,"lamp width capped");
       check(height<=Math.round(ShiftLightRenderer.LAMP_HEIGHT_DP*d)+1,"lamp height capped");
       check(box[0]==Math.round(box[0]),"pixel-aligned lamp origin");
       if(firstX==null)firstX=box[0]; lastRight=box[2];
       if(priorX!=null){
        check(Math.abs(width-priorD)<.01,"equal lamp widths");
        int gap=Math.round(box[0]-priorX-priorD);
        if(lamp==6){
         check(observedGap!=null,"ordinary gap observed before centre");
         check(gap>=observedGap*5,"centre gap is at least five ordinary gaps");
         if(logicalWidth>=500) check(gap==Math.max(Math.max(4,Math.round(ShiftLightRenderer.NORMAL_GAP_DP*d))*5,
                 Math.round(ShiftLightRenderer.CENTER_GAP_DP*d)),"nominal centre gap retained on full-width header");
        } else {
         if(observedGap==null) observedGap=gap;
         check(gap==observedGap,"equal normal lamp gaps");
        }
       }
       priorX=box[0];priorD=width;
      }
     }
     check(bezels==10,"ten independent capsule lamp bezels");
     check(Math.abs((firstX+lastRight)/2f-s.getWidth()/2f)<=1f,"rev bank globally centred in full header width");
     if(logicalWidth>=792) {
      check((lastRight-firstX)/d>=400f,"800px-class header uses a broad OEM capsule span");
     }
     check(lit==2*expStage,"outside-in stage count "+rpm+" "+shift+" lit "+lit+" stage "+expStage);
     check(((Integer)field(s,"currentStage"))==expStage,"stateful stage matches visual stage");
     check(((Boolean)field(s,"flashing"))==expFlash,"flash latch hysteresis state");
    }
   }
   s.setShiftRpm(5500);s.setRpm(5500);
   ((Runnable)field(s,"flashTick")).run();
   check(!(Boolean)field(s,"flashOn"),"red dark phase");
   Canvas dim=new Canvas(s.getWidth(),s.getHeight());s.onDraw(dim);
   int bright=0;for(int color:dim.colors)for(int token:DashboardPalette.RPM_PAIR)if(color==token)bright++;
   check(bright==8,"only the central red pair flashes; the outer eight stay steady");
   check(ShiftLightView.FLASH_MS==100,"5 Hz full bright/dim cycle");
   s.setMonitoringActive(false);Canvas off=new Canvas(s.getWidth(),s.getHeight());s.onDraw(off);
   for(int color:off.colors)for(int token:DashboardPalette.RPM_PAIR)check(color!=token,"stale LEDs unlit");
   check(!(Boolean)field(s,"flashing"),"stale stops flashing");
   s.setMonitoringActive(true);s.setRpm(5500);s.setRpm(Float.NaN);
   check(!(Boolean)field(s,"flashing"),"invalid RPM stops flashing");
   s.setRpm(5500);s.onDetachedFromWindow();check(!(Boolean)field(s,"flashing"),"detach cancels flashing");
  }
 }
 public static void main(String[] args)throws Exception{
  Typeface.current=Font.createFont(Font.TRUETYPE_FONT,Paths.get(args[0]).toFile());
  Typeface.scale=Typeface.current;scaleChecks();ledChecks();
  System.out.println("PASS: "+checks+" scale/LED assertions across "+draws+" production draws (5 densities; no hardware rasterization)");
 }
 CONFIGURE
}
'''

COLOR = r'''package io.github.asteroidb612zs.hondatadash;
public class ColorPolicyProbe {
 CONSTANTS
 METHODS
 public static void main(String[] args){ColorPolicyProbe p=new ColorPolicyProbe();int checks=0;
  if(p.getEctColor(Float.NaN)!=0xffff4444||p.getIatColor(Float.NaN)!=0xffff4444||p.getEthanolColor(Float.NaN)!=0xffff4444)throw new AssertionError("invalid-value fallback");checks+=3;
  for(int n=-2000;n<=13000;n++){float v=n/100f;
   int ect=v<65?0xff00d8ff:v<=96?0xff27dce6:v<=102?0xffd29922:v<=108?0xffff4444:0xffb040ff;
   int iat=v<10?0xff27dce6:v<45?0xffe8eef2:v<55?0xffd29922:v<65?0xffff4444:0xffb040ff;
   int eth=v<20?0xffe8eef2:v<=50?0xff70dd48:v<=85?0xff27dce6:0xffd29922;
   if(p.getEctColor(v)!=ect||p.getIatColor(v)!=iat||p.getEthanolColor(v)!=eth)throw new AssertionError("temperature/ethanol policy "+v);
   checks+=3;
  }
  for(int n=-3000;n<=5500;n++){float v=n/100f;
   if(p.getTrimSemanticColor(v)!=(Math.abs(v)<=5?0xff3fb950:Math.abs(v)<=15?0xffd29922:0xffff4444))throw new AssertionError("trim boundary");
   if(p.getIgnSemanticColor(v)!=(v>=0?0xff3fb950:v>=-5?0xffd29922:0xffff4444))throw new AssertionError("IGN boundary");checks+=2;
  }
  for(int n=-1500;n<=3000;n++){float v=n/1000f;
   if(p.getMapSemanticColor(v)!=(v<=1.45f?0xff3fb950:v<=1.60f?0xffd29922:0xffff4444))throw new AssertionError("MAP boundary");checks++;
  }
  System.out.println("PASS: "+checks+" production color-policy comparisons (RC5 thermal/fuel policy + unchanged trim/IGN/MAP safety boundaries)");
 }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", type=Path, help="Regular condensed TTF used by the desktop geometry probe")
    parser.add_argument("--fonts", type=Path, nargs="+", help="Compatibility form; the *Regular.ttf entry is selected")
    args = parser.parse_args()
    default_font = ROOT / "app/src/main/assets/fonts/RobotoCondensed-Regular.ttf"
    font_path = args.font
    if font_path is None and args.fonts:
        font_path = next((f for f in args.fonts if f.name.endswith("-Regular.ttf")), args.fonts[0])
    if font_path is None:
        font_path = default_font
    if not font_path.is_file():
        raise SystemExit("Scale/LED geometry probe needs a regular TTF. Pass --font PATH or --fonts ...; font binaries are not redistributed in this source handoff.")

    main_source = (fit.JAVA / "MainActivity.java").read_text()
    start = main_source.index("        switch (i) {", main_source.index("private void configureScaleBar("))
    end = main_source.index("\n    @Override", start)
    configure = "static void configure(int i,ScaleBarView bar) {\n" + main_source[start:end].strip()
    configure += "\n" + fit.trim_zone_method(main_source)
    stubs = dict(fit.STUBS)
    canvas = stubs["android/graphics/Canvas.java"]
    canvas = canvas.replace("public void drawRect(float a,float b,float c,float d,Paint p){} public void drawLine(float a,float b,float c,float d,Paint p){}", SHAPES)
    stubs["android/graphics/Canvas.java"] = canvas
    stubs["android/graphics/RectF.java"] = "package android.graphics; public class RectF {public float l,t,r,b;public void set(float a,float c,float d,float e){l=a;t=c;r=d;b=e;}}"
    stubs["android/view/View.java"] = stubs["android/view/View.java"].replace("protected void onDraw(android.graphics.Canvas c){}", "protected void onDraw(android.graphics.Canvas c){} protected void onDetachedFromWindow(){}")
    for name in ("android/os/Looper.java", "android/os/Handler.java", "android/os/SystemClock.java"):
        stubs[name] = reg.STUBS[name]
    names = ("COLOR_TEXT_NORMAL", "COLOR_INFO_BLUE", "COLOR_SAFE", "COLOR_WARN", "COLOR_DANGER", "TRIM_GREEN_ABS_MAX", "TRIM_WARN_ABS_MAX", "IGN_GREEN_MIN", "IGN_WARN_MIN", "MAP_GREEN_MAX", "MAP_WARN_MAX")
    constants = "\n".join(re.search(r"private static final (?:float|int) " + n + r"\s*=.*?;", main_source).group(0) for n in names)
    methods = "\n".join(reg.method(main_source, "private int " + n + "(") for n in ("getEthanolColor", "getEctColor", "getIatColor", "getTrimSemanticColor", "getIgnSemanticColor", "getMapSemanticColor"))
    with tempfile.TemporaryDirectory(prefix="hondata-instruments-") as tmp:
        temp = Path(tmp)
        for name, content in stubs.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        (temp / "InstrumentProbe.java").write_text(PROBE.replace("CONFIGURE", configure))
        (temp / "ColorPolicyProbe.java").write_text(COLOR.replace("CONSTANTS", constants).replace("METHODS", methods))
        sources = list(temp.rglob("*.java")) + [fit.JAVA / n for n in ("ScaleBarView.java", "ShiftLightView.java", "ShiftLightRenderer.java", "DashboardPalette.java", "DashboardTypeface.java")]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11", "-d", str(temp / "classes"), *map(str, sources)], check=True)
        for name, probe_args in (("InstrumentProbe", [str(font_path)]), ("ColorPolicyProbe", [])):
            subprocess.run(["java", "-Djava.awt.headless=true", "-cp", str(temp / "classes"), "io.github.asteroidb612zs.hondatadash." + name, *probe_args], check=True)


if __name__ == "__main__":
    main()
