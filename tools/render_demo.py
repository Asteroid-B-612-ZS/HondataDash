#!/usr/bin/env python3
"""Render an 800x480 demo from the production XML and Java custom Views.

This is a source-driven visual reconstruction using AWT and supplied fonts,
not an Android emulator or Bluetooth/ECU recording. App source is untouched.
"""
from pathlib import Path
import argparse
import importlib.util
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


fit = load("text_fit", ROOT / "tests/verify_text_fit.py")
regression = load("regression", ROOT / "tests/verify_refinement.py")

CANVAS = r'''package android.graphics;
import java.awt.*;
import java.awt.geom.*;
public class Canvas {
 public final Graphics2D g;private final java.util.List<AffineTransform> transforms=new java.util.ArrayList<>();
 public Canvas(Graphics2D graphics){g=graphics;}
 public int save(){transforms.add(g.getTransform());return transforms.size();}
 public void restoreToCount(int n){while(transforms.size()>=n)g.setTransform(transforms.remove(transforms.size()-1));}
 public void translate(float x,float y){g.translate(x,y);}public void scale(float x,float y){g.scale(x,y);}
 private void paint(Paint p){g.setColor(new java.awt.Color(p.getColor(),true));
 if(p.shader instanceof LinearGradient){LinearGradient s=(LinearGradient)p.shader;
 if(s.multiColors!=null&&s.multiColors.length>1){
  java.awt.Color[] cols=new java.awt.Color[s.multiColors.length];
  for(int i=0;i<cols.length;i++)cols[i]=new java.awt.Color(s.multiColors[i],true);
  g.setPaint(new LinearGradientPaint(s.x0,s.y0,s.x1,s.y1,s.multiPos,cols));
 }else g.setPaint(new GradientPaint(s.x0,s.y0,new java.awt.Color(s.c0,true),s.x1,s.y1,new java.awt.Color(s.c1,true)));}}
 public void drawText(String t,float x,float y,Paint p){paint(p);AffineTransform at=AffineTransform.getTranslateInstance(x,y);g.fill(at.createTransformedShape(p.shape(t)));}
 public void drawRect(float l,float t,float r,float b,Paint p){paint(p);g.fill(new Rectangle2D.Float(l,t,r-l,b-t));}
 public void drawLine(float x0,float y0,float x1,float y1,Paint p){paint(p);g.setStroke(new BasicStroke(p.getStrokeWidth(),BasicStroke.CAP_BUTT,BasicStroke.JOIN_MITER));g.draw(new Line2D.Float(x0,y0,x1,y1));}
 public void drawRoundRect(RectF r,float rx,float ry,Paint p){paint(p);Shape s=new RoundRectangle2D.Float(r.l,r.t,r.r-r.l,r.b-r.t,rx*2,ry*2);
 if(p.getStyle()==Paint.Style.STROKE){g.setStroke(new BasicStroke(p.getStrokeWidth()));g.draw(s);}else g.fill(s);}
 public void drawPath(Path path,Paint p){paint(p);if(p.getStyle()==Paint.Style.STROKE){g.setStroke(new BasicStroke(p.getStrokeWidth()));g.draw(path.path);}else g.fill(path.path);}
}
'''


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--font-bold", required=True, type=Path)
    p.add_argument("--font-italic", required=True, type=Path)
    p.add_argument("--font-scale", type=Path, default=ROOT / "app/src/main/assets/fonts/RobotoCondensed-Regular.ttf")
    p.add_argument("--output", required=True, type=Path)
    p.add_argument("--scenario", choices=("drive", "boundary", "startup", "winter"), default="drive")
    p.add_argument("--stills", action="store_true", help="Only retain selected PNG frames")
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    original = fit.resolve
    elements = []
    row_bounds = {}
    colors = {n.attrib["name"]: n.text for n in ET.parse(fit.RES / "values/colors.xml").getroot()}

    def color(value):
        return colors.get(value.split("/")[-1], value).removeprefix("#")

    def resolve(node, x, y, w, h, card, screen, slots):
        a = fit.attributes(node)
        if a.get("visibility") == "gone":
            return
        ident = a.get("id", "").split("/")[-1]
        if ident in ("mainRowOne", "mainRowTwo", "bottomRow"):
            row_bounds[ident] = (x, y, w, h)
        if ident == "statusDot":
            # Parent is center_vertical; the font-only resolver does not need that
            # cross-axis alignment, but this complete composition does.
            y += (fit.dimension("@dimen/header_height") - h) // 2
        if "background" in a:
            bg = a["background"]
            elements.append(["background", bg, x, y, w, h])
        if node.tag.endswith("ShiftLightView"):
            elements.append(["shift", "", x, y, w, h])
            return
        if node.tag.endswith("HondaBrandView"):
            elements.append(["brand", "", x, y, w, h])
            return
        if node.tag.endswith("MiniIconView"):
            elements.append(["icon", a.get("tag", "cyl"), x, y, w, h])
            return
        before = len(slots)
        original(node, x, y, w, h, card, screen, slots)
        if node.tag.endswith("DashboardGridLayout"):
            bottom = row_bounds["bottomRow"]
            elements.append(["outline", "", *bottom])
        if node.tag == fit.FITTED or node.tag.endswith("ScaleBarView"):
            assert len(slots) == before + 1
            row = slots[-1]
            if row[2] in ("highLabel", "lowLabel") and row[3] >= 5:
                row[9] = "HI" if row[2] == "highLabel" else "LO"
            elements.append(["slot", *row[1:], color(a.get("textColor", "#FFE8EEF2")),
                             "italic" if "italic" in a.get("textStyle", "") else "bold"])

    fit.resolve = resolve
    tree = ET.parse(fit.RES / "layout/activity_main.xml").getroot()
    for n in tree.iter():
        if n.attrib.get(fit.NS + "id") == "@+id/sourceName":
            n.set(fit.NS + "visibility", "visible")
            n.set(fit.NS + "text", "TYPE TEST" if args.scenario == "boundary" else "DEMO")
    slots = []
    resolve(tree, 0, 0, 800, 480, -1, "800x480", slots)
    # The real Activity tightens each title label to its measured ink before first layout.
    # The XML-only preview cannot execute that Activity code, so mirror the 800x480
    # optical result here strictly for the source-driven preview.
    label_widths = [60, 24, 20, 47, 30, 22, 26, 48]
    label_x = {}
    for e in elements:
        if e[0] == "slot" and e[2] == "labelEn":
            label_x[int(e[3])] = int(e[4])
    for e in elements:
        if e[0] == "slot" and e[2] == "unit":
            card = int(e[3])
            if card in label_x:
                e[4] = label_x[card] + label_widths[card] + 6
        if e[0] == "background" and e[1].startswith("@color/"):
            e[1] = color(e[1])

    source = (fit.JAVA / "MainActivity.java").read_text()
    signatures = ["private void configureScaleBar(", "private void updateEmotion(",
                  "private static void addTrimScaleZones(",
                  "private String fmt1NoSign(", "private String fmtSigned1(", "private String fmtSigned2(",
                  "private String formatMainText(", "private String formatExtremeText(",
                  "private int getTrimSemanticColor(", "private int getIgnSemanticColor(",
                  "private int getMapSemanticColor(", "private int getAfColorByLambda(",
                  "private int getEthanolColor(", "private int getEctColor(", "private int getIatColor(",
                  "private float ema(", "private float boostFilter("]
    methods = "\n".join(regression.method(source, s) for s in signatures)
    const_names = ["COLOR_TEXT_NORMAL", "COLOR_INFO_BLUE", "COLOR_SAFE", "COLOR_WARN", "COLOR_DANGER", "TRIM_GREEN_ABS_MAX", "TRIM_WARN_ABS_MAX",
                   "IGN_GREEN_MIN", "IGN_WARN_MIN", "MAP_GREEN_MAX", "MAP_WARN_MAX", "WOT_LAMBDA_DANGER_LEAN",
                   "WOT_LAMBDA_WARN_LEAN", "WOT_LAMBDA_WARN_RICH", "CL_LAMBDA_ERR_GREEN", "CL_LAMBDA_ERR_WARN"]
    constants = "\n".join(re.search(r"private static final (?:float|int) " + name + r"\s*=.*?;", source).group(0)
                          for name in const_names)
    renderer = (ROOT / "tools/preview/AnimationRenderer.java").read_text().replace("// PRODUCTION_METHODS", methods).replace("// PRODUCTION_CONSTANTS", constants)
    stubs = dict(fit.STUBS)
    stubs["android/graphics/Canvas.java"] = CANVAS
    stubs["android/graphics/RectF.java"] = "package android.graphics; public class RectF {public float l,t,r,b;public RectF(){}public RectF(float a,float c,float d,float e){set(a,c,d,e);}public void set(float a,float c,float d,float e){l=a;t=c;r=d;b=e;}}"
    stubs["android/graphics/Path.java"] = """package android.graphics;public class Path {
public java.awt.geom.Path2D.Float path=new java.awt.geom.Path2D.Float();public void moveTo(float x,float y){path.moveTo(x,y);}
public void lineTo(float x,float y){path.lineTo(x,y);}public void cubicTo(float a,float b,float c,float d,float e,float f){path.curveTo(a,b,c,d,e,f);}public void close(){path.closePath();}}"""
    stubs["android/graphics/LinearGradient.java"] = """package android.graphics; public class LinearGradient extends Shader {
public float x0,y0,x1,y1; public int c0,c1; public int[] multiColors; public float[] multiPos;
public LinearGradient(float a,float b,float c,float d,int e,int f,TileMode t){x0=a;y0=b;x1=c;y1=d;c0=e;c1=f;}
public LinearGradient(float a,float b,float c,float d,int[] e,float[] f,TileMode t){x0=a;y0=b;x1=c;y1=d;multiColors=e;multiPos=f;}}"""
    stubs["android/graphics/Paint.java"] = stubs["android/graphics/Paint.java"].replace(
        "public void setShader(Object s){}", "public Object shader;public void setShader(Object s){shader=s;}")
    stubs["android/graphics/Paint.java"] = stubs["android/graphics/Paint.java"].replace(
        "public void setStyle(Style s){}", "private Style style=Style.FILL;public void setStyle(Style s){style=s;}public Style getStyle(){return style;}")
    stubs["android/view/View.java"] = stubs["android/view/View.java"].replace(
        "protected void onDraw(android.graphics.Canvas c){}", "protected void onDraw(android.graphics.Canvas c){} protected void onDetachedFromWindow(){}")
    for name in ("android/os/Looper.java", "android/os/Handler.java", "android/os/SystemClock.java"):
        stubs[name] = regression.STUBS[name]
    with tempfile.TemporaryDirectory(prefix="hondata-demo-") as tmp:
        temp = Path(tmp)
        for name, content in stubs.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        (temp / "AnimationRenderer.java").write_text(renderer)
        (temp / "elements.tsv").write_text("\n".join("\t".join(map(str, e)) for e in elements))
        sources = list(temp.rglob("*.java")) + [fit.JAVA / n for n in
            ["FittedTextView.java", "TextFitGeometry.java", "ScaleBarView.java", "ShiftLightView.java",
             "ColorRecovery.java", "data/EngineSemanticState.java", "DashboardTypeface.java", "DashboardPalette.java", "ShiftLightRenderer.java",
             "HondaMark.java", "HondaBrandView.java", "StartupSequence.java", "StartupBrandRenderer.java"]]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11", "-d", str(temp / "classes"), *map(str, sources)], check=True)
        version = re.search(r'versionName "([^"]+)"', (ROOT / "app/build.gradle").read_text()).group(1)
        output = args.output / f"HondataDash-v{version}-{args.scenario}-800x480.mp4"
        assert not output.exists(), "Choose a new output directory instead of overwriting a delivered movie"
        encoder = None if args.stills else subprocess.Popen(["ffmpeg", "-hide_banner", "-loglevel", "error", "-f", "rawvideo", "-pixel_format", "bgr24",
            "-video_size", "800x480", "-framerate", "50", "-i", "pipe:0", "-an", "-c:v", "libx264", "-preset", "fast",
            "-crf", "17", "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-metadata",
            "comment=Synthetic source-driven visual reconstruction, not an Android or vehicle recording", str(output)], stdin=subprocess.PIPE)
        render = subprocess.Popen(["java", "-Xmx512m", "-Djava.awt.headless=true", "-cp", str(temp / "classes"),
            "io.github.asteroidb612zs.hondatadash.AnimationRenderer", str(temp / "elements.tsv"), str(args.font_bold), str(args.font_italic),
            str(args.output), args.scenario, str(args.stills).lower(), str(args.font_scale)], stdout=encoder.stdin if encoder else subprocess.DEVNULL)
        if encoder: encoder.stdin.close()
        assert render.wait() == 0, "renderer failed"
        if encoder: assert encoder.wait() == 0, "encoder failed"
    print(args.output if args.stills else output)


if __name__ == "__main__":
    main()
