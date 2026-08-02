import { describe, expect, it, vi } from "vitest"
import { DeterministicStudioAdapter } from "./studio-adapter"

describe("DeterministicStudioAdapter",()=>{
  it("starts with exactly one explicitly routed microphone",()=>{const adapter=new DeterministicStudioAdapter();expect(adapter.getSnapshot().audio.filter(a=>a.routed).map(a=>a.cameraId)).toEqual(["cam-1"])})
  it("keeps failures isolated and holds the last Program frame",()=>{const adapter=new DeterministicStudioAdapter();adapter.simulateProgramFailure();const state=adapter.getSnapshot();expect(state.cameras.find(c=>c.id===state.programId)).toMatchObject({health:"offline",heldFrame:true});expect(state.cameras.filter(c=>c.id!==state.programId).every(c=>c.health!=="offline")).toBe(true);expect(state.alerts.at(-1)?.title).toContain("LAST FRAME HELD")})
  it("does not fake camera command confirmation",()=>{vi.useFakeTimers();const adapter=new DeterministicStudioAdapter();adapter.setCameraControl("cam-1","iso",400);expect(adapter.getSnapshot().cameras[0].controls.iso.state).toBe("pending");vi.advanceTimersByTime(350);expect(adapter.getSnapshot().cameras[0].controls.iso).toMatchObject({state:"confirmed",value:400});vi.useRealTimers()})
  it("preserves unsupported capability state",()=>{const adapter=new DeterministicStudioAdapter();adapter.setCameraControl("cam-4","torch",true);expect(adapter.getSnapshot().cameras[3].controls.torch.state).toBe("unsupported")})
})
