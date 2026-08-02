import type {Camera,CameraSetting,StudioAdapter,StudioCommand,StudioSnapshot,CommandResult} from './types';

const capability=(missing:CameraSetting[]=[])=>(Object.fromEntries(['lens','zoom','iso','shutter','whiteBalance','focus','torch','stabilization'].map(k=>[k,!missing.includes(k as CameraSetting)])) as Camera['capabilities']);
const controls=(caps:Camera['capabilities'])=>(Object.fromEntries(Object.entries({lens:'Wide',zoom:1,iso:400,shutter:'1/60',whiteBalance:4800,focus:'Auto',torch:false,stabilization:true}).map(([k,v])=>[k,{value:v,applied:v,state:caps[k as CameraSetting]?'confirmed':'unsupported',supported:caps[k as CameraSetting],reason:caps[k as CameraSetting]?undefined:'Not reported by this phone'}])) as Camera['controls']);
function camera(id:string,name:string,missing:CameraSetting[]=[],tally:Camera['tally']='none'):Camera{const caps=capability(missing);return{id,name,online:true,signal:92-Number(id)*5,battery:96-Number(id)*7,temperature:36+Number(id),latencyMs:65+Number(id)*8,tally,frameHeld:false,capabilities:caps,controls:controls(caps)}}
export const initialSnapshot=():StudioSnapshot=>({revision:12,connection:'connected',readiness:{engine:true,phone:true,obs:true,virtualCamera:false},cameras:[camera('1','Main Wide',[],'program'),camera('2','Stage Left',['torch'],'preview'),camera('3','Stage Right',['iso','shutter']),camera('4','Close Up',['stabilization'])],audio:[1,2,3,4].map((n)=>({id:String(n),name:`Phone ${n} microphone`,program:n===1,gain:n===1?2:0,muted:false,solo:false,level:-18-n*3,syncMs:n*4-6,health:n===4?'warning':'healthy'})),previewId:'2',programId:'1',transition:'cut',alerts:[{id:'gap',severity:'critical',title:'Recording gap',detail:'Camera 3 · 2.3 seconds · recovery pending',route:'/cameras#camera-3',action:'Open camera 3'},{id:'thermal',severity:'warning',title:'Thermal warning',detail:'Camera 4 is warm (40°C)',route:'/cameras#camera-4',action:'Review thermal health'}]});

export class DeterministicStudioAdapter implements StudioAdapter{
 private snapshot=initialSnapshot(); private listeners=new Set<(s:StudioSnapshot)=>void>();
 getSnapshot(){return structuredClone(this.snapshot)} subscribe(l:(s:StudioSnapshot)=>void){this.listeners.add(l);return()=>this.listeners.delete(l)}
 private emit(){this.listeners.forEach(l=>l(this.getSnapshot()))}
 async dispatch(command:StudioCommand):Promise<CommandResult>{
  if(command.expectedRevision!==this.snapshot.revision)return{status:'conflict',revision:this.snapshot.revision,reason:'State changed; refreshed from engine snapshot'};
  const next=structuredClone(this.snapshot); const result:CommandResult={status:'confirmed',revision:next.revision+1};
  if(command.type==='camera.set'){const c=next.cameras.find(x=>x.id===command.cameraId)!;const ctl=c.controls[command.setting];if(!ctl.supported)return{status:'unsupported',revision:next.revision,reason:ctl.reason};ctl.value=command.value;ctl.state='pending';this.snapshot=next;this.emit();await Promise.resolve();ctl.applied=command.value;ctl.state='confirmed'}
  if(command.type==='audio.program'){next.audio.forEach(a=>a.program=a.id===command.sourceId&&command.enabled)}
  if(command.type==='audio.patch'){const a=next.audio.find(x=>x.id===command.sourceId)!;if(command.gain!==undefined)a.gain=command.gain;if(command.muted!==undefined)a.muted=command.muted;if(command.solo!==undefined)a.solo=command.solo}
  if(command.type==='preview.set'){const target=next.cameras.find(c=>c.id===command.cameraId);if(!target?.online)return{status:'rejected',revision:next.revision,reason:'Offline cameras cannot enter Preview'};next.previewId=command.cameraId}
  if(command.type==='transition'){const target=next.cameras.find(c=>c.id===next.previewId);if(!target?.online)return{status:'rejected',revision:next.revision,reason:'Preview camera is offline; Program remains unchanged'};next.transition=command.kind;const p=next.programId;next.programId=next.previewId;next.previewId=p;next.cameras.forEach(c=>c.tally=c.id===next.programId?'program':c.id===next.previewId?'preview':'none')}
  if(command.type==='simulate.failure'){const c=next.cameras.find(x=>x.id===command.cameraId)!;c.online=false;c.frameHeld=c.id===next.programId;if(c.frameHeld)next.alerts.unshift({id:'live-failure',severity:'critical',title:'Program camera lost — frame held',detail:`${c.name} disconnected. Last Program frame remains on air.`,route:'/studio',action:'Choose another Preview source'})}
  next.revision++;this.snapshot=next;this.emit();return{...result,revision:next.revision};
 }
}
