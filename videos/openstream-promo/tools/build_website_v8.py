#!/usr/bin/env python3
"""Animate the captured website DOM with a single seekable 3D camera."""
import json
import re
from pathlib import Path
from bs4 import BeautifulSoup

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parents[1]
DEST = ROOT / 'formats/website-vertical'
DEST.mkdir(parents=True, exist_ok=True)
if not (DEST / 'assets').exists():
    (DEST / 'assets').symlink_to('../../assets', target_is_directory=True)

doc = BeautifulSoup((ROOT / 'capture/site-v7/extracted/page.html').read_text(), 'html.parser')
main = doc.select_one('main')
main['id'] = 'website'
for tag in main.select('[style]'):
    declarations = [x for x in tag['style'].split(';') if x.strip().startswith('--')]
    if declarations:
        tag['style'] = ';'.join(declarations)
    else:
        del tag['style']
for tag in main.select('img'):
    tag['src'] = 'assets/v8/site-logo.png'
for text in main.select('.stack-card h3,.stack-card p,.sc-head span'):
    text['data-layout-allow-overlap']=''
    text['data-layout-allow-occlusion']=''
for i,tag in enumerate(main.select('.stack-card')):
    tag['id'] = f'pipeline-{i}'
for i,tag in enumerate(main.select('.frow')):
    tag['id'] = f'feature-{i}'
for i,tag in enumerate(main.select('.qcol')):
    tag['id'] = f'quality-{i}'
for i,tag in enumerate(main.select('.num,.move,.dl,.inst-steps li')):
    tag['id'] = f'site-element-{i}'

css = (REPO / 'website/src/styles.css').read_text().replace('infinite', '12')
css = re.sub(r'transition\s*:[^;{}]+;', '', css)
css = css.replace('"Unbounded Variable"', 'Unbounded').replace('"Inter Variable"', 'Inter')
css = '''@font-face{font-family:Unbounded;src:url('assets/fonts/Unbounded-var.woff2');font-weight:200 900}
@font-face{font-family:Inter;src:url('assets/v8/Inter-variable.woff2');font-weight:100 900}
''' + css
css += '''
html,body{width:100%;height:100%;overflow:hidden;background:#0e0e0c}
#root{position:relative;width:100%;height:100%;overflow:hidden;background:#0e0e0c}
.clip{position:absolute;inset:0}
#perspective{position:absolute;inset:0;perspective:2200px;perspective-origin:50% 50%}
#camera{position:absolute;inset:0;transform-style:preserve-3d;transform-origin:540px 960px}
#travel{position:relative;width:1080px;transform-style:preserve-3d;transform-origin:540px 0}
#website{position:relative;width:1080px;background:#0e0e0c;box-shadow:0 0 0 1px #36332d;transform-style:preserve-3d;--pad:48px}
#website .hero{min-height:1200px;padding-top:36px}
#website .hero-title{font-size:101px;padding-top:90px}
#website .hero-foot{padding-top:50px;padding-bottom:50px}
#website .hero-top{font-size:19px}
#website .hero-top .logo{font-size:22px}
#website .hero-foot p{font-size:26px;max-width:550px}
#website .hero-btns a{font-size:22px;height:70px}
#website .hero-title .hl{transform-style:preserve-3d}
#website .manifesto{padding-top:150px;padding-bottom:130px}
#website .scrollwords{font-size:51px;line-height:1.12}
#website .label{font-size:20px;color:#b8b2a6;margin-bottom:36px}
#website .num{padding:28px 22px;min-height:255px}
#website .num strong{font-size:77px}
#website .num>span{font-size:19px;color:#bab3a6}
#website .num-o>span{color:#29251d}
#website .how{padding-top:150px}
#website .how-ports span{font-size:18px}
#website .stack{height:1320px;position:relative;margin-top:55px;transform-style:preserve-3d}
#website .stack-slot{position:absolute;top:90px;width:100%;height:700px;transform-style:preserve-3d}
#website .stack-card{top:0!important;width:984px;height:565px;padding:46px;grid-template-columns:1.1fr 1fr;grid-template-rows:auto auto 1fr;transform-style:preserve-3d}
#website .stack-card h3{font-size:57px}
#website .stack-card p{font-size:23px;line-height:1.35}
#website .sc-head{font-size:19px}
#website .sc-n{font-size:20px}
#website .sc-k{font-size:18px}
#website .features{padding-top:110px}
#website .frow{grid-template-columns:48px 1fr 1.08fr 24px;gap:20px;padding:44px 0}
#website .frow h3{font-size:35px}
#website .frow p{font-size:23px;color:#b8b2a6;line-height:1.35}
#website .fn{font-size:18px;color:#b8b2a6}
#website .quality{padding-top:145px}
#website .qcol{padding:30px 23px;min-height:440px}
#website .qcol strong{font-size:68px;padding-top:100px}
#website .qn{font-size:22px;color:#b8b2a6}
#website .qnote{font-size:23px;color:#b8b2a6;line-height:1.3}
#website .qcol.on .qn,#website .qcol.on .qnote{color:#49443b}
#website .qfps{font-size:23px;color:#ff5b14}
#website .install{margin-top:160px;padding-top:85px;padding-bottom:85px}
#website .plat-name{font-size:51px}
#website .plats{gap:35px}
#website .plat-side{font-size:18px}
#website .inst-body{grid-template-columns:1fr 1.1fr;gap:34px}
#website .req{font-size:20px}
#website .inst-steps h4{font-size:22px}
#website .inst-steps p{font-size:21px}
#website .dl-l strong{font-size:23px}
#website .dl-l small{font-size:19px}
#website .inst-note,#website .inst-all{font-size:21px}
#website .moves{grid-template-columns:repeat(3,1fr);gap:16px;margin-top:50px}
#website .move{min-height:340px;padding:26px 22px}
#website .move h4{font-size:22px}
#website .move p{font-size:21px;color:#b8b2a6;line-height:1.35}
#website .faq{padding-top:100px}
#website .fq button{font-size:27px}
#website .footer{padding-top:110px!important;padding-bottom:80px}
#website .foot-cta{padding-top:60px;padding-bottom:100px}
#website .foot-cta .btn-o{height:100px;font-size:30px;padding:0 65px}
#website .foot-word{font-size:121px}
#website .foot-row{font-size:20px;padding:48px}
#website .mq-big .mq-set span{font-size:128px}
#website .badge{transform-style:preserve-3d}
#website .btn-o,#website .btn-k,#website .frow,#website .qcol,#website .plats button,#website .move,#website .dl{transition:none}
'''

