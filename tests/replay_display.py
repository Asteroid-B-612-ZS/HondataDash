#!/usr/bin/env python3
"""50Hz BT42-profile display comparison. Not an Android event-loop/device replay.
Inputs are explicitly normalized derivatives, never original Core14 files.
Usage: python3 tests/replay_display.py --inputs DIR --source-ref baseline|current
"""
from pathlib import Path
import argparse,re,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JAVA=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash'
BASE='c077201222bb18c300ca4f10639c8063907aa2c3'
def method(s,signature):
 start=s.index(signature);brace=s.index('{',start);depth=0
 for i in range(brace,len(s)):
  depth+=(s[i]=='{')-(s[i]=='}')
  if depth==0:return s[start:i+1]

BODY=r'''
  public static void main(String[] args) throws Exception {
    for (String file:args) new Replay().run(file);
  }
  void run(String file) throws Exception {
    EngineStateTracker tracker=new EngineStateTracker();
    CombustionDisplayAdmission gate=new CombustionDisplayAdmission();
    ColorRecovery colors=colorRecovery;
    float[] ema={Float.NaN,Float.NaN}; long[] update={0,0}; int[] shown={0,0};
    long ignGuard=0, start=1000, previousFrame=-1; boolean stale=false;
    BufferedReader in=new BufferedReader(new FileReader(file)); in.readLine();
    PrintWriter out=new PrintWriter(file.replace("_replay.csv",OUTPUT_SUFFIX));
    out.println("t_ms,main,modifier,combustion,shift,confidence,hold_af,hold_ign,af_context,af_red,ign_red,ign_ema,gray,plausible,low_context,calm_ign,af_level,ign_level");
    String line;
    while ((line=in.readLine())!=null) {
      String[] v=line.split(",");double[] x=new double[v.length];
      for(int i=0;i<x.length;i++) x[i]=v[i].isEmpty()?Double.NaN:Double.parseDouble(v[i]);
      long now=(long)x[0]+1000;android.os.SystemClock.now=now;
      if(previousFrame>=0 && now-previousFrame>500)stale=true;previousFrame=now;
      SensorData d=new SensorData();
      int[] ids={0,HondataProtocol.CID_RPM,HondataProtocol.CID_Speed,HondataProtocol.CID_Gear,HondataProtocol.CID_MAP,
        HondataProtocol.CID_ThrottlePlate,HondataProtocol.CID_Inj,HondataProtocol.CID_ClosedLoop,HondataProtocol.CID_TargetLambda,
        HondataProtocol.CID_Lambda,HondataProtocol.CID_ECT,HondataProtocol.CID_Ign,HondataProtocol.CID_STrim};
      for(int i=1;i<ids.length;i++) d.put(ids[i],x[i]);
      d.put(HondataProtocol.CID_TPS,x[13]);d.put(HondataProtocol.CID_KnockRetard,x[15]);d.put(HondataProtocol.CID_PA,x[18]);
      // BT42 replay deliberately omits Clutch.Pos even though CSV contains it.
      boolean plausible=isSemanticFramePlausible(d);
      if(x[19]==0 || !plausible){stale=true;out.println((long)x[0]+",INVALID,NONE,INVALID,NONE,0,true,true,false,false,false,NaN,true,false,false,false,0,0");continue;}
      if(stale){tracker.reset();gate.requireReacquire(now);colors.reset(5);colors.reset(6);ema[0]=ema[1]=Float.NaN;shown[0]=shown[1]=0;stale=false;}
      EngineSemanticState state=tracker.update(d);
      CombustionDisplayAdmission.Snapshot a=gate.update(state,d,now);
      lowLoadDisplay.update(state,d,now);
      boolean afContext=isAfColorContext(state,d), gray=isWarmupLowReference(state,d)
        || (now-start>=4000 && !state.isIdle() && isDynamicLowReference(state));
      for(int k=0;k<2;k++){
        int card=5+k;
        if(a.holdsCard(card)){colors.reset(card);shown[k]=0;continue;}
        if(a.releasedCard(card)){ema[k]=Float.NaN;update[k]=0;if(k==1)ignGuard=now+500;}
        float value=(float)(k==0?x[9]*14.7:x[11]);
        boolean valid= k==0 ? value>=6.5f && value<=25f : value>=-25f && value<=55f;
        if(!valid){if(k==1&&now<=ignGuard){colors.reset(card);shown[k]=0;}continue;}
        float alpha=k==0?state.afAlpha():0.4f;
        ema[k]=Float.isNaN(ema[k])?value:alpha*value+(1-alpha)*ema[k];
        int ignSeverity=0;
        if(k==1){updateMainColorState(6,ema[k],now,state,d);int c=resolvedMainColors[6];ignSeverity=c==COLOR_DANGER?2:c==COLOR_WARN?1:0;}
        long interval=shouldUseFastCombustionRefresh(state,d)?50:100;
        if(now-update[k]>=interval){
          update[k]=now;
          if(k==0){
            int desired=afContext?getAfSeverity((float)x[9],(float)x[8],state):0;
            int level;
            if(afContext) level=colors.update(5,desired,now,AF_ATTACK);
            else{colors.reset(5);level=0;}
            shown[0]=desired>level?0:level;
            // WOT flashing takes priority over confidence gray.
            if(gray && !(state.isWot()&&afContext&&level>=2))shown[0]=0;
          }else shown[1]=gray?0:ignSeverity;
        }
      }
      out.println((long)x[0]+","+state.main+","+state.modifier+","+state.combustion+","+state.shiftPhase+","+state.confidence+","+a.holdAf+","+a.holdIgn+","+afContext+","+(shown[0]>=2)+","+(shown[1]>=2)+","+ema[1]+","+gray+",true,"+lowLoadDisplay.isActive()+","+lowLoadDisplay.calmIgn()+","+shown[0]+","+shown[1]);
    }
    in.close();out.flush();if(out.checkError())throw new IOException("Replay output write failed");out.close();System.out.println("Replayed "+file);
  }
}
'''

