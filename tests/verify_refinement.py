#!/usr/bin/env python3
"""Focused JVM checks; Android APIs are mocked, so this is not a device/UI test.

Usage: python3 tests/verify_refinement.py [--android-jar /path/to/android.jar]
An optional official SDK jar compiles every production Java source as well.
"""
import argparse
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/io/github/asteroidb612zs/hondatadash"
RES = ROOT / "app/src/main/res"


def method(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


STUBS = {
    "android/os/Looper.java": """package android.os;
public class Looper { public static Looper getMainLooper(){return new Looper();} }""",
    "android/os/Handler.java": """package android.os;
public class Handler { public int posts;
 public Handler(Looper l){} public boolean post(Runnable r){return true;}
 public boolean postDelayed(Runnable r,long delay){posts++;return true;}
 public void removeCallbacks(Runnable r){}
}""",
    "android/os/SystemClock.java": """package android.os;
public class SystemClock { public static volatile long testNow=-1;
 public static long elapsedRealtime(){return testNow>=0?testNow:System.nanoTime()/1000000;}
}""",
    "android/content/Context.java": "package android.content; public class Context {}",
    "android/util/Log.java": """package android.util; public class Log {
 public static int i(String t,String s){return 0;} public static int w(String t,String s){return 0;}
 public static int e(String t,String s){return 0;} public static int e(String t,String s,Throwable e){return 0;}
}""",
    "android/bluetooth/BluetoothSocket.java": """package android.bluetooth;
import java.io.*;import java.util.concurrent.*;import java.util.concurrent.atomic.*;
public class BluetoothSocket {
 public static final AtomicInteger ENTRIES=new AtomicInteger(),CLOSES=new AtomicInteger();
 public static final CountDownLatch ENTERED=new CountDownLatch(1),RELEASE=new CountDownLatch(1);
 public void connect() throws IOException {ENTRIES.incrementAndGet();ENTERED.countDown();
  try{RELEASE.await();}catch(InterruptedException e){throw new IOException(e);}
  throw new IOException("cancelled mock connection");}
 public void close() throws IOException {CLOSES.incrementAndGet();RELEASE.countDown();}
 public InputStream getInputStream() throws IOException{return new ByteArrayInputStream(new byte[0]);}
 public OutputStream getOutputStream() throws IOException{return new ByteArrayOutputStream();}
}""",
    "android/bluetooth/BluetoothDevice.java": """package android.bluetooth;
import java.util.UUID;public class BluetoothDevice {
 public String getName(){return "Test FlashPro";}public String getAddress(){return "00:00:00:00:00:00";}
 public BluetoothSocket createRfcommSocket(int c){return new BluetoothSocket();}
 public BluetoothSocket createInsecureRfcommSocketToServiceRecord(UUID u){return new BluetoothSocket();}
 public BluetoothSocket createRfcommSocketToServiceRecord(UUID u){return new BluetoothSocket();}
}""",
    "android/bluetooth/BluetoothAdapter.java": """package android.bluetooth;
import java.util.*;public class BluetoothAdapter {
 public static BluetoothAdapter getDefaultAdapter(){return new BluetoothAdapter();}
 public static boolean checkBluetoothAddress(String address){return address!=null&&address.matches("(?i)([0-9A-F]{2}:){5}[0-9A-F]{2}");}
 public boolean isEnabled(){return true;}public boolean cancelDiscovery(){return true;}
 public BluetoothDevice getRemoteDevice(String address){return new BluetoothDevice();}
 public Set<BluetoothDevice> getBondedDevices(){return Collections.emptySet();}
}""",
}

DISPLAY_SETUP = """import io.github.asteroidb612zs.hondatadash.data.*;
import android.os.*;import java.util.*;
public class DisplayProbe {
 // This unit checks pre-palette alarm/freshness semantics. OEM colour mapping
 // is exercised separately by verify_oem.py against the actual palette class.
 void applyOemPalette(){}
 static class TextView {int color;float alpha=1;void setTextColor(int c){color=c;}void setAlpha(float a){alpha=a;}}
 static class ScaleBarView extends TextView {boolean active=true;void setMonitoringActive(boolean a){active=a;}}
 static class Shift {boolean active=true;void setMonitoringActive(boolean a){active=a;}}
 static class Source {boolean connected=true;boolean isConnected(){return connected;}}
 static class Recovery {void reset(int card){}}
 boolean foreground=true,displayDataStale,flashVisible=false,flashScheduled;
 boolean ectFlashing,iatFlashing,afFlashing,kcFlashing,fpFlashing,cylRedFlashing;
 boolean[] frameValid=new boolean[8],hasValidValue=new boolean[8],semanticMode=new boolean[8],displayHoldMode=new boolean[8],auxiliaryValid=new boolean[11];
 TextView[] valueIntViews=new TextView[8],auxiliaryViews=new TextView[11],knockValues=new TextView[4];
 ScaleBarView[] scaleBars=new ScaleBarView[8];
 TextView knockRetValue,bottomFpValue;Shift shiftLight=new Shift();Source dataSource=new Source();
 Recovery colorRecovery=new Recovery();
 static class EngineState {void reset(){}} static class Admission {void requireReacquire(long n){}} static class FuelPressure {void reset(){}}
 EngineState engineState=new EngineState(); Admission combustionAdmission=new Admission(); FuelPressure fuelPressureAlert=new FuelPressure();
 void invalidateCombustionDisplayForLinkGap(){}
 boolean rpmFrameValid=true;long lastValidFrameTimeMs=10000,connectedSinceMs;
 final Handler flashHandler=new Handler(Looper.getMainLooper());final Runnable flashTick=()->{};
 static final int COLOR_DANGER=0xFFFF4444;
 static final long DFCO_EXIT_IGN_MIN_SYNC_MS=250L;
 static int checks;
 static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
 DisplayProbe(){Arrays.fill(frameValid,true);Arrays.fill(hasValidValue,true);Arrays.fill(auxiliaryValid,true);
  for(int i=0;i<8;i++){valueIntViews[i]=new TextView();scaleBars[i]=new ScaleBarView();}
  for(int i=0;i<11;i++)auxiliaryViews[i]=new TextView();
  knockRetValue=auxiliaryViews[0];bottomFpValue=auxiliaryViews[8];
  for(int i=0;i<4;i++)knockValues[i]=auxiliaryViews[i+1];}
"""

DISPLAY_TESTS = """
 public static void main(String[] args){
  SystemClock.testNow=10000;DisplayProbe p=new DisplayProbe();p.afFlashing=true;
  p.valueIntViews[5].setTextColor(0xFFFFFFFF);p.valueIntViews[5].setAlpha(1);
  p.applyAlertAndFreshnessVisuals();
  check(p.valueIntViews[5].color==COLOR_DANGER,"data refresh must not erase red alarm");
  check(p.valueIntViews[5].alpha==0.35f,"data refresh must preserve dim alarm phase");
  for(int i=0;i<100;i++)p.updateFlashState();
  check(p.flashHandler.posts==1,"repeated frames must not restart alarm timer");
  p.semanticMode[5]=true;p.valueIntViews[5].color=0xFFA0A0A0;p.valueIntViews[5].alpha=1;
  p.applyAlertAndFreshnessVisuals();
  check(p.valueIntViews[5].color==0xFFA0A0A0 && p.valueIntViews[5].alpha==1,"DFCO/SYNC priority");
  p.semanticMode[5]=false;SystemClock.testNow=10501;p.applyAlertAndFreshnessVisuals();
  check(!p.isDataFresh() && p.displayDataStale,"receive age, not UI processing time");
  check(p.valueIntViews[5].alpha==0.4f,"stale data overrides alarm phase");
  check(!p.shiftLight.active,"stale RPM must not keep shift light running");
  check(!p.scaleBars[5].active,"stale card must remove live fill and histories");
  p.updateFlashState();check(!p.flashScheduled,"stale alarms stop ticking");
  SystemClock.testNow=10000;p.frameValid[5]=false;p.applyAlertAndFreshnessVisuals();
  check(p.valueIntViews[5].alpha==0.4f,"missing PID must not flash as a live reading");
  System.out.println("PASS: "+checks+" display/semantic assertions (extracted production methods)");
 }
}
"""

FORMAT_SETUP = """public class FormatProbe {
 static int checks;
 static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
"""

FORMAT_TESTS = """
 public static void main(String[] args){FormatProbe p=new FormatProbe();
  check("+1.45".equals(p.formatMainText(4,1.449f)),"MAP uses two decimals");
  check("+1.46".equals(p.formatMainText(4,1.455f)),"MAP boundary remains visible");
  check("-0.87".equals(p.formatExtremeText(4,-0.874f)),"MAP extremes match main precision");
  check("+0.0".equals(p.formatMainText(6,0f)),"signed cards keep explicit sign");
  check("14.7".equals(p.formatMainText(5,14.74f)),"A/F precision is unchanged");
  for(int card:new int[]{3,7}){
   check("+9.9".equals(p.formatMainText(card,9.94f)),"trim below positive decade");
   check("+10.0".equals(p.formatMainText(card,9.96f)),"trim crosses positive decade");
   check("-9.9".equals(p.formatMainText(card,-9.94f)),"trim below negative decade");
   check("-10.0".equals(p.formatMainText(card,-9.96f)),"trim crosses negative decade");
   check("+30.0".equals(p.formatMainText(card,30f)),"trim positive valid limit");
   check("-30.0".equals(p.formatExtremeText(card,-30f)),"trim negative extreme limit");
  }
  System.out.println("PASS: "+checks+" number-format assertions (extracted production methods)");
 }
}
"""

COLOR_PROBE = """package io.github.asteroidb612zs.hondatadash;
public class ColorRecoveryProbe {
 static int checks;
 static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);checks++;}
 public static void main(String[] args){ColorRecovery r=new ColorRecovery();
  check(r.update(4,0,0)==0,"initial safe state");
  check(r.update(4,2,10)==2,"danger escalation is immediate");
  check(r.update(4,0,11)==2,"recovery candidate starts without changing color");
  check(r.update(4,0,410)==2,"recovery waits for a stable interval");
  check(r.update(4,0,411)==0,"recovery occurs after 400ms");
  check(r.update(4,1,412)==1,"warning escalation remains immediate");
  r.reset(4);check(r.update(4,0,413)==0,"reset starts from current input");
  System.out.println("PASS: "+checks+" color-recovery assertions");
 }
}
"""

CONNECTION_PROBE = """import io.github.asteroidb612zs.hondatadash.data.*;
import android.bluetooth.*;import android.os.*;
import java.io.*;import java.lang.reflect.*;import java.util.concurrent.*;
public class ConnectionProbe {
 static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);}
 static Field field(String name)throws Exception{Field f=BluetoothSource.class.getDeclaredField(name);f.setAccessible(true);return f;}
 static class Packet extends InputStream {
  CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);int offset;
  byte[] bytes={0,0x35,0,0,80};
  public int available() throws IOException {
   if(offset==0){entered.countDown();try{release.await();}catch(InterruptedException e){throw new IOException(e);}}
   return bytes.length-offset;
  }
  public int read(){return offset<bytes.length?bytes[offset++]&255:-1;}
  public int read(byte[] b,int off,int len){int n=Math.min(len,bytes.length-offset);System.arraycopy(bytes,offset,b,off,n);offset+=n;return n;}
  public void close(){release.countDown();}
 }
 public static void main(String[] args)throws Exception {
  SystemClock.testNow=-1;BluetoothSource source=new BluetoothSource();
  source.connect("aa:bb:cc:dd:ee:ff");check(BluetoothSocket.ENTERED.await(2,TimeUnit.SECONDS),"first connection begins");
  check("AA:BB:CC:DD:EE:FF".equals(source.getTargetAddress()),"selected address is normalized and retained");
  source.connect("11:22:33:44:55:66");source.fullReset();Thread.sleep(30);
  check(BluetoothSocket.ENTRIES.get()==1,"one connection owner across connect/resume/reset");
  source.disconnect();Thread.sleep(30);
  check(BluetoothSocket.CLOSES.get()>0&&!source.isConnected(),"pending connection is cancelled");
  int entriesAfterDisconnect=BluetoothSocket.ENTRIES.get();BluetoothSource invalid=new BluetoothSource();
  invalid.connect(null);Thread.sleep(30);
  check(invalid.getTargetAddress()==null&&BluetoothSocket.ENTRIES.get()==entriesAfterDisconnect,"missing address never starts RFCOMM");
  BluetoothSource poll=new BluetoothSource();
  HondataProtocol protocol=new HondataProtocol();
  check(protocol.parseInit(new byte[]{0,0x30,0,0,1,0,1,0}),"init fixture");
  check(protocol.parseSensorDefinitions(new byte[]{0,0x31,0,0,0x60,1,0x50}),"definition fixture");
  Packet packet=new Packet();field("protocol").set(poll,protocol);field("connected").set(poll,true);
  field("inputStream").set(poll,packet);field("outputStream").set(poll,new ByteArrayOutputStream());
  poll.startPolling();check(packet.entered.await(2,TimeUnit.SECONDS),"poll enters bounded read");
  Thread first=(Thread)field("pollThread").get(poll);
  poll.stopPolling();poll.startPolling();
  check(field("pollThread").get(poll)==first,"fast resume waits for the same reader");
  packet.release.countDown();first.join(1000);
  check(!first.isAlive(),"old reader exits after completing its packet");
  check(field("pollThread").get(poll)!=null && field("pollThread").get(poll)!=first,"resume starts one replacement reader");
  poll.disconnect();System.out.println("PASS: connection arbitration, cancellation and rapid pause/resume (mock Android)");
 }
}
"""


def run(command):
    subprocess.run(command, check=True)


def validate_resources():
    """Catch malformed XML and unresolved project resources without Gradle/aapt2."""
    available = {"id": set()}
    xml_files = list(RES.rglob("*.xml")) + [ROOT / "app/src/main/AndroidManifest.xml"]
    for directory in RES.iterdir():
        if directory.is_dir() and not directory.name.startswith("values"):
            resource_type = directory.name.split("-")[0]
            available.setdefault(resource_type, set()).update(path.stem for path in directory.iterdir() if path.is_file())
    for path in RES.glob("values*/*.xml"):
        root = ET.parse(path).getroot()
        for child in root:
            name = child.attrib.get("name")
            if name:
                available.setdefault(child.tag, set()).add(name)

    references = []
    for path in xml_files:
        text = path.read_text()
        ET.fromstring(text)
        for create, resource_type, name in re.findall(r"@(\+?)(?!android:)([A-Za-z_]+)/([A-Za-z0-9_]+)", text):
            if create and resource_type == "id":
                available["id"].add(name)
            else:
                references.append((path, resource_type, name))
    for path in JAVA.rglob("*.java"):
        for resource_type, name in re.findall(r"(?<!android\.)R\.(\w+)\.(\w+)", path.read_text()):
            references.append((path, resource_type, name))

    missing = [(path, kind, name) for path, kind, name in references
               if name not in available.get(kind, set())]
    if missing:
        details = "\n".join(f"{path.relative_to(ROOT)}: @{kind}/{name}" for path, kind, name in missing)
        raise AssertionError("unresolved project resources:\n" + details)
    print(f"PASS: {len(xml_files)} resource/manifest XML files parse and all project references resolve")


def validate_bluetooth_selection_contract():
    """Guard the no-compiled-MAC, system-owned pairing and saved-device contract."""
    main = (JAVA / "MainActivity.java").read_text()
    source = (JAVA / "data/BluetoothSource.java").read_text()
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
    checks = {
        "compile-time MAC placeholder is absent": "FLASHPRO_MAC" not in source and "XX:XX:XX:XX:XX:XX" not in source,
        "selected address reaches RFCOMM": "getRemoteDevice(address)" in source and "targetAddress = normalizedAddress" in source,
        "address survives reconnect": "final String address = targetAddress" in source and "hasTargetAddress()" in source,
        "selection is private and persistent": "getSharedPreferences(BLUETOOTH_PREFS, MODE_PRIVATE)" in main and ".putString(PREF_DEVICE_ADDRESS" in main,
        "only bonded devices are listed": "getBondedDevices()" in main and "startDiscovery(" not in main,
        "pairing UI is system owned": "Settings.ACTION_BLUETOOTH_SETTINGS" in main,
        "device can be changed from header": "statusText.setOnClickListener" in main and "showBluetoothDeviceChooser(false)" in main,
        "Bluetooth pairing still needs no location permission": "ACCESS_FINE_LOCATION" not in manifest,
        "IT2 diagnostic storage permission is explicit": "WRITE_EXTERNAL_STORAGE" in manifest
            and "HondataDash/Diagnostics" in main,
    }
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise AssertionError("Bluetooth selection contract failed: " + ", ".join(failed))
    print(f"PASS: {len(checks)} Bluetooth pairing/persistence contract assertions")


def validate_visual_contract():
    """Lock the fixed 800x480 optical system without pretending to test rasterization."""
    main = (JAVA / "MainActivity.java").read_text()
    scale = (JAVA / "ScaleBarView.java").read_text()
    shift = (JAVA / "ShiftLightView.java").read_text()
    lens = (JAVA / "ShiftLightRenderer.java").read_text()
    dims = (RES / "values/dimens.xml").read_text()
    palette = (JAVA / "DashboardPalette.java").read_text()
    activity = (RES / "layout/activity_main.xml").read_text()
    card = (RES / "layout/item_sensor_card.xml").read_text()
    styles = (RES / "values/styles.xml").read_text()
    colors = (RES / "values/colors.xml").read_text()
    fitted = (JAVA / "FittedTextView.java").read_text()
    geometry = (JAVA / "TextFitGeometry.java").read_text()
    overlay = (JAVA / "StartupOverlayView.java").read_text()
    proguard = (ROOT / "app/proguard-rules.pro").read_text()
    checks = {
        "two-level graphite surface": "#FF03070A" in colors and "#FF071014" in colors,
        "single-pixel structure": 'android:layout_width="1dp"' in card and "#FF58727E" in colors,
        "condensed instrument type": styles.count("sans-serif-condensed") >= 7,
        "main value design ceiling": "MAIN_VALUE_SP = 106f" in main,
        "v2.9.3 extreme geometry retained": '@dimen/extreme_width' in card and 'name="extreme_width">56dp' in dims and 'name="extreme_value_reserve">37dp' in dims,
        "extremes are optically right aligned": card.count('android:gravity="center_vertical|end"') >= 4,
        "daylight title typography": '<item name="android:textSize">19sp</item>' in styles and '<item name="android:textSize">14sp</item>' in styles,
        "proven extreme typography retained": '<item name="android:textSize">15sp</item>' in styles and '<item name="android:textSize">22sp</item>' in styles,
        "units are punctuation-free": "unitViews[i].setText(u);" in main and '"bar"' in main,
        "eighteen-dp scale with full tick slot": "Math.round(18 * density)" in scale and '@dimen/range_height' in card and 'name="range_height">42dp' in dims,
        "three-pixel live cursor": "indicatorPaint.setStrokeWidth(Math.max(3f, Math.round(3 * density)))" in scale,
        "muted scale palette separated from alerts": all(token in palette for token in
            ["SCALE_CYAN", "SCALE_NEUTRAL", "SCALE_GREEN", "SCALE_AMBER", "SCALE_RED", "SCALE_PURPLE"]),
        "target band has deadband": "TARGET_BAND_DEADBAND = 0.20f" in scale and "TARGET_BAND_FULL = 1.20f" in scale,
        "static gauge unused sides symmetric": "if (fillLeft > barLeft) canvas.drawRect(barLeft, barTop, fillLeft" in scale
            and "if (fillRight < barRight) canvas.drawRect(fillRight, barTop, barRight" in scale,
        "live colour metadata does not redraw scale": "public void setLiveColor(int color)" in scale
            and "liveColor = color;" in scale and "liveColor = color; invalidate()" not in scale,
        "RC7 daylight capsule shift lamps": "LAMP_WIDTH_DP = 30f" in lens and "LAMP_HEIGHT_DP = 18f" in lens
            and "CORE_WIDTH_DP = 23f" in lens and "CORE_HEIGHT_DP = 11f" in lens,
        "RC7 5+5 optical spacing": "NORMAL_GAP_DP = 8f" in lens and "CENTER_GAP_DP = 80f" in lens and "gap * 5" in lens,
        "no enclosing capsule slot": "#293C47" not in lens and "#0C171D" not in lens,
        "5+5 symmetric pair colours": "RPM_PAIR" in lens and "Math.min(i, 9 - i)" in lens,
        "outside-in five-stage thresholds": "STAGE_RATIOS = {.73f, .83f, .91f, .97f, 1f}" in shift,
        "stage and flash hysteresis": "stageHysteresis" in shift and "flashExitHysteresis" in shift,
        "flash latch retains stage five": "if (flashing)" in shift and "currentStage = 5" in shift,
        "af physical range widened": "{6.5f, 25.0f}" in main,
        "af target/actual gauge": "setTargetTracking(true)" in main and "targetLambda * 14.7f" in main,
        "af target continues through alarm flash": "updateAfTargetMarker(i, targetLambda, color" in main
            and "afFlashing" not in method(main, "private void updateAfTargetMarker("),
        "no 14.7 expansion on af": "setExpand(14.5f, 15.5f, 2.5f)" not in main,
        "fp alarm gated on engine/semantics/persistence": "FuelPressureAlertTracker" in main
            and "fuelPressureAlert.update(engineRunningStable, state" in main,
        "time-based recent decay": "RECENT_DECAY_TAU_MS" in main and "RECENT_DECAY_RATE" not in main,
        "independent recent max/min decay clocks": "recentMaxDecayTimeMs" in main and "recentMinDecayTimeMs" in main
            and "recentDecayTimeMs" not in main,
        "emotion retired from production path": "updateEmotion(i, fVal, state)" not in main,
        "runtime brand mark removed": "brandMark" not in activity,
        "startup brand renderer retained": "StartupBrandRenderer" in overlay,
        "gradient cached not rebuilt per frame": "trackGradient(barLeft, barW)" in scale and "zoneRevision++" in scale,
        "consistent warning red": "DashboardPalette.RPM_PAIR" in lens and "RED = 0xFFFF3045" in palette and "applyOemPalette" in main,
        "critical labels retain priority": "labelPriority" in scale and "heightFittedSize" in scale,
        "overflow cannot auto-rescale": "drawOverflow" in scale and "valToX(current" in scale,
        "full header status target": 'android:layout_width="108dp"' in activity and "@+id/statusDot" in activity,
        "rev view declares full header width": 'android:id="@+id/shiftLight"' in activity and 'android:layout_width="match_parent"' in activity,
        "semantic colors retained": all(token in main for token in
            ["0xFF3FB950", "0xFFD29922", "0xFFFF4444", "0xFF00D8FF", "0xFF888888"]),
        "actual font ink is measured": "getTextBounds" in fitted and "getFontMetrics" in fitted,
        "both current dimensions constrain text": "getWidth()" in fitted and "getHeight()" in fitted,
        "no minimum scale forces overflow": "Math.min(sy, width / fitWidth)" in geometry,
        "native unscaled layout cannot scroll": "super.scrollTo(0, 0)" in fitted,
        "proportional fallback digits are tabular": "Math.max(digitWidth, glyph(c).width)" in fitted,
        "sign ink is optically centered": "glyph.baselineOffset" in fitted,
        "XML classes survive release shrinking": "-keep class io.github.asteroidb612zs.hondatadash.FittedTextView" in proguard,
    }
    failed = [name for name, passed in checks.items() if not passed]
    if failed:
        raise AssertionError("visual contract failed: " + ", ".join(failed))
    print(f"PASS: {len(checks)} fixed-display visual contract assertions")


RECENT_DECAY_PROBE = r'''
public class RecentDecayProbe {
 static final long[] RECENT_PEAK_HOLD_MS = {30000L,30000L,30000L,30000L,30000L,30000L,30000L,30000L};
 static final int EXTREME_MAX=1, EXTREME_MIN=2;
 static final int[] EXTREME_POLICY={3,3,3,3,3,3,3,3};
 static final float RECENT_DECAY_TAU_MS = 3000f;
 final float[] recentMax=new float[8],recentMin=new float[8];
 final long[] recentMaxTime=new long[8],recentMinTime=new long[8];
 final long[] recentMaxDecayTimeMs=new long[8],recentMinDecayTimeMs=new long[8];
 UPDATE_METHOD
 DECAY_METHOD
 static void check(boolean v,String s){if(!v)throw new AssertionError(s);}
 public static void main(String[] args){
  RecentDecayProbe p=new RecentDecayProbe(); int i=5;
  p.recentMax[i]=10f;p.recentMin[i]=0f;p.recentMaxTime[i]=0;p.recentMinTime[i]=0;
  p.recentMaxDecayTimeMs[i]=30000;p.recentMinDecayTimeMs[i]=30000;
  p.updateRecentPeak(i,5f,31000);
  check(p.recentMax[i]<10f&&p.recentMax[i]>5f,"MAX decays");
  check(p.recentMin[i]>0f&&p.recentMin[i]<5f,"MIN decays independently in same sample");
  p.recentMax[i]=10f;p.recentMin[i]=0f;p.recentMaxTime[i]=30500;p.recentMinTime[i]=0;
  p.recentMaxDecayTimeMs[i]=30000;p.recentMinDecayTimeMs[i]=30000;
  p.updateRecentPeak(i,5f,31000);
  check(p.recentMax[i]==10f,"MAX hold remains held");
  check(p.recentMin[i]>0f,"MAX hold cannot reset MIN decay clock");
  System.out.println("PASS: independent recent MAX/MIN decay clocks");
 }
}
'''


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--android-jar", type=Path)
    args = parser.parse_args()
    validate_resources()
    validate_bluetooth_selection_contract()
    validate_visual_contract()
    javac = ["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "--release", "11"]
    with tempfile.TemporaryDirectory(prefix="hondata-refinement-") as temporary:
        temp = Path(temporary)
        sources = []
        for name, source in STUBS.items():
            path = temp / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(str(path))
        main_source = (JAVA / "MainActivity.java").read_text()
        signatures = ["private boolean isDataFresh(", "private void applyAlertAndFreshnessVisuals(",
                      "private void applyCardAlert(", "private void updateFlashState("]
        (temp / "DisplayProbe.java").write_text(DISPLAY_SETUP + "\n".join(method(main_source, s) for s in signatures) + DISPLAY_TESTS)
        format_signatures = ["private String fmt1NoSign(", "private String fmtSigned2(",
                             "private String fmtSigned1(", "private String formatMainText(",
                             "private String formatExtremeText("]
        (temp / "FormatProbe.java").write_text(
            FORMAT_SETUP + "\n".join(method(main_source, s) for s in format_signatures) + FORMAT_TESTS)
        color_probe = temp / "io/github/asteroidb612zs/hondatadash/ColorRecoveryProbe.java"
        color_probe.parent.mkdir(parents=True, exist_ok=True)
        color_probe.write_text(COLOR_PROBE)
        (temp / "ConnectionProbe.java").write_text(CONNECTION_PROBE)
        recent_probe = temp / "RecentDecayProbe.java"
        recent_probe.write_text(RECENT_DECAY_PROBE
            .replace("UPDATE_METHOD", method(main_source, "private void updateRecentPeak("))
            .replace("DECAY_METHOD", method(main_source, "private float decayToward(")))
        sources += [str(temp / "DisplayProbe.java"), str(temp / "FormatProbe.java"),
                    str(color_probe), str(temp / "ConnectionProbe.java"), str(recent_probe), str(JAVA / "ColorRecovery.java")]
        sources += [str(JAVA / "data" / (name + ".java")) for name in
                    ["SensorData", "EngineSemanticState", "EngineStateTracker", "DataSource",
                     "HondataProtocol", "DiagnosticObserver", "BluetoothSource"]]
        run(javac + ["-d", str(temp / "classes")] + sources)
        run(["java", "-cp", str(temp / "classes"), "DisplayProbe"])
        run(["java", "-cp", str(temp / "classes"), "FormatProbe"])
        run(["java", "-cp", str(temp / "classes"), "io.github.asteroidb612zs.hondatadash.ColorRecoveryProbe"])
        run(["java", "-cp", str(temp / "classes"), "ConnectionProbe"])
        run(["java", "-cp", str(temp / "classes"), "RecentDecayProbe"])
        if args.android_jar:
            groups = {}
            production = list(JAVA.rglob("*.java"))
            for path in production:
                for group, name in re.findall(r"(?<!android\.)R\.(\w+)\.(\w+)", path.read_text()):
                    groups.setdefault(group, set()).add(name)
            lines = ["package io.github.asteroidb612zs.hondatadash; public final class R {"]
            value = 1
            for group, names in groups.items():
                lines.append("public static final class " + group + " {")
                for name in sorted(names):
                    lines.append("public static final int " + name + "=" + str(value) + ";")
                    value += 1
                lines.append("}")
            lines.append("}")
            (temp / "R.java").write_text("\n".join(lines))
            run(javac + ["-cp", str(args.android_jar), "-d", str(temp / "android-classes"), str(temp / "R.java")]
                + [str(path) for path in production])
            print("PASS: all production Java compiles against the supplied Android SDK jar")
    print("Not covered: actual Bluetooth stack, Android text rasterization, resource linking, APK installation.")


if __name__ == "__main__":
    main()
