import { invoke } from "@tauri-apps/api/core";

export type PreviewHealth = "ready" | "warning" | "offline";
export interface PreviewDescriptor {
  id: string;
  label: string;
  width: number;
  height: number;
  adapterLuid: string;
  generation: number;
  health: PreviewHealth;
}
export interface StudioSnapshot {
  protocolVersion: 1;
  serviceInstance: string;
  previews: PreviewDescriptor[];
}
export interface BridgeStatus { connected: boolean; pipeName: string; detail: string; snapshot?: StudioSnapshot }

const simulated: StudioSnapshot = {
  protocolVersion: 1,
  serviceInstance: "simulated-no-service",
  previews: Array.from({ length: 4 }, (_, index) => ({
    id: `camera-${index + 1}`, label: `Camera ${index + 1}`, width: 1920, height: 1080,
    adapterLuid: "SIMULATED:0000", generation: 1, health: index === 3 ? "warning" : "ready",
  })),
};

export async function connectBridge(): Promise<BridgeStatus> {
  if (!("__TAURI_INTERNALS__" in window)) {
    return { connected: false, pipeName: "browser-preview", detail: "Browser-only simulated bridge", snapshot: simulated };
  }
  return invoke<BridgeStatus>("connect_test_service");
}
