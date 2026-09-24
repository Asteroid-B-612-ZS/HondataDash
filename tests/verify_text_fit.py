#!/usr/bin/env python3
"""Run the production text renderer using AWT-backed Android test doubles.

This is a geometry/fallback-font regression, NOT Android rasterization/emulation.
All dashboard text slots are resolved from the production XML (including includes,
fixed dimensions, padding, margins, and sequential LinearLayout weight rounding).
Requires Python 3 and a JDK with java.desktop. Optional --output retains QA PNGs.
"""
from pathlib import Path
import argparse
import copy
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash"
RES = ROOT / "app/src/main/res"
NS = "{http://schemas.android.com/apk/res/android}"
FITTED = "io.github.asteroidb612zs.hondatadash.FittedTextView"

# Small test doubles, deliberately separate from app/src so they cannot enter an APK.
STUBS = {
"android/content/res/AssetManager.java": "package android.content.res; public class AssetManager {}",
"android/util/AttributeSet.java": "package android.util; public interface AttributeSet {}",
"android/util/DisplayMetrics.java": "package android.util; public class DisplayMetrics { public float density=1,scaledDensity=1; }",
"android/content/res/Resources.java": """package android.content.res;
public class Resources { public final android.util.DisplayMetrics m=new android.util.DisplayMetrics();
public android.util.DisplayMetrics getDisplayMetrics(){return m;} }""",
"android/content/Context.java": """package android.content;
public class Context { private final android.content.res.Resources r=new android.content.res.Resources();
public Context(float d,float f){r.m.density=d;r.m.scaledDensity=d*f;}
public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}
public android.content.res.Resources getResources(){return r;} }""",
"android/view/Gravity.java": """package android.view;
public class Gravity { public static final int LEFT=3,RIGHT=5,TOP=48,BOTTOM=80,
CENTER_HORIZONTAL=1,CENTER_VERTICAL=16,HORIZONTAL_GRAVITY_MASK=7,VERTICAL_GRAVITY_MASK=112;
public static int getAbsoluteGravity(int g,int d){return g;} }""",
"android/view/View.java": """package android.view;
public class View {
private final android.content.Context c; private int w,h,l,t,r,b;
public View(android.content.Context c){this.c=c;}
public View(android.content.Context c,android.util.AttributeSet a){this(c);}
public View(android.content.Context c,android.util.AttributeSet a,int d){this(c);}
public android.content.res.Resources getResources(){return c.getResources();}
public android.content.Context getContext(){return c;}
public void layout(int l,int t,int r,int b){w=r-l;h=b-t;}
public int getWidth(){return w;} public int getHeight(){return h;}
public int getPaddingLeft(){return l;} public int getPaddingTop(){return t;}
public int getPaddingRight(){return r;} public int getPaddingBottom(){return b;}
public void setPadding(int l,int t,int r,int b){this.l=l;this.t=t;this.r=r;this.b=b;}
public int getLayoutDirection(){return 0;} public void invalidate(){}
private int sx,sy;public void scrollTo(int x,int y){sx=x;sy=y;}
public int getScrollX(){return sx;}public int getScrollY(){return sy;}
protected void onDraw(android.graphics.Canvas c){} }
""",
"android/widget/TextView.java": """package android.widget;
public class TextView extends android.view.View {
private final android.graphics.Paint p=new android.graphics.Paint(1);
private CharSequence text=""; private int gravity=17,color=0xffe8eef2;
public TextView(android.content.Context c){super(c);}
public TextView(android.content.Context c,android.util.AttributeSet a){super(c,a);}
public TextView(android.content.Context c,android.util.AttributeSet a,int s){super(c,a,s);}
public void setSingleLine(boolean b){} public void setEllipsize(Object e){}
public void setHorizontallyScrolling(boolean b){}
public void setIncludeFontPadding(boolean b){} public void setText(CharSequence t){text=t;}
public CharSequence getText(){return text;} public android.graphics.Paint getPaint(){return p;}
public float getTextSize(){return p.getTextSize();}
public void setTextSize(float sp){p.setTextSize(sp*getResources().m.scaledDensity);}
public void setTypeface(android.graphics.Typeface t){p.setTypeface(t);}
public android.graphics.Typeface getTypeface(){return p.getTypeface();}
public int getGravity(){return gravity;} public void setGravity(int g){gravity=g;}
public int getCurrentTextColor(){return color;} public void setTextColor(int c){color=c;}
}
""",
"android/graphics/Typeface.java": """package android.graphics;
public class Typeface { public static final int NORMAL=0,BOLD=1,BOLD_ITALIC=3; public static java.awt.Font current,scale;
public final java.awt.Font font; public Typeface(java.awt.Font f){font=f;}
public boolean isItalic(){return font.isItalic();}
public static Typeface createFromAsset(android.content.res.AssetManager a,String p){return new Typeface(p.endsWith("-Regular.ttf")&&scale!=null?scale:current);}
public static Typeface create(String s,int style){return new Typeface(current);} }
""",
"android/graphics/Rect.java": "package android.graphics; public class Rect {public int left,top,right,bottom;}",
"android/graphics/Paint.java": """package android.graphics;
public class Paint {
public static final int ANTI_ALIAS_FLAG=1; public enum Align {LEFT,CENTER,RIGHT}
public enum Style {FILL,STROKE} public static class FontMetrics {public float top,bottom,ascent,descent;}
private Typeface face=new Typeface(Typeface.current); private float size=16,sx=1,skew,stroke=1;
private int color=0xffe8eef2;private boolean bold;
public Paint(int flags){} public void set(Paint p){face=p.face;size=p.size;sx=p.sx;skew=p.skew;bold=p.bold;color=p.color;}
public void setTextSize(float s){size=s;}public float getTextSize(){return size;}
public void setTextScaleX(float s){sx=s;}public float getTextScaleX(){return sx;}
public void setTypeface(Typeface f){face=f;} public Typeface getTypeface(){return face;}
public float getTextSkewX(){return skew;}public boolean isFakeBoldText(){return bold;}
public void setTextSkewX(float v){skew=v;}public void setFakeBoldText(boolean v){bold=v;}
public void setTextAlign(Align a){} public void setStyle(Style s){}
public void setStrokeWidth(float s){stroke=s;}public float getStrokeWidth(){return stroke;}
public void setShader(Object s){} public void setColor(int c){color=c;}public int getColor(){return color;}
public void setAlpha(int a){color=(color&0xffffff)|(a<<24);}
private final java.awt.font.FontRenderContext frc=new java.awt.font.FontRenderContext(null,true,true);
private java.awt.Font font(){return face.font.deriveFont(size);}
public java.awt.Shape shape(String s){return java.awt.geom.AffineTransform.getScaleInstance(sx,1)
.createTransformedShape(font().createGlyphVector(frc,s).getOutline());}
public float measureText(String s){java.awt.font.GlyphVector g=font().createGlyphVector(frc,s);
return (float)g.getGlyphPosition(g.getNumGlyphs()).getX()*sx;}
public void getTextBounds(String s,int start,int end,Rect r){java.awt.Rectangle b=shape(s.substring(start,end)).getBounds();
r.left=b.x;r.top=b.y;r.right=b.x+b.width;r.bottom=b.y+b.height;}
public FontMetrics getFontMetrics(){FontMetrics f=new FontMetrics();getFontMetrics(f);return f;}
public float getFontMetrics(FontMetrics f){java.awt.font.LineMetrics m=font().getLineMetrics("0123456789ABC",frc);
f.top=f.ascent=-m.getAscent();f.bottom=f.descent=m.getDescent();return f.bottom-f.top;}
}
""",
"android/graphics/Canvas.java": """package android.graphics;
public class Canvas {
private java.awt.geom.AffineTransform transform=new java.awt.geom.AffineTransform();
private final java.util.List<java.awt.geom.AffineTransform> saved=new java.util.ArrayList<>();
public final java.util.List<java.awt.geom.Rectangle2D> boxes=new java.util.ArrayList<>();
public final java.util.List<String> texts=new java.util.ArrayList<>();
public final java.util.List<java.awt.geom.AffineTransform> transforms=new java.util.ArrayList<>();
public java.awt.Graphics2D graphics; public float width,height;
public Canvas(float w,float h){width=w;height=h;}
public int save(){saved.add(new java.awt.geom.AffineTransform(transform));return saved.size();}
public void restoreToCount(int c){while(saved.size()>=c)transform=saved.remove(saved.size()-1);}
public void translate(float x,float y){transform.translate(x,y);} public void scale(float x,float y){transform.scale(x,y);}
public void drawText(String text,float x,float y,Paint paint){
java.awt.geom.AffineTransform at=new java.awt.geom.AffineTransform(transform);at.translate(x,y);
java.awt.Shape shape=at.createTransformedShape(paint.shape(text));java.awt.geom.Rectangle2D b=shape.getBounds2D();
if(!b.isEmpty()&&(b.getMinX() < -.01 || b.getMinY() < -.01 || b.getMaxX() > width+.01 || b.getMaxY() > height+.01))
throw new AssertionError("ink outside "+width+"x"+height+" : "+text+" "+b);
boxes.add(b);texts.add(text);transforms.add(new java.awt.geom.AffineTransform(transform));
if(graphics!=null){graphics.setColor(new java.awt.Color(paint.getColor(),true));graphics.fill(shape);}}
public void drawRect(float a,float b,float c,float d,Paint p){} public void drawLine(float a,float b,float c,float d,Paint p){}
}
""",
"android/graphics/Shader.java": "package android.graphics; public class Shader {public enum TileMode {CLAMP}}",
"android/graphics/LinearGradient.java": """package android.graphics; public class LinearGradient extends Shader {
public LinearGradient(float a,float b,float c,float d,int e,int f,TileMode t){}
public LinearGradient(float a,float b,float c,float d,int[] e,float[] f,TileMode t){} }
""",
"android/graphics/Color.java": """package android.graphics;public class Color {
public static int red(int c){return c>>16&255;}public static int green(int c){return c>>8&255;}
public static int blue(int c){return c&255;}public static int argb(int a,int r,int g,int b){return a<<24|r<<16|g<<8|b;} }
""",
"android/os/SystemClock.java": "package android.os; public class SystemClock {public static long elapsedRealtime(){return 1000L;}}",
}