def main():
 a=argparse.ArgumentParser();a.add_argument('--inputs',required=True,type=Path);a.add_argument('--source-ref',choices=['baseline','current'],required=True);args=a.parse_args()
 def read(path):
  return subprocess.check_output(['git','show',BASE+':'+str(path.relative_to(ROOT))],cwd=ROOT,text=True) if args.source_ref=='baseline' else path.read_text()
 s=read(JAVA/'MainActivity.java')
 signatures=['private boolean isAfColorContext(','private int getAfSeverity(','private long getAfAttackMs(', 'private int severityColor(', 'private int getIgnSemanticColor(', 'private boolean shouldUseFastCombustionRefresh(', 'private boolean isWarmupLowReference(', 'private boolean isDynamicLowReference(', 'private boolean isSemanticFramePlausible(', 'private boolean isSignedMainCard(', 'private void updateMainColorState(', 'private boolean isStableTrimColorContext(', 'private int getMapSemanticColor(', 'private int getTrimSemanticColor(']
 methods='\n'.join(method(s,n) for n in signatures)
 constants='\n'.join(line for line in s.splitlines() if re.search(r'private static final (float|int|long) (WOT_LAMBDA_|CL_LAMBDA_|IGN_GREEN_|IGN_WARN_|COLOR_SAFE|COLOR_WARN|COLOR_DANGER|COLOR_TEXT_NORMAL|WARMUP_ECT_|LOW_CONFIDENCE_THRESHOLD|MAP_GREEN_|MAP_WARN_|TRIM_GREEN_|TRIM_WARN_)',line))
 with tempfile.TemporaryDirectory(prefix='hondata-replay-') as tmp:
  dest=Path(tmp);pkg=dest/'io/github/asteroidb612zs/hondatadash';(pkg/'data').mkdir(parents=True);(dest/'android/os').mkdir(parents=True)
  (dest/'android/os/SystemClock.java').write_text('package android.os; public final class SystemClock { public static long now=1000L; public static long elapsedRealtime(){return now;} }')
  for n in ['EngineStateTracker','EngineSemanticState','SensorData','HondataProtocol','CombustionDisplayAdmission']:(pkg/'data'/(n+'.java')).write_text(read(JAVA/'data'/(n+'.java')))
  for n in ['ColorRecovery','DashboardPalette']:(pkg/(n+'.java')).write_text(read(JAVA/(n+'.java')))
  (pkg/'LowLoadDisplayContext.java').write_text((JAVA/'LowLoadDisplayContext.java').read_text())
  header='package io.github.asteroidb612zs.hondatadash; import io.github.asteroidb612zs.hondatadash.data.*; import java.io.*; public final class Replay { private final LowLoadDisplayContext lowLoadDisplay=new LowLoadDisplayContext(); private final ColorRecovery colorRecovery=new ColorRecovery(); private final int[] resolvedMainColors=new int[8];'
  suffix='"_'+args.source_ref+'_result.csv"'
  attack='getAfAttackMs(desired,state)' if args.source_ref=='baseline' else 'lowLoadDisplay.afAttackMs(desired,getAfAttackMs(desired,state))'
  (pkg/'Replay.java').write_text(header+constants+methods+BODY.replace('OUTPUT_SUFFIX',suffix).replace('AF_ATTACK',attack))
  subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','--release','11','-d',str(dest/'classes'),*map(str,dest.rglob('*.java'))],check=True)
  inputs=sorted(args.inputs.glob('*_replay.csv'));assert inputs
  subprocess.run(['java','-Xmx512m','-cp',str(dest/'classes'),'io.github.asteroidb612zs.hondatadash.Replay',*map(str,inputs)],check=True)
if __name__=='__main__':main()
