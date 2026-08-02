import { fireEvent, render, screen } from "@testing-library/react"
import { beforeEach, describe, expect, it } from "vitest"
import { App } from "./App"
import { DeterministicStudioAdapter } from "./studio-adapter"

describe("operator routes",()=>{
  beforeEach(()=>{location.hash=""})
  it("navigates to each of the four destinations",()=>{render(<App/>);for(const name of ["Cameras","Audio","Studio","Dashboard"]){fireEvent.click(screen.getByRole("button",{name:new RegExp(name)}));expect(screen.getByRole("heading",{name,level:1})).toBeInTheDocument()}})
  it("renders capability and command authority states",()=>{location.hash="#/cameras";render(<App/>);fireEvent.click(screen.getByRole("button",{name:/Camera 4, idle/}));expect(screen.getByText("Unsupported")).toBeInTheDocument();expect(screen.getByText("No torch on active lens")).toBeInTheDocument()})
  it("requires explicit audio routing",()=>{const adapter=new DeterministicStudioAdapter();location.hash="#/audio";render(<App adapter={adapter}/>);const routes=screen.getAllByRole("switch",{name:/Route to Program/});expect(routes.filter(r=>r.getAttribute("aria-checked")==="true")).toHaveLength(1);fireEvent.click(routes[1]);expect(adapter.getSnapshot().audio[1].routed).toBe(true)})
  it("exposes non-colour-only failure feedback and keyboard cut",()=>{const adapter=new DeterministicStudioAdapter();location.hash="#/studio";render(<App adapter={adapter}/>);fireEvent.click(screen.getByRole("button",{name:/Simulate live failure/}));expect(screen.getAllByText(/LAST FRAME HELD/).length).toBeGreaterThan(0);const before=adapter.getSnapshot().programId;fireEvent.keyDown(window,{key:" "});expect(adapter.getSnapshot().programId).not.toBe(before)})
})