PROBE = r'''package io.github.asteroidb612zs.hondatadash;
import java.nio.file.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import android.graphics.Canvas;
import android.graphics.Typeface;
public class TextFitProbe {
 static int draws, glyphs, transitions, scaleLabels;
 static final String[][] CASES={
 {"E0","E9","E25","E85","E99","--"},
 {"-20","-1","0","99","100","130","888","--"},
 {"-20","-1","0","99","100","130","888","--"},
 {"-30.0","-15.1","-15.0","-10.0","-9.9","-0.1","+0.0","+9.9","+10.0","+15.0","+15.1","+30.0","+88.8","-88.8","--"},
 {"-1.50","-0.01","+0.00","+0.99","+1.00","+1.45","+1.60","+3.00","--"},
 {"6.5","6.8","7.0","7.2","7.9","8.0","9.9","10.0","14.7","18.0","20.0","21.5","25.0","--"},
 {"-25.0","-0.1","+0.0","+9.9","+10.0","+55.0","--"},
 {"-30.0","-15.1","-15.0","-10.0","-9.9","-0.1","+0.0","+9.9","+10.0","+15.0","+15.1","+30.0","+88.8","-88.8","--"}
 };
 static final String[][] REFS={{"E99","--"},{"888","-88","--"},{"888","-88","--"},
 {"+88.8","-88.8","--"},{"+8.88","-8.88","--"},{"88.8","--"},
 {"+88.8","-88.8","--"},{"+88.8","-88.8","--"}};
 static final String[] AUX={"888","88888","88888","88888","88888","-88.8","888.8","88.8","888.8","888","888"};
 static Canvas render(FittedTextView v){v.scrollTo(150,75);
 if(v.getScrollX()!=0||v.getScrollY()!=0)throw new AssertionError("TextView scrolled fitted text");
 Canvas c=new Canvas(v.getWidth(),v.getHeight());v.onDraw(c);
 for(java.awt.geom.Rectangle2D b:c.boxes)if(!b.isEmpty() &&
 (b.getMinX()<.99||b.getMinY()<.99||b.getMaxX()>v.getWidth()-.99||b.getMaxY()>v.getHeight()-.99))
 throw new AssertionError("antialias edge guard lost: "+v.getText()+" "+b);
 for(int i=1;i<c.boxes.size();i++)if(!c.boxes.get(i-1).isEmpty()&&!c.boxes.get(i).isEmpty()
 &&c.boxes.get(i-1).getMaxX()>c.boxes.get(i).getMinX()+.01)
 throw new AssertionError("adjacent glyphs overlap: "+v.getText());
 if(!String.join("",c.texts).replace('−','-').equals(v.getText().toString()))throw new AssertionError("text omitted: "+v.getText());
 draws++;glyphs+=c.texts.size();return c;}
 static void sameScale(Canvas a,Canvas b){if(a.transforms.isEmpty()||b.transforms.isEmpty())return;
 java.awt.geom.AffineTransform x=a.transforms.get(0),y=b.transforms.get(0);
 if(Math.abs(x.getScaleX()-y.getScaleX())>.0001||Math.abs(x.getScaleY()-y.getScaleY())>.0001||Math.abs(x.getTranslateY()-y.getTranslateY())>.0001)
 throw new AssertionError("normal value caused size/baseline breathing");}
 public static void main(String[] args)throws Exception {
 java.util.List<String> lines=Files.readAllLines(Paths.get(args[0]));
 String[] fonts=args[2].split(";");
 java.util.List<String> legibility=new ArrayList<>();
 legibility.add("card\tvalue\tink_width_px\tink_height_px\tminimum_digit_ink_height_px\tscale_x\tscale_y");
 for(int fontIndex=0;fontIndex<fonts.length;fontIndex++) {
 Font font=Font.createFont(Font.TRUETYPE_FONT,Paths.get(fonts[fontIndex]).toFile());Typeface.current=font;
 for(String name:new String[]{"bold","boldItalic","scale"}){java.lang.reflect.Field f=DashboardTypeface.class.getDeclaredField(name);f.setAccessible(true);f.set(null,null);}
 for(float fontScale:new float[]{.85f,1f,1.3f,1.5f,2f}) {
 Map<String,BufferedImage> images=new HashMap<>();
 for(String line:lines){String[] f=line.split("\t",-1);
 String screen=f[0],kind=f[1],id=f[2];int card=Integer.parseInt(f[3]);
 int x=Integer.parseInt(f[4]),y=Integer.parseInt(f[5]),w=Integer.parseInt(f[6]),h=Integer.parseInt(f[7]);
 float sp=Float.parseFloat(f[8]);String initial=f[9];int gravity=Integer.parseInt(f[10]);
 android.content.Context context=new android.content.Context(1f,fontScale);
 if(kind.equals("scale")) {
 ScaleBarView bar=new ScaleBarView(context);bar.layout(0,0,w,h);bar.setPadding(3,0,3,0);configureScale(card,bar);
 Canvas canvas=new Canvas(w,h);bar.onDraw(canvas);
 int expected=new int[]{6,6,6,5,5,7,5,5}[card];
 String[][] critical={{"0","100"},{"20","120"},{"−20","0","80"},{"−25","0","+25"},
 {"−1.0","0","+2.0"},{"7","14","20"},{"−40","0","+40"},{"−25","0","+25"}};
 if(!canvas.texts.containsAll(Arrays.asList(critical[card])))throw new AssertionError("critical scale label omitted "+screen+" card "+card+" "+canvas.texts);
 if((card==3||card==7)&&canvas.texts.contains("−15")!=canvas.texts.contains("+15"))throw new AssertionError("asymmetric trim labels");
 if(fonts[fontIndex].endsWith("RobotoCondensed-Regular.ttf")&&w>=190&&canvas.texts.size()!=expected)
 throw new AssertionError("production upright font omitted label at reference-width slot "+screen+" "+fontScale+" "+canvas.texts);
 for(int a=0;a<canvas.boxes.size();a++)for(int b=a+1;b<canvas.boxes.size();b++)
 if(canvas.boxes.get(a).intersects(canvas.boxes.get(b)))throw new AssertionError("scale label overlap");
 scaleLabels+=canvas.texts.size();continue;}
 FittedTextView v=new FittedTextView(context);v.layout(0,0,w,h);v.setTextSize(sp);
 v.setTypeface(new Typeface(font));v.setGravity(gravity);v.setText(initial);
 String[] cases={initial};boolean stable=false;
 if(kind.equals("main")){v.setFitReference(true,card==1||card==2||card==3||card==4||card==6||card==7,REFS[card]);cases=CASES[card];stable=true;}
 if(kind.equals("extreme")){v.setFitReference(true,false,card==0?new String[]{"888","--"}:REFS[card]);
 cases=card==0?new String[]{"0","9","85","100","888","--"}:CASES[card];stable=true;}
 // Sweep every displayed tenth through both trim ranges in the target viewport.
 if((kind.equals("main")||kind.equals("extreme"))&&(card==3||card==7)&&screen.equals("800x480")&&fontScale==1f){
 java.util.List<String> all=new ArrayList<>(Arrays.asList(cases));
 for(int n=-300;n<=300;n++)all.add(String.format(Locale.US,"%+.1f",n/10f));
 cases=all.toArray(new String[0]);}
 if(kind.equals("aux")){v.setFitReference(true,false,AUX[card],"--");
 cases=new String[]{"--","0","9","99","100","127.5","65535","2147483647","-2147483648"};}
 if(id.equals("statusText")){v.setFitReference(false,false,"CONNECTING","DATA LOST","RECONNECT");
 cases=new String[]{"WAIT","CONNECTING","DATA LOST","RECONNECT","BT LOST","BT OFF","NO BT","PAIR","SELECT","LIVE","STALE"};stable=true;}
 Canvas previous=null;Double decimalX=null;
 for(String value:cases){v.setText(value);Canvas current=render(v);if(stable&&previous!=null)sameScale(previous,current);previous=current;
 if(fontIndex==0&&fontScale==1f&&screen.equals("800x480")&&kind.equals("main")
 &&Arrays.asList("E100","130","-20","+10.0","-10.0","+30.0","-30.0","+3.00","-1.50","25.0","+55.0","-25.0").contains(value)){
 java.awt.geom.Rectangle2D union=null;double digitHeight=Double.POSITIVE_INFINITY;
 for(int j=0;j<current.boxes.size();j++){java.awt.geom.Rectangle2D b=current.boxes.get(j);if(b.isEmpty())continue;
 union=union==null?(java.awt.geom.Rectangle2D)b.clone():union.createUnion(b);
 if(Character.isDigit(current.texts.get(j).charAt(0)))digitHeight=Math.min(digitHeight,b.getHeight());}
 java.awt.geom.AffineTransform at=current.transforms.get(0);
 String row=String.format(Locale.US,"%d\t%s\t%.2f\t%.2f\t%.2f\t%.4f\t%.4f",card,value,union.getWidth(),union.getHeight(),digitHeight,at.getScaleX(),at.getScaleY());
 if(!legibility.contains(row))legibility.add(row);}
 if(stable&&kind.equals("main")&&current.texts.contains(".")){double dx=current.boxes.get(current.texts.indexOf(".")).getMinX();
 if(decimalX!=null&&Math.abs(decimalX-dx)>.001)throw new AssertionError("decimal drift: screen="+screen+" card="+card+" id="+id+" value="+value+" prev="+decimalX+" now="+dx);
 decimalX=dx;}}
 if(kind.equals("main")){
 if(card==0){
  // E99 is the normal design reference, but a genuine E100 must still render in full.
  v.setText("E100");render(v);
  v.setText("E888");render(v);
 }
 for(String overflow:new String[]{"-100.0","+100.0","-888.88"}){v.setText(overflow);render(v);}
 v.setTextSize(80);v.setFitReference(false,false,"DFCO","SYNC");v.setText("DFCO");Canvas a=render(v);v.setText("SYNC");sameScale(a,render(v));
 // Resize without setText: exercises the old same-width/changed-height cache bug.
 v.layout(0,0,w,Math.max(1,h-36));render(v);v.layout(0,0,w,h);
 v.setTextSize(sp);v.setFitReference(true,card==1||card==2||card==3||card==4||card==6||card==7,REFS[card]);
 v.setText(cases[0]);render(v);transitions++;
 }
 if(fontIndex==0&&fontScale==1f){
 int[] size=Arrays.stream(screen.split("x")).mapToInt(Integer::parseInt).toArray();
 BufferedImage image=images.get(screen);if(image==null){image=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB);
 Graphics2D g=image.createGraphics();g.setColor(new Color(0x05080b));g.fillRect(0,0,size[0],size[1]);g.dispose();images.put(screen,image);}
 Graphics2D g=image.createGraphics();g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
 g.setColor(new Color(0x1b252c));g.drawRect(x,y,w-1,h-1);g.translate(x,y);
 v.setText(initial);if(kind.equals("main"))v.setTextColor(0xff3fb950);
 Canvas c=new Canvas(w,h);c.graphics=g;v.onDraw(c);g.dispose();}
 }
 for(Map.Entry<String,BufferedImage> image:images.entrySet())javax.imageio.ImageIO.write(image.getValue(),"png",Paths.get(args[1],"text-slots-"+image.getKey()+".png").toFile());
 }}
 System.out.println("PASS: "+draws+" production text draws / "+glyphs+" glyphs contained; "+transitions+" semantic/resize transitions; "+scaleLabels+" scale labels (critical labels retained, symmetric fallback).");
 System.out.println("Test doubles use desktop fonts; this is NOT an Android emulator or real head-unit screenshot.");
 Files.write(Paths.get(args[1],"legibility-metrics.tsv"),legibility);
 }
 CONFIGURE_SCALE
}
'''


