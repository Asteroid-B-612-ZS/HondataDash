#!/usr/bin/env python3
from pathlib import Path
import subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/java/io/github/asteroidb612zs/hondatadash/ColorRecovery.java'
PROBE=r'''package io.github.asteroidb612zs.hondatadash;
public final class Rc7ColorProbe {
 static void ok(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[]a){
  ColorRecovery c=new ColorRecovery(); long t=1000;
  ok(c.update(5,2,t,250)==0,"single danger frame must not paint danger");
  t+=100; ok(c.update(5,1,t,350)==0,"severity chatter must preserve abnormal persistence without instant escalation");
  t+=260; ok(c.update(5,1,t,350)==1,"sustained abnormality must eventually reach amber");
  t+=20; ok(c.update(5,2,t,250)==1,"danger must earn its own attack time");
  t+=260; ok(c.update(5,2,t,250)==2,"sustained danger must surface");
  t+=20; ok(c.update(5,0,t,0)==2,"recovery is intentionally delayed");
  t+=410; ok(c.update(5,0,t,0)==0,"stable recovery clears danger");
  c.reset(5); ok(c.update(5,0,t,0)==0,"reset returns clean state");
  System.out.println("PASS: RC7 colour attack/recovery persistence probe");
 }
}'''
with tempfile.TemporaryDirectory(prefix='rc7-color-') as td:
 p=Path(td); (p/'Rc7ColorProbe.java').write_text(PROBE)
 subprocess.run(['javac','-source','8','-target','8','-Xlint:-options','-d',str(p/'classes'),str(SRC),str(p/'Rc7ColorProbe.java')],check=True)
 subprocess.run(['java','-cp',str(p/'classes'),'io.github.asteroidb612zs.hondatadash.Rc7ColorProbe'],check=True)