script = '''
document.fonts.ready.then(()=>{
 const tl=gsap.timeline({paused:true});
 const site=document.getElementById('website');
 const box=s=>{let e=document.querySelector(s),top=0;const height=e.offsetHeight;while(e&&e!==site){top+=e.offsetTop;e=e.offsetParent;}return {top,height}};
 const center=s=>{const b=box(s);return b.top+b.height/2};
 const point=(selector,ratio=.5)=>{const b=box(selector);return b.top+b.height*ratio};
 const camera=(target,at,duration,scale=1,ry=0,rx=0,x=0)=>{
   tl.to('#travel',{y:960-target,duration,ease:'power2.inOut'},at);
   tl.to('#camera',{scale,rotationY:ry,rotationX:rx,x,duration,ease:'power2.inOut'},at);
 };
 const drop=(selector,at,stagger=.09)=>tl.fromTo(selector,{y:-170,z:220,rotationX:18,opacity:0},{y:0,z:0,rotationX:0,opacity:1,duration:.85,stagger,ease:'power3.out'},at);

 // Actual page, already filling the opening frame. Only the website moves.
 tl.fromTo('#travel',{y:220},{y:220,duration:.1},0);
 tl.fromTo('#camera',{scale:.92,rotationY:-11,rotationX:4,x:0},{scale:.98,rotationY:0,rotationX:0,x:0,duration:2.3,ease:'power2.inOut'},0);
 drop('.hero-top > *',0,.10);
 drop('.hero-title .hl',.25,.24);
 tl.fromTo('.hero-title .mask > span',{yPercent:-112},{yPercent:0,duration:.65,stagger:.011,ease:'power3.out'},.32);
 drop('.hero-foot > *',1.75,.16);
 tl.fromTo('.hero .marquee',{y:80,opacity:0},{y:0,opacity:1,duration:.8,ease:'power3.out'},2.0);
 tl.to('.hero .mq-track',{x:-750,duration:39.5,ease:'none'},0);
 tl.to('.badge',{rotation:125,duration:5.5,ease:'none'},.5);

 camera(point('.hero-title',.65),2.4,1.5,1.04,7,-2);
 camera(center('.manifesto'),4.5,1.5,.94,-6,2);
 tl.fromTo('.scrollwords>span',{opacity:.16},{opacity:1,stagger:.021,duration:.35},5.2);
 drop('.num',5.45,.12);

 const cardFocus=point('.stack',.10)+325;
 camera(cardFocus,6.1,1.0,.96,7,2);
 const cards=document.querySelectorAll('.stack-card');
 for(let i=0;i<4;i++){
   tl.set(cards[i],{opacity:0},0);
   tl.set(cards[i],{opacity:1},6.55+i*1.45);
   tl.fromTo(cards[i],{y:-240+i*28,z:230,rotationX:20,rotationY:-8},{y:i*28,z:0,rotationX:0,rotationY:0,duration:.95,ease:'power3.out'},6.55+i*1.45);
   if(i>0)tl.to(cards[i-1].querySelectorAll('.sc-head,h3,p,.sc-art'),{opacity:0,duration:.25},6.55+i*1.45+.3);
   if(i>0)tl.to(cards[i-1],{scale:.96-i*.014,y:(i-1)*28-18,duration:.7,ease:'power2.inOut'},6.55+i*1.45);
 }
 camera(cardFocus+60,8.2,2.0,1.01,-7,-2);
 camera(cardFocus+85,10.3,1.5,1.04,4,0);

 camera(point('.features',.75),12.0,1.3,.96,-6,1);
 drop('.features .frows>div',12.5,.13);
 camera(center('#feature-5'),13.55,1.0,1.08,5,0);
 camera(point('.features',.38),15.0,1.2,1.0,-6,1);
 tl.fromTo('#feature-0 h3,#feature-1 h3',{z:0},{z:28,duration:1.1,stagger:.1,ease:'power2.inOut'},16.0);
 camera(point('.features',.52),17.0,1.9,.97,4,-1);

 camera(center('.quality'),19.45,1.15,.92,-8,2);
 drop('.qcol',19.8,.22);
 camera(center('#quality-2'),22.1,1.25,1.08,5,-1,-285);
 tl.to('#quality-2',{z:50,y:-12,duration:.7,ease:'power2.inOut'},23.0);
 camera(center('.quality'),24.4,1.0,.94,0,0);

 camera(center('.install'),25.35,1.2,.95,-7,1);
 drop('.plats button',20.55,.10);
 drop('.inst-body > *',20.75,.12);
 camera(point('.golive',.50),27.0,1.2,.93,6,-1);
 drop('.move',27.3,.14);
 camera(center('.moves'),28.6,1.1,.97,-3,1);

 // The open-source badge belongs to the original site. Return to it, then
 // glide down to the site's own download button and orange wordmark.
 camera(center('.footer'),30.0,1.6,.92,-7,2);
 drop('.foot-cta,.foot-word,.foot-row',31.0,.14);
 tl.to('.footer .mq-track',{x:-900,duration:9.5,ease:'none'},30);
 camera(point('.footer',.62),32.15,2.0,1.04,3,-1);
 camera(point('.footer',.62)+15,35.0,4.5,1.0,0,0);
 window.__timelines['openstream-website-v8']=tl;
});
'''