def attributes(node):
    result = {}
    if "style" in node.attrib:
        result.update(STYLES[node.attrib["style"].split("/")[-1]])
    result.update({k.replace(NS, ""): v for k, v in node.attrib.items()})
    return result


STYLES = {s.attrib["name"]: {i.attrib["name"].replace("android:", ""): i.text for i in s}
          for s in ET.parse(RES / "values/styles.xml").getroot()}
DIMENS = {s.attrib["name"]: s.text for s in ET.parse(RES / "values/dimens.xml").getroot()}


def dimension(value, default=0):
    if value and value.startswith("@dimen/"):
        return dimension(DIMENS[value.split("/")[-1]], default)
    return int(float(value.removesuffix("dp"))) if value and value.endswith("dp") else default


def method_text(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == "{":
            depth += 1
        elif source[i] == "}":
            depth -= 1
            if depth == 0:
                return source[start:i + 1]
    raise ValueError(signature)


def trim_zone_method(source):
    start = source.index("    private static void addTrimScaleZones(")
    end = source.index("\n    }", start) + len("\n    }")
    return source[start:end]


def resolve(node, x, y, width, height, card, screen, slots):
    a = attributes(node)
    if a.get("visibility") == "gone":
        return
    if node.tag == "include":
        imported = copy.deepcopy(ET.parse(RES / (node.attrib["layout"].replace("@", "") + ".xml")).getroot())
        imported.attrib.update({k: v for k, v in node.attrib.items() if k != "layout"})
        resolve(imported, x, y, width, height, card, screen, slots)
        return
    ident = a.get("id", "").split("/")[-1]
    if re.fullmatch(r"card[0-7]", ident):
        card = int(ident[-1])
    if re.fullmatch(r"knock[0-3]", ident):
        card = int(ident[-1]) + 1
    if node.tag == "TextView":
        raise AssertionError(f"unprotected visible TextView: {ident}")
    if node.tag == FITTED:
        assert width > 0 and height > 0, (ident, width, height)
        assert a["layout_width"] != "wrap_content" and a["layout_height"] != "wrap_content", ident
        text = a.get("text", "--")
        kind = "label"
        if ident == "valueInt":
            kind, text = "main", ["E100", "130", "-20", "+30.0", "-1.50", "25.0", "-25.0", "+30.0"][card]
        elif ident in ("maxValue", "minValue"):
            kind, text = "extreme", ["100", "130", "-20", "+30.0", "-1.50", "25.0", "-25.0", "+30.0"][card]
        elif ident == "labelEn":
            text = ["ETHANOL", "ECT", "IAT", "L.TRIM", "MAP", "A/F", "IGN", "S.TRIM"][card]
        elif ident == "unit":
            text = ["%", "°C", "°C", "%", "bar", "", "°", "%"][card]
        elif ident == "knockLabel":
            text = "CYL" + str(card)
        elif ident == "knockValue":
            kind, text = "aux", "65535"
        else:
            aux = {"knockRetValue": 0, "bottomTrimValue": 5, "bottomAfmValue": 6, "bottomBatValue": 7,
                   "bottomFpValue": 8, "bottomWgValue": 9, "bottomTpValue": 10}
            if ident in aux:
                kind, card = "aux", aux[ident]
                text = ["100", "", "", "", "", "-25.0", "127.5", "14.9", "250.0", "100", "100"][card]
            if ident == "statusText":
                text = "CONNECTING"
        gravity = a.get("gravity", "top|start")
        g = (5 if "end" in gravity else 1 if "center" == gravity or "center_horizontal" in gravity else 3)
        g |= 16 if "center" == gravity or "center_vertical" in gravity else 80 if "bottom" in gravity else 48
        slots.append([screen, kind, ident, card, x, y, width, height, a.get("textSize", "16sp")[:-2], text, g])
        return
    if node.tag.endswith("ScaleBarView"):
        slots.append([screen, "scale", ident, card, x, y, width, height, 10, "", 17])
        return
    p = dimension(a.get("padding"))
    pl = dimension(a.get("paddingStart", a.get("paddingLeft")), p)
    pr = dimension(a.get("paddingEnd", a.get("paddingRight")), p)
    pt, pb = dimension(a.get("paddingTop"), p), dimension(a.get("paddingBottom"), p)
    grid = node.tag.endswith("DashboardGridLayout")
    if grid:
        available = max(0, width - 2 * pt)
        content = available // 12 * 12 if available >= 12 else available
        pl, pr = (width - content) // 2, width - content - (width - content) // 2
    x, y, width, height = x + pl, y + pt, width - pl - pr, height - pt - pb
    children = [n for n in node if attributes(n).get("visibility") != "gone"]
    horizontal = a.get("orientation") == "horizontal"
    aligned = node.tag.endswith("AlignedRowLayout")
    linear = node.tag == "LinearLayout" or aligned or grid
    weights = sum(float(attributes(n).get("layout_weight", 0)) for n in children)
    def margins(b):
        return [dimension(b.get(k)) for k in ("layout_marginStart", "layout_marginTop", "layout_marginEnd", "layout_marginBottom")]
    remaining = width if horizontal else height
    if linear:
        for child in children:
            b = attributes(child)
            m = margins(b)
            remaining -= dimension(b.get("layout_width" if horizontal else "layout_height")) + (m[0] + m[2] if horizontal else m[1] + m[3])
    cursor = 0
    original_remaining, total_weights, used_weight = remaining, weights, 0
    for child in children:
        b = attributes(child)
        ml, mt, mr, mb = margins(b)
        cw = dimension(b.get("layout_width"), width - ml - mr)
        ch = dimension(b.get("layout_height"), height - mt - mb)
        weight = float(b.get("layout_weight", 0))
        if linear and weight:
            if aligned:
                used_weight += weight
                share = int(original_remaining * used_weight / total_weights + .5) - cursor
            else:
                share = int(remaining * weight / weights)
            remaining, weights = remaining - share, weights - weight
            if horizontal:
                cw += share
            else:
                ch += share
        cx, cy = x + ml, y + mt
        if linear:
            if horizontal:
                cx += cursor
                cursor += cw + ml + mr
            else:
                cy += cursor
                cursor += ch + mt + mb
        else:
            gravity = b.get("layout_gravity", "")
            if "end" in gravity:
                cx = x + width - cw - mr
            if "center_vertical" in gravity:
                cy = y + (height - ch) // 2
            if "bottom" in gravity:
                cy = y + height - ch - mb
            if node.tag.endswith("HeaderLayout"):
                child_id = b.get("id", "").split("/")[-1]
                if child_id in ("brandMark", "shiftLight", "connectionStatus"):
                    cw = int(width * (.54 if child_id == "shiftLight" else .20) + .5)
                    ch = height
                    cx = x if child_id == "brandMark" else x + (width - cw) // 2 if child_id == "shiftLight" else x + width - cw
                    cy = y
        assert cw >= 0 and ch >= 0, (ident, cw, ch)
        assert cx >= x and cy >= y and cx + cw <= x + width and cy + ch <= y + height, (ident, "slot outside parent")
        resolve(child, cx, cy, cw, ch, card, screen, slots)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--fonts", nargs="*", type=Path)
    args = parser.parse_args()
    fonts = args.fonts or [Path(p) for p in (
        "/usr/share/fonts/opentype/urw-base35/NimbusSansNarrow-BoldOblique.otf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-BoldOblique.ttf")]
    assert len(fonts) >= 2 and all(p.is_file() for p in fonts), "Provide >=2 actual font files using --fonts"
    main_source = (JAVA / "MainActivity.java").read_text()
    # Execute the ACTUAL scale configuration, not a separately guessed tick arrangement.
    start = main_source.index("        switch (i) {", main_source.index("private void configureScaleBar("))
    end = main_source.index("\n    @Override", start)
    configure = "static void configureScale(int i,ScaleBarView bar) {\n" + main_source[start:end].strip()
    configure += "\n" + trim_zone_method(main_source)
    probe = PROBE.replace("CONFIGURE_SCALE", configure)
    # Keep fixture profiles honest if production references change.
    for ref in ("E99", "+8.88", "-88.8", "88888"):
        assert f'"{ref}"' in main_source, f"fixture profile stale: {ref}"
    slots = []
    viewports = [(800, 480), (800, 432), (800, 408), (752, 480), (752, 408),
                 (1024, 600), (1280, 720), (1440, 900), (799, 480), (853, 480)]
    for w, h in viewports:
        resolve(ET.parse(RES / "layout/activity_main.xml").getroot(), 0, 0, w, h, -1, f"{w}x{h}", slots)
    with tempfile.TemporaryDirectory(prefix="hondata-text-fit-") as tmp:
        temp = Path(tmp)
        output = args.output or temp
        output.mkdir(parents=True, exist_ok=True)
        for path, contents in STUBS.items():
            target = temp / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(contents)
        (temp / "TextFitProbe.java").write_text(probe)
        (temp / "slots.tsv").write_text("\n".join("\t".join(map(str, row)) for row in slots))
        sources = [str(p) for p in temp.rglob("*.java")]
        sources += [str(JAVA / p) for p in ("FittedTextView.java", "TextFitGeometry.java", "ScaleBarView.java", "DashboardTypeface.java", "DashboardPalette.java")]
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11", "-d", str(temp / "classes"), *sources], check=True)
        subprocess.run(["java", "-Djava.awt.headless=true", "-cp", str(temp / "classes"), "io.github.asteroidb612zs.hondatadash.TextFitProbe",
                        str(temp / "slots.tsv"), str(output), ";".join(map(str, fonts))], check=True)
    print(f"PASS: {len(slots)} production XML slots across {len(viewports)} viewport cases, 5 font scales and {len(fonts)} fallback fonts")


if __name__ == "__main__":
    main()
