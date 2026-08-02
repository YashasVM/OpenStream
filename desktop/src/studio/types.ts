export type CommandState='confirmed'|'pending'|'rejected'|'unsupported'|'conflict'|'reconnecting';
export type CameraSetting='lens'|'zoom'|'iso'|'shutter'|'whiteBalance'|'focus'|'torch'|'stabilization';
export interface Control<T=string|number|boolean>{value:T; applied:T; state:CommandState; supported:boolean; reason?:string}
export interface Camera {id:string;name:string;online:boolean;signal:number;battery:number;temperature:number;latencyMs:number;tally:'program'|'preview'|'none';frameHeld:boolean;capabilities:Record<CameraSetting,boolean>;controls:Record<CameraSetting,Control>}
export interface AudioSource{id:string;name:string;program:boolean;gain:number;muted:boolean;solo:boolean;level:number;syncMs:number;health:'healthy'|'warning'|'offline'}
export interface Alert{id:string;severity:'critical'|'warning'|'info';title:string;detail:string;route:string;action:string}
export interface StudioSnapshot{revision:number;connection:'connected'|'reconnecting';readiness:{engine:boolean;phone:boolean;obs:boolean;virtualCamera:boolean};cameras:Camera[];audio:AudioSource[];previewId:string;programId:string;transition:'cut'|'dissolve';alerts:Alert[]}
export type StudioCommand=
 |{type:'camera.set';cameraId:string;setting:CameraSetting;value:string|number|boolean;expectedRevision:number}
 |{type:'audio.program';sourceId:string;enabled:boolean;expectedRevision:number}
 |{type:'audio.patch';sourceId:string;gain?:number;muted?:boolean;solo?:boolean;expectedRevision:number}
 |{type:'preview.set';cameraId:string;expectedRevision:number}
 |{type:'transition';kind:'cut'|'dissolve';expectedRevision:number}
 |{type:'simulate.failure';cameraId:string;expectedRevision:number};
export interface CommandResult{status:'confirmed'|'rejected'|'unsupported'|'conflict';revision:number;reason?:string}
export interface StudioAdapter{getSnapshot():StudioSnapshot;subscribe(listener:(snapshot:StudioSnapshot)=>void):()=>void;dispatch(command:StudioCommand):Promise<CommandResult>}