audio='''<audio id="narration" src="assets/v7/website-narration.wav" data-start="0.3" data-duration="35.5" data-track-index="1" data-volume="1"></audio>
<audio id="music-bed" src="assets/bgm/car-ad-energy-120bpm.mp3" data-start="0" data-duration="39.5" data-media-start="8" data-track-index="2" data-volume="0.6" data-automation='{"version":1,"lanes":[{"target":"volume","points":[{"t":0,"v":0},{"t":0.6,"v":0.6},{"t":36,"v":0.6},{"t":38,"v":0.6},{"t":39.5,"v":0}]}]}'></audio>'''

html=f'''<!doctype html><html lang="en"><head><meta charset="UTF-8"><meta name="viewport" content="width=1080,height=1920"><title>OpenStream • website in motion</title><script src="assets/v7/gsap.min.js"></script><style>{css}</style></head><body><div id="root" data-composition-id="openstream-website-v8" data-width="1080" data-height="1920" data-duration="39.5" data-fps="60"><div id="film" class="clip" data-start="0" data-duration="39.5" data-track-index="0" data-layout-allow-overflow><div id="perspective"><div id="camera"><div id="travel">{main}</div></div></div></div>{audio}</div><script>{script}</script></body></html>'''
(DEST / 'index.html').write_text(html)
(DEST / 'hyperframes.json').write_text(json.dumps({'authoringSkill':'product-launch-video'}))
print(DEST)
