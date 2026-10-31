#!/usr/bin/env python3
"""Website-first OpenStream film. Two independently composed 60 fps formats."""
import json
import shutil
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'assets/v7'
WORDS = json.loads((OUT / 'narration.words.json').read_text())
CUTS = [0, 2.65, 7.55, 12.75, 16.6, 20.0, 24.9, 27.3, 33.5]

im = Image.open(ROOT / 'capture/site-v7/screenshots/scroll-090.png')
for i, (a,b) in enumerate([(56,409),(420,773),(784,1137),(1148,1501),(1512,1865)]):
    im.crop((a,75,b,375)).save(OUT / f'step-{i}.png')

def lines(*texts):
    return ''.join(f'<div class="line"><span>{x}</span></div>' for x in texts)

def browser(img, extra=''):
    return f'<div class="browser {extra}"><div class="bar"><i></i><i></i><i></i><span>os.yash0.in</span><b>↗</b></div><img class="web-plate" src="assets/v7/{img}.png" alt="OpenStream website" /></div>'

def build(portrait=False):
    w,h = (1080,1920) if portrait else (1920,1080)
    label = 'portrait' if portrait else 'landscape'
    logo = '<img class="brand-icon" src="assets/openstream-logo.svg" alt="OpenStream logo" />'
    content = [
        f'<div class="opening-copy"><div class="eyebrow">A BETTER CAMERA. ALREADY YOURS.</div><h1>{lines("Your next", "camera.")}</h1><p>Already in your pocket.</p></div><div class="phone-stage"><div class="phone"><div class="phone-edge"></div><div class="phone-back"><div class="camera-block"><div class="lens"><i></i></div><div class="lens"><i></i></div><div class="lens"><i></i></div><div class="flash"></div></div>{logo}<span>Android → OBS</span></div></div><div class="phone-shadow"></div></div>',
        '<div class="site-title"><div class="eyebrow">MEET OPENSTREAM</div><h2>'+lines('Your phone.', 'Now in OBS.')+'</h2></div><div class="site-stage">'+browser('hero','hero-browser')+'<div class="native-proof"><img src="assets/v7/native.png" alt="Native OBS source card from the website" /></div></div><div class="site-chip">ANDROID → OBS STUDIO <span>•</span> LOCAL WI-FI</div>',
        '<div class="setup-title"><div class="eyebrow">FROM DOWNLOAD TO GO LIVE</div><h2>'+lines('Set up.', '<em>Show up.</em>')+'</h2></div><div class="setup-rail">'+''.join(f'<div class="step step-{i}"><img src="assets/v7/step-{i}.png" alt="Setup step {i+1}" /></div>' for i in range(5))+'</div><div class="install-note">Android app + OBS plugin <span>→</span> Same Wi-Fi</div><div class="live-tag"><i></i> READY TO GO LIVE</div>',
        '<div class="quality-title"><div class="eyebrow">EVERY LENS. YOUR CHOICE.</div><h2 class="quality-number">1080<span>p</span></h2><div class="fps">60 <span>frames / second</span></div><p>Up to 1080p60 • High quality mode</p></div><div class="quality-stack">'+''.join(f'<div class="quality-card qc-{i}"><img src="assets/v7/quality-{i}.png" alt="Quality mode" /></div>' for i in range(3))+'</div>',
        '<div class="control-title"><div class="eyebrow">HANDS OFF THE PHONE</div><h2>'+lines('Lens.', 'Zoom.', '<em>Torch.</em>')+'</h2><p>Right inside OBS.</p></div><div class="dock-stage"><div class="dock-label">THE REAL OBS CAMERA CONTROL DOCK</div><img class="dock" src="assets/obs-dock-panel.png" alt="Real OpenStream OBS camera controls" /><div class="control-ring cr-1"></div><div class="control-ring cr-2"></div><div class="control-ring cr-3"></div></div>',
        '<div class="privacy-copy"><div class="eyebrow">KEEP IT LOCAL</div><h2>'+lines('No capture card.', 'No account.', '<em>No cloud.</em>')+'</h2><div class="own">Your camera. Your network.</div></div><div class="privacy-proof"><img src="assets/v7/local-only.png" alt="OpenStream local-only website feature" /></div>',
        f'<div class="opensource">{logo}<div class="eyebrow">BUILT IN THE OPEN</div><h2>'+lines('Free.', 'Open source.')+'</h2><div class="repo">github.com/YashasVM/OpenStream</div><div class="code-mark">&lt; / &gt;</div></div>',
        '<div class="end-site">'+browser('hero')+'</div><div class="end-copy"><div class="eyebrow">MAKE YOUR NEXT STREAM LOOK BETTER</div><h2>OpenStream</h2><div class="cta">os.yash0.in <span>↗</span></div><p>Free and open source. Android → OBS.</p></div>',
    ]
    scenes = ''.join(f'<section class="clip scene scene-{i}" id="scene-{i}" data-start="{CUTS[i]}" data-duration="{CUTS[i+1]-CUTS[i]:.3f}" data-track-index="{i}"><div class="stage stage-{i}">{s}</div></section>' for i,s in enumerate(content))
    # Phrase subtitles remain readable in platform safe zones. The typed headline is separate.
    groups=[]
    group=[]
    for word in WORDS:
        group.append(word)
        if len(group)>=6 or word['text'].endswith(('.', ',')):
            groups.append(group); group=[]
    if group: groups.append(group)
    captions=''
    for i,g in enumerate(groups):
        start=g[0]['start']+.3
        end=(groups[i+1][0]['start']+.3) if i+1<len(groups) else g[-1]['end']+.65
        captions+=f'<div id="caption-{i}" class="clip caption" data-start="{start:.3f}" data-duration="{end-start:.3f}" data-track-index="12"><span>{" ".join(x["text"] for x in g)}</span></div>'
    sound='<audio id="narration" src="assets/v7/narration.wav" data-start="0.3" data-duration="29.07" data-track-index="10" data-volume="1"></audio>'
    sound+='<audio id="music-bed" src="assets/bgm/car-ad-energy-120bpm.mp3" data-start="0" data-duration="33.5" data-media-start="10" data-track-index="11" data-volume="0.62" data-automation=\'{"version":1,"lanes":[{"target":"volume","points":[{"t":0,"v":0},{"t":0.3,"v":0.62},{"t":29.5,"v":0.62},{"t":32,"v":0.62},{"t":33.5,"v":0}]}]}\'></audio>'
    for i,t in enumerate(CUTS[1:-1]):
        sound+=f'<audio id="sfx-whoosh-{i}" src="assets/sfx/whoosh-short.mp3" data-start="{max(0,t-.12)}" data-duration="0.57" data-track-index="13" data-volume="0.20"></audio>'
    sound+='<audio id="sfx-live" src="assets/sfx/ping.mp3" data-start="12.12" data-duration="0.6" data-volume="0.2" data-track-index="14"></audio>'
    css='''
@font-face{font-family:Inter;src:url('assets/fonts/Inter-500.woff2');font-weight:400 600}
@font-face{font-family:Unbounded;src:url('assets/fonts/Unbounded-var.woff2');font-weight:200 900}
*{box-sizing:border-box}body{margin:0;background:#0e0e0c;color:#f2ede4;font-family:Inter,sans-serif}#root{width:100%;height:100%;position:relative;overflow:hidden;background:#0e0e0c}.clip{position:absolute;inset:0}.stage{position:absolute;inset:0;overflow:hidden;background:#0e0e0c}h1,h2,p{margin:0}h1,h2{font-family:Unbounded,sans-serif;font-weight:800;letter-spacing:-.065em;line-height:1.07}.line{overflow:hidden;padding-bottom:.07em}.line span{display:block}em{font-style:normal;color:#ff5b14}.eyebrow{font-size:20px;letter-spacing:.13em;font-weight:600;margin-bottom:32px;color:#c4bfb4}.brand-icon{width:72px;height:72px;object-fit:contain}p{font-size:30px;color:#c4bfb4;line-height:1.4}.brand{position:absolute;left:70px;top:44px;display:flex;gap:12px;align-items:center;font-size:21px;z-index:20;background:#0e0e0c;padding:9px 14px;border-radius:30px}.brand img{width:28px;height:28px}.edition{position:absolute;right:70px;top:50px;font-size:17px;letter-spacing:.1em;color:#c4bfb4;z-index:20;background:#0e0e0c;padding:9px 14px;border-radius:30px}.progress{position:absolute;height:4px;left:0;bottom:0;width:100%;background:#ff5b14;transform-origin:left;z-index:30}.caption{inset:auto 60px 35px;height:54px;display:flex;justify-content:center;align-items:center;z-index:25}.caption span{background:#0e0e0c;color:#f2ede4;font-size:24px;padding:11px 24px;border:1px solid #46443e;border-radius:12px;line-height:1.2;max-width:100%;text-align:center}
.opening-copy{position:absolute;left:112px;top:278px;width:900px}.opening-copy h1{font-size:124px}.opening-copy p{margin-top:35px}.phone-stage{position:absolute;left:1170px;top:160px;width:430px;height:800px;perspective:1500px}.phone{position:absolute;left:35px;top:20px;width:340px;height:670px;transform-style:preserve-3d}.phone-back{position:absolute;inset:0;border-radius:55px;background:linear-gradient(135deg,#3b3a36,#181815 55%,#292823);border:3px solid #69665e;box-shadow:inset 0 0 0 6px #22221e}.phone-edge{position:absolute;inset:0;background:#66625b;border-radius:55px;transform:translateZ(-22px)}.camera-block{position:absolute;top:35px;left:26px;width:184px;height:258px;background:#121210;border:1px solid #47453f;border-radius:35px;display:grid;grid-template-columns:1fr 1fr;gap:18px;padding:17px;box-shadow:0 12px 22px #0006}.lens{width:65px;height:65px;border-radius:50%;background:radial-gradient(circle at 43% 40%,#506475 0%,#102434 12%,#030604 31%,#3a3933 33%,#121410 42%,#ff5b14 43%,#ff5b14 45%,#151713 47%,#43423c 72%,#10120e 76%);box-shadow:0 4px 8px #000}.lens i{display:block;width:12px;height:12px;margin:13px;border-radius:50%;background:#c8dbed;opacity:.6}.flash{width:27px;height:27px;border-radius:50%;background:#f1e8d1;align-self:center;margin-left:20px}.phone-back>.brand-icon{position:absolute;left:133px;top:435px}.phone-back>span{position:absolute;bottom:70px;width:100%;text-align:center;font-size:17px;letter-spacing:.1em;color:#c4bfb4}.phone-shadow{position:absolute;top:720px;left:15px;width:380px;height:25px;background:#000;border-radius:50%;opacity:.5}
.site-title{position:absolute;left:104px;top:202px;width:580px}.site-title h2{font-size:78px}.site-stage{position:absolute;left:780px;top:200px;width:1030px;perspective:1800px}.browser{width:100%;border:1px solid #535048;border-radius:22px;overflow:hidden;background:#0e0e0c;box-shadow:0 35px 80px #0008}.bar{height:48px;background:#25241f;display:flex;align-items:center;padding:0 20px;gap:8px;color:#c4bfb4;font-size:17px}.bar i{width:9px;height:9px;border-radius:50%;background:#777267}.bar i:first-child{background:#ff5b14}.bar span{margin:auto}.bar b{font-size:18px}.web-plate{width:100%;display:block}.site-chip{position:absolute;left:105px;bottom:178px;font-size:20px;letter-spacing:.09em}.site-chip span{color:#ff5b14;margin:0 14px}
.setup-title{position:absolute;left:110px;top:180px}.setup-title h2{font-size:84px}.setup-rail{position:absolute;left:800px;top:268px;display:flex;gap:18px;perspective:1600px}.step{width:285px;height:243px;border-radius:23px;overflow:hidden}.step img{width:100%;display:block}.step-3,.step-4{display:none}.install-note{position:absolute;left:808px;top:190px;font-size:23px;color:#c4bfb4}.install-note span{color:#ff5b14;margin:0 15px}.live-tag{position:absolute;left:805px;top:610px;border:1px solid #ff5b14;border-radius:50px;padding:22px 36px;font-size:23px;letter-spacing:.06em}.live-tag i{display:inline-block;width:12px;height:12px;background:#ff5b14;border-radius:50%;margin-right:14px}
.quality-title{position:absolute;left:110px;top:244px}.quality-number{font-size:180px;line-height:1.1}.quality-number span{font-size:104px;color:#ff5b14}.fps{font-family:Unbounded,sans-serif;font-size:83px;font-weight:800;letter-spacing:-.06em;color:#ff5b14;margin-top:15px}.fps span{font-family:Inter,sans-serif;font-size:28px;font-weight:500;letter-spacing:0;color:#f2ede4}.quality-title p{font-size:22px;margin-top:34px}.quality-stack{position:absolute;left:1190px;top:225px;width:545px;height:540px;perspective:1800px}.quality-card{position:absolute;width:510px;border-radius:27px;overflow:hidden;border:1px solid #535048;background:#181815}.quality-card img{width:100%;display:block}.qc-0{left:-70px;top:-35px}.qc-1{left:-35px;top:0}.qc-2{left:0;top:35px;border:2px solid #ff5b14}
.control-title{position:absolute;left:110px;top:215px}.control-title h2{font-size:94px}.control-title p{margin-top:25px}.dock-stage{position:absolute;left:1100px;top:180px;width:490px;perspective:1600px}.dock{display:block;width:462px;border-radius:10px;box-shadow:0 30px 80px #0008}.dock-label{font-size:17px;color:#c4bfb4;margin-bottom:20px;letter-spacing:.05em}.control-ring{position:absolute;border:3px solid #ff5b14;border-radius:13px;box-shadow:0 0 0 5px #ff5b1422}.cr-1{left:23px;top:505px;width:418px;height:66px}.cr-2{left:163px;top:560px;width:281px;height:64px}.cr-3{left:23px;top:626px;width:272px;height:58px}
.native-proof{position:absolute;left:20px;right:20px;top:80px;background:#0e0e0c;padding:30px;border-radius:20px}.native-proof img{width:100%;display:block}.stage-5{background:#f2ede4;color:#0e0e0c}.stage-5 em{color:#e14b08}.privacy-copy{position:absolute;left:110px;top:205px}.privacy-copy h2{font-size:97px}.privacy-copy .eyebrow{color:#49473f}.own{font-size:31px;margin-top:36px}.privacy-proof{position:absolute;left:110px;top:765px;width:1700px;border-radius:18px;padding:20px;background:#0e0e0c}.privacy-proof img{width:100%;display:block}
.stage-6{background:#ff5b14;color:#0e0e0c}.opensource{position:absolute;left:110px;top:150px}.opensource>.brand-icon{width:74px;height:74px;filter:brightness(0);margin-bottom:22px}.opensource .eyebrow{color:#28261f}.opensource h2{font-size:137px}.repo{font-size:29px;margin-top:34px}.code-mark{position:absolute;left:1340px;top:125px;font-family:Unbounded,sans-serif;font-size:110px;font-weight:700;white-space:nowrap}
.end-site{position:absolute;left:1210px;top:145px;width:600px;perspective:1800px}.end-site .browser{opacity:.7}.end-copy{position:absolute;left:110px;top:410px;width:1700px}.end-copy h2{font-size:142px}.end-copy .eyebrow{font-size:20px}.cta{display:inline-flex;align-items:center;gap:55px;background:#ff5b14;color:#0e0e0c;font-family:Unbounded,sans-serif;font-size:56px;font-weight:700;letter-spacing:-.045em;padding:25px 38px;border-radius:100px;margin-top:36px}.cta span{font-size:54px}.end-copy p{font-size:24px;margin-top:28px}
'''
    if portrait:
        css+='''
.brand{left:64px;top:115px;font-size:24px}.edition{right:64px;top:120px;font-size:16px}.caption{left:80px;right:130px;bottom:245px;height:90px}.caption span{font-size:30px;padding:14px 23px;line-height:1.25}.eyebrow{font-size:20px;line-height:1.5;letter-spacing:.075em}.opening-copy{left:80px;top:310px;width:850px}.opening-copy h1{font-size:112px}.opening-copy p{font-size:35px}.phone-stage{left:325px;top:820px;width:430px;height:800px}.site-title{left:80px;top:305px;width:900px}.site-title h2{font-size:93px}.site-stage{left:55px;top:790px;width:970px}.site-chip{left:80px;bottom:420px;font-size:22px;letter-spacing:0}.setup-title{left:80px;top:305px}.setup-title h2{font-size:94px}.setup-rail{left:85px;top:770px;gap:18px;flex-wrap:wrap;width:900px}.step{width:280px;height:238px}.install-note{left:85px;top:670px;font-size:26px}.live-tag{left:220px;top:1150px;font-size:30px;padding:26px 34px}.quality-title{left:80px;top:330px}.quality-number{font-size:176px}.fps{font-size:87px}.fps span{font-size:30px}.quality-title p{font-size:26px}.quality-stack{left:292px;top:1000px;width:530px;height:430px}.quality-card{width:510px}.control-title{left:80px;top:300px}.control-title h2{font-size:87px;line-height:1.04}.control-title p{font-size:30px;margin-top:15px}.dock-stage{left:300px;top:830px}.dock-label{font-size:15px}.privacy-copy{left:75px;top:405px;width:925px}.privacy-copy h2{font-size:71px;line-height:1.2}.own{font-size:35px;margin-top:50px}.privacy-proof{left:80px;top:1190px;width:920px;padding:24px}.opensource{left:80px;top:385px;width:900px}.opensource>.brand-icon{width:105px;height:105px;margin-bottom:70px}.opensource h2{font-size:92px;line-height:1.15}.repo{font-size:29px;line-height:1.5;max-width:900px}.code-mark{left:0;top:795px;font-size:92px}.end-site{left:120px;top:315px;width:840px}.end-copy{left:80px;top:975px;width:920px}.end-copy .eyebrow{font-size:18px;letter-spacing:.05em}.end-copy h2{font-size:82px}.cta{font-size:49px;gap:30px;padding:26px 37px;margin-top:45px}.cta span{font-size:49px}.end-copy p{font-size:27px;max-width:830px;line-height:1.5;margin-top:36px}
'''
    js='''const tl=gsap.timeline({paused:true});
const cuts=CUTS;
for(let i=0;i<8;i++){
 const s=cuts[i];
 if(i>0)tl.fromTo('.stage-'+i,{y:95,opacity:0},{y:0,opacity:1,duration:.38,ease:'power3.out'},s);
 if(i<7)tl.to('.stage-'+i,{y:-45,opacity:.0,duration:.17,ease:'power2.in'},cuts[i+1]-.17);
 const sel='.scene-'+i+' .line span';
 if(i!==3&&i!==7)tl.fromTo(sel,{yPercent:112},{yPercent:0,duration:.65,stagger:i===5?.90:.085,ease:'power4.out'},s+.10);
 tl.fromTo('.scene-'+i+' .eyebrow',{y:15,opacity:0},{y:0,opacity:1,duration:.45,ease:'power3.out'},s+.1);
}
tl.fromTo('.phone',{rotationY:-38,rotationZ:8,y:140,opacity:0},{rotationY:12,rotationZ:-7,y:0,opacity:1,duration:1.15,ease:'power3.out'},0);
tl.to('.phone',{rotationY:-5,rotationZ:-3,y:-20,duration:1.3,ease:'sine.inOut'},1.15);
tl.fromTo('.opening-copy p',{y:25,opacity:0},{y:0,opacity:1,duration:.45},1.45);
tl.fromTo('.site-stage .browser',{rotationY:-17,rotationX:10,y:100,scale:.82,opacity:0},{rotationY:0,rotationX:0,y:0,scale:1,opacity:1,duration:1.1,ease:'power3.out'},2.65);
tl.to('.site-stage .browser',{scale:1.03,y:-12,duration:3.5,ease:'none'},3.75);
tl.fromTo('.native-proof',{opacity:0,y:60,scale:.9},{opacity:1,y:0,scale:1,duration:.7,ease:'power3.out'},5.25);
tl.fromTo('.site-chip',{y:15,opacity:0},{y:0,opacity:1,duration:.5},5.4);
tl.fromTo('.step',{y:130,rotationX:30,opacity:0},{y:0,rotationX:0,opacity:1,duration:.55,stagger:.18,ease:'power3.out'},7.75);
tl.fromTo('.install-note',{opacity:0},{opacity:1,duration:.35},7.8);
tl.to('.step-1',{scale:1.045,duration:.3,ease:'power2.out'},9.05);
tl.to('.step-1',{scale:1,duration:.3},9.7);
tl.to('.step-2',{scale:1.045,duration:.3,ease:'power2.out'},10.2);
tl.to('.step-2',{scale:1,duration:.3},10.95);
tl.fromTo('.live-tag',{scale:.9,opacity:0,y:35},{scale:1,opacity:1,y:0,duration:.48,ease:'back.out(1.3)'},11.65);
tl.fromTo('.quality-number',{y:75,opacity:0},{y:0,opacity:1,duration:.55,ease:'power3.out'},13.72);
tl.fromTo('.fps',{y:50,opacity:0},{y:0,opacity:1,duration:.5,ease:'power3.out'},15.1);
tl.fromTo('.quality-card',{rotationY:-20,y:90,opacity:0},{rotationY:0,y:0,opacity:1,duration:.65,stagger:.12,ease:'power3.out'},12.8);
tl.to('.qc-0',{x:-25,y:-30,rotation:-6,duration:1.8,ease:'power2.inOut'},13.6);
tl.to('.qc-1',{x:-10,y:-15,rotation:-3,duration:1.8,ease:'power2.inOut'},13.6);
tl.fromTo('.dock',{rotationY:17,y:70,opacity:0},{rotationY:0,y:0,opacity:1,duration:.7,ease:'power3.out'},16.65);
tl.fromTo('.cr-1',{opacity:0,scale:.94},{opacity:1,scale:1,duration:.24},17.0);
tl.to('.cr-1',{opacity:0,duration:.2},17.57);
tl.fromTo('.cr-2',{opacity:0,scale:.94},{opacity:1,scale:1,duration:.24},17.61);
tl.to('.cr-2',{opacity:0,duration:.2},18.1);
tl.fromTo('.cr-3',{opacity:0,scale:.94},{opacity:1,scale:1,duration:.24},18.13);
tl.to('.cr-3',{opacity:0,duration:.3},19.35);
tl.fromTo('.privacy-proof',{y:70,opacity:0},{y:0,opacity:1,duration:.65,ease:'power3.out'},22.3);
tl.fromTo('.own',{y:35,opacity:0},{y:0,opacity:1,duration:.55},23.0);
tl.fromTo('.opensource>.brand-icon',{rotation:-90,scale:.5,opacity:0},{rotation:0,scale:1,opacity:1,duration:.6,ease:'power3.out'},24.9);
tl.fromTo('.repo',{y:25,opacity:0},{y:0,opacity:1,duration:.45},25.55);
tl.fromTo('.code-mark',{x:45,opacity:0},{x:0,opacity:1,duration:.6,ease:'power3.out'},25.3);
tl.fromTo('.end-site .browser',{rotationY:15,rotationX:-8,scale:.85,y:60,opacity:0},{rotationY:0,rotationX:0,scale:1,y:0,opacity:.7,duration:.85,ease:'power3.out'},27.3);
tl.fromTo('.end-copy h2',{y:60,opacity:0},{y:0,opacity:1,duration:.6,ease:'power4.out'},27.5);
tl.fromTo('.cta',{scale:.9,y:25,opacity:0},{scale:1,y:0,opacity:1,duration:.6,ease:'back.out(1.2)'},28.0);
tl.fromTo('.end-copy p',{opacity:0,y:15},{opacity:1,y:0,duration:.5},28.5);
tl.to('.end-site .browser',{y:-10,scale:1.025,duration:4.4,ease:'sine.inOut'},28.2);
tl.fromTo('.progress',{scaleX:0},{scaleX:1,duration:33.5,ease:'none'},0);
window.__timelines['openstream-v7-LABEL']=tl;
'''.replace('CUTS',json.dumps(CUTS)).replace('LABEL',label)
    return f'''<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width={w},height={h}"><title>OpenStream • Your next camera</title><script src="assets/v7/gsap.min.js"></script><style>{css}</style></head><body><div id="root" data-composition-id="openstream-v7-{label}" data-width="{w}" data-height="{h}" data-duration="33.5" data-fps="60"><div class="brand">{logo}OpenStream</div><div class="edition">ANDROID → OBS</div>{scenes}{captions}{sound}<div class="progress"></div></div><script>{js}</script></body></html>'''

if not (ROOT/'archive/index-v6.html').exists():
    shutil.copy2(ROOT/'index.html',ROOT/'archive/index-v6.html')
(ROOT/'index.html').write_text(build())
portrait_dir=ROOT/'formats/portrait'
portrait_dir.mkdir(parents=True,exist_ok=True)
(portrait_dir/'index.html').write_text(build(True))
if not (portrait_dir/'assets').exists(): (portrait_dir/'assets').symlink_to('../../assets',target_is_directory=True)
(portrait_dir/'hyperframes.json').write_text(json.dumps({'authoringSkill':'product-launch-video'}))
print('Built landscape + portrait, 33.5 seconds at 60 fps')
