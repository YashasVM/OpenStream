use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u8 = 1;
pub const MAX_FRAME_BYTES: usize = 64 * 1024;

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloRequest {
    pub protocol_version: u8,
    pub request_id: String,
    pub nonce: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PreviewDescriptor {
    pub id: String,
    pub label: String,
    pub width: u32,
    pub height: u32,
    pub adapter_luid: String,
    pub generation: u64,
    pub health: String,
}

#[derive(Clone, Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StudioSnapshot {
    pub protocol_version: u8,
    pub service_instance: String,
    pub previews: Vec<PreviewDescriptor>,
}

#[derive(Debug, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct HelloResponse {
    pub protocol_version: u8,
    pub request_id: String,
    pub snapshot: StudioSnapshot,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BridgeStatus {
    pub connected: bool,
    pub pipe_name: String,
    pub detail: String,
    pub snapshot: Option<StudioSnapshot>,
}
