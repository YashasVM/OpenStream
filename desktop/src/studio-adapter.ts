import { useSyncExternalStore } from "react"

export type CommandState = "confirmed" | "pending" | "rejected" | "unsupported" | "conflict" | "reconnect-required"
export type Health = "ready" | "warning" | "offline"
export type CameraControl = "lens" | "zoom" | "iso" | "shutter" | "whiteBalance" | "focus" | "torch" | "stabilization"

export interface CameraState {
  id: string; name: string; model: string; health: Health; signal: number; latencyMs: number; battery: number; thermal: "nominal" | "warm" | "hot"
  tally: "program" | "preview" | "idle"; frame: string; heldFrame?: boolean
  controls: Record<CameraControl, { value: string | number | boolean; state: CommandState; detail?: string }>
}
export interface AudioSource { id: string; name: string; cameraId: string; routed: boolean; gain: number; muted: boolean; solo: boolean; level: number; syncMs: number; health: Health }
export interface StudioState {
  readiness: Record<"engine" | "phones" | "obs" | "virtualCamera", Health>
  cameras: CameraState[]; audio: AudioSource[]; previewId: string; programId: string
  transition: "cut" | "dissolve"; alerts: { id: string; severity: "warning" | "critical"; title: string; detail: string; destination: string }[]
  output: { recording: boolean; gapCount: number; virtualCamera: Health; obs: Health }
}

export interface StudioAdapter {
  subscribe(listener: () => void): () => void
  getSnapshot(): StudioState
  selectPreview(cameraId: string): void
  take(transition: "cut" | "dissolve"): void
  setCameraControl(cameraId: string, control: CameraControl, value: string | number | boolean): void
  setAudio(cameraId: string, patch: Partial<Pick<AudioSource, "routed" | "gain" | "muted" | "solo">>): void
  simulateProgramFailure(): void
  reset(): void
}

const control = (value: string | number | boolean, state: CommandState = "confirmed", detail?: string) => ({ value, state, detail })
const initialState = (): StudioState => ({
  readiness: { engine: "ready", phones: "ready", obs: "warning", virtualCamera: "ready" },
  previewId: "cam-2", programId: "cam-1", transition: "cut",
  output: { recording: true, gapCount: 1, virtualCamera: "ready", obs: "warning" },
  alerts: [{ id: "gap-1", severity: "warning", title: "Recording gap declared", detail: "Camera 3 · 00:42.114–00:42.614 · recovery pending", destination: "/cameras" }],
  cameras: [
    ["cam-1", "Camera 1", "Pixel 9 Pro", "ready", 94, 82, 78, "nominal", "program", "linear-gradient(135deg,#17233a,#304b66 52%,#b06f46)"],
    ["cam-2", "Camera 2", "Galaxy S25", "ready", 88, 96, 64, "warm", "preview", "linear-gradient(135deg,#132c2c,#326a63 55%,#d39b68)"],
    ["cam-3", "Camera 3", "Pixel 8", "warning", 62, 148, 42, "hot", "idle", "linear-gradient(135deg,#321e2b,#75404f 58%,#d78771)"],
    ["cam-4", "Camera 4", "Xperia 1 VI", "ready", 91, 74, 86, "nominal", "idle", "linear-gradient(135deg,#242238,#514777 55%,#c29373)"],
  ].map(([id,name,model,health,signal,latencyMs,battery,thermal,tally,frame]) => ({ id,name,model,health,signal,latencyMs,battery,thermal,tally,frame, controls: {
    lens: control(id === "cam-4" ? "24 mm" : "Main"), zoom: control(1), iso: control(200), shutter: control("1/60"), whiteBalance: control("4300 K"), focus: control("Auto"),
    torch: id === "cam-4" ? control(false,"unsupported","No torch on active lens") : control(false), stabilization: id === "cam-3" ? control("Standard","reconnect-required","Session rebuild required") : control("Standard")
  }})) as CameraState[],
  audio: [1,2,3,4].map((n) => ({ id:`mic-${n}`, name:`Camera ${n} mic`, cameraId:`cam-${n}`, routed:n===1, gain:0, muted:false, solo:false, level:-16-n*3, syncMs:n*4-7, health:n===3?"warning":"ready" })) as AudioSource[],
})

export class DeterministicStudioAdapter implements StudioAdapter {
  private state = initialState(); private listeners = new Set<() => void>(); private revision = 0
  subscribe = (listener: () => void) => { this.listeners.add(listener); return () => this.listeners.delete(listener) }
  getSnapshot = () => this.state
  private emit(state: StudioState) { this.state = state; this.listeners.forEach((listener) => listener()) }
  selectPreview(cameraId: string) { this.emit({ ...this.state, previewId: cameraId, cameras: this.state.cameras.map(c => ({...c,tally:c.id===this.state.programId?"program":c.id===cameraId?"preview":"idle"})) }) }
  take(transition: "cut" | "dissolve") { const oldProgram=this.state.programId; const next=this.state.previewId; this.emit({...this.state,transition,programId:next,previewId:oldProgram,cameras:this.state.cameras.map(c=>({...c,heldFrame:false,tally:c.id===next?"program":c.id===oldProgram?"preview":"idle"}))}) }
  setCameraControl(cameraId: string, key: CameraControl, value: string | number | boolean) {
    const token=++this.revision; const update=(state:CommandState,detail?:string)=>this.emit({...this.state,cameras:this.state.cameras.map(c=>c.id===cameraId?{...c,controls:{...c.controls,[key]:control(value,state,detail)}}:c)})
    const current=this.state.cameras.find(c=>c.id===cameraId)?.controls[key]; if(current?.state==="unsupported"){ update("unsupported",current.detail); return }
    update("pending","Awaiting authoritative phone state")
    window.setTimeout(()=>{ if(token!==this.revision)return; update(key==="stabilization"&&cameraId==="cam-3"?"reconnect-required":"confirmed",key==="stabilization"&&cameraId==="cam-3"?"Apply & reconnect required":undefined)},350)
  }
  setAudio(cameraId: string, patch: Partial<Pick<AudioSource,"routed"|"gain"|"muted"|"solo">>) { this.emit({...this.state,audio:this.state.audio.map(a=>a.cameraId===cameraId?{...a,...patch}:a)}) }
  simulateProgramFailure() { const id=this.state.programId; this.emit({...this.state,cameras:this.state.cameras.map(c=>c.id===id?{...c,health:"offline",heldFrame:true}:c),alerts:[...this.state.alerts,{id:"program-failure",severity:"critical",title:"PROGRAM CAMERA OFFLINE — LAST FRAME HELD",detail:`${this.state.cameras.find(c=>c.id===id)?.name} disconnected. Other cameras and recording continue.`,destination:"/studio"}]}) }
  reset(){this.emit(initialState())}
}

export function useStudio(adapter: StudioAdapter) { return useSyncExternalStore(adapter.subscribe,adapter.getSnapshot,adapter.getSnapshot) }
