mod protocol;

use protocol::{BridgeStatus, HelloRequest, HelloResponse, MAX_FRAME_BYTES, PROTOCOL_VERSION};
#[cfg(test)]
use protocol::{PreviewDescriptor, StudioSnapshot};
use std::{env, io};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::windows::named_pipe::ClientOptions,
    time::{timeout, Duration},
};

fn validate_bridge_config(pipe: String, nonce: String) -> Result<(String, String), String> {
    if !pipe.starts_with(r"\\.\pipe\openstream-v4-studio-spike-S-1-") {
        return Err("pipe name is not user-SID scoped".to_owned());
    }
    if nonce.len() < 32 || nonce.len() > 128 {
        return Err("test nonce must be 32..128 characters".to_owned());
    }
    Ok((pipe, nonce))
}

fn bridge_config() -> Result<(String, String), String> {
    let pipe = env::var("OPENSTREAM_TAURI_TEST_PIPE")
        .map_err(|_| "OPENSTREAM_TAURI_TEST_PIPE is not set".to_owned())?;
    let nonce = env::var("OPENSTREAM_TAURI_TEST_NONCE")
        .map_err(|_| "OPENSTREAM_TAURI_TEST_NONCE is not set".to_owned())?;
    validate_bridge_config(pipe, nonce)
}

async fn write_frame(stream: &mut (impl AsyncWriteExt + Unpin), payload: &[u8]) -> io::Result<()> {
    if payload.len() > MAX_FRAME_BYTES {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "oversize frame"));
    }
    stream.write_u32_le(payload.len() as u32).await?;
    stream.write_all(payload).await
}

async fn read_frame(stream: &mut (impl AsyncReadExt + Unpin)) -> io::Result<Vec<u8>> {
    let size = stream.read_u32_le().await? as usize;
    if size == 0 || size > MAX_FRAME_BYTES {
        return Err(io::Error::new(
            io::ErrorKind::InvalidData,
            "invalid frame size",
        ));
    }
    let mut payload = vec![0; size];
    stream.read_exact(&mut payload).await?;
    Ok(payload)
}

async fn connect_pipe_service(pipe_name: String, nonce: String) -> BridgeStatus {
    let result = timeout(Duration::from_secs(2), async {
        let mut pipe = ClientOptions::new().open(&pipe_name)?;
        let request = HelloRequest {
            protocol_version: PROTOCOL_VERSION,
            request_id: uuid::Uuid::new_v4().to_string(),
            nonce,
        };
        let encoded = serde_json::to_vec(&request).map_err(io::Error::other)?;
        write_frame(&mut pipe, &encoded).await?;
        let response: HelloResponse =
            serde_json::from_slice(&read_frame(&mut pipe).await?).map_err(io::Error::other)?;
        if response.protocol_version != PROTOCOL_VERSION
            || response.request_id != request.request_id
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "protocol/request mismatch",
            ));
        }
        Ok::<_, io::Error>(response.snapshot)
    })
    .await;
    match result {
        Ok(Ok(snapshot)) => BridgeStatus {
            connected: true,
            pipe_name,
            detail: "Versioned named-pipe snapshot received off the UI thread".into(),
            snapshot: Some(snapshot),
        },
        Ok(Err(error)) => BridgeStatus {
            connected: false,
            pipe_name,
            detail: format!("pipe error: {error}"),
            snapshot: None,
        },
        Err(_) => BridgeStatus {
            connected: false,
            pipe_name,
            detail: "pipe operation timed out after 2 seconds".into(),
            snapshot: None,
        },
    }
}

#[tauri::command]
async fn connect_test_service() -> BridgeStatus {
    match bridge_config() {
        Ok((pipe_name, nonce)) => connect_pipe_service(pipe_name, nonce).await,
        Err(detail) => BridgeStatus {
            connected: false,
            pipe_name: "not-configured".into(),
            detail,
            snapshot: None,
        },
    }
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![connect_test_service])
        .run(tauri::generate_context!())
        .expect("Tauri runtime failed");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_unscoped_pipe() {
        assert!(validate_bridge_config(
            r"\\.\pipe\openstream".into(),
            "01234567890123456789012345678901".into(),
        )
        .is_err());
    }

    #[tokio::test]
    async fn typed_bridge_receives_a_versioned_snapshot() {
        use tokio::net::windows::named_pipe::ServerOptions;

        let pipe_name = format!(
            r"\\.\pipe\openstream-v4-studio-spike-S-1-5-21-test-{}",
            uuid::Uuid::new_v4()
        );
        let nonce = "01234567890123456789012345678901".to_owned();
        let mut server = ServerOptions::new()
            .first_pipe_instance(true)
            .create(&pipe_name)
            .unwrap();
        let expected_nonce = nonce.clone();
        let service = tokio::spawn(async move {
            server.connect().await.unwrap();
            let request: HelloRequest =
                serde_json::from_slice(&read_frame(&mut server).await.unwrap()).unwrap();
            assert_eq!(request.protocol_version, PROTOCOL_VERSION);
            assert_eq!(request.nonce, expected_nonce);
            let response = HelloResponse {
                protocol_version: PROTOCOL_VERSION,
                request_id: request.request_id,
                snapshot: StudioSnapshot {
                    protocol_version: PROTOCOL_VERSION,
                    service_instance: "test-service".into(),
                    previews: vec![PreviewDescriptor {
                        id: "fake-shared-texture-1".into(),
                        label: "Camera 1".into(),
                        width: 1920,
                        height: 1080,
                        adapter_luid: "FAKE:0000ABCD".into(),
                        generation: 7,
                        health: "ready".into(),
                    }],
                },
            };
            write_frame(&mut server, &serde_json::to_vec(&response).unwrap())
                .await
                .unwrap();
        });

        let status = connect_pipe_service(pipe_name, nonce).await;
        service.await.unwrap();
        assert!(status.connected, "{}", status.detail);
        assert_eq!(
            status.snapshot.unwrap().previews[0].id,
            "fake-shared-texture-1"
        );
    }
}
