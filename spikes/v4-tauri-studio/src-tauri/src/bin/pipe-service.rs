#[path = "../protocol.rs"]
mod protocol;

use protocol::{
    HelloRequest, HelloResponse, PreviewDescriptor, StudioSnapshot, MAX_FRAME_BYTES,
    PROTOCOL_VERSION,
};
use std::{env, io, ptr};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::windows::named_pipe::ServerOptions,
};
use windows_sys::Win32::{
    Foundation::{CloseHandle, LocalFree},
    Security::{
        Authorization::{
            ConvertSidToStringSidW, ConvertStringSecurityDescriptorToSecurityDescriptorW,
            SDDL_REVISION_1,
        },
        GetTokenInformation, TokenUser, PSECURITY_DESCRIPTOR, SECURITY_ATTRIBUTES, TOKEN_QUERY,
        TOKEN_USER,
    },
    System::Threading::{GetCurrentProcess, OpenProcessToken},
};

fn current_user_sid() -> io::Result<String> {
    unsafe {
        let mut token = ptr::null_mut();
        if OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &mut token) == 0 {
            return Err(io::Error::last_os_error());
        }
        let mut bytes = 0;
        GetTokenInformation(token, TokenUser, ptr::null_mut(), 0, &mut bytes);
        let mut buffer = vec![0u8; bytes as usize];
        let ok = GetTokenInformation(
            token,
            TokenUser,
            buffer.as_mut_ptr().cast(),
            bytes,
            &mut bytes,
        );
        CloseHandle(token);
        if ok == 0 {
            return Err(io::Error::last_os_error());
        }
        let user = &*(buffer.as_ptr().cast::<TOKEN_USER>());
        let mut sid_wide: *mut u16 = ptr::null_mut();
        if ConvertSidToStringSidW(user.User.Sid, &mut sid_wide) == 0 {
            return Err(io::Error::last_os_error());
        }
        let len = (0..).take_while(|&i| *sid_wide.add(i) != 0).count();
        let sid = String::from_utf16_lossy(std::slice::from_raw_parts(sid_wide, len));
        LocalFree(sid_wide.cast());
        Ok(sid)
    }
}

struct SecurityDescriptor(PSECURITY_DESCRIPTOR);
impl Drop for SecurityDescriptor {
    fn drop(&mut self) {
        unsafe {
            LocalFree(self.0);
        }
    }
}
fn user_only_security(sid: &str) -> io::Result<(SecurityDescriptor, SECURITY_ATTRIBUTES)> {
    let sddl: Vec<u16> = format!("D:P(A;;GA;;;{sid})\0").encode_utf16().collect();
    let mut descriptor = ptr::null_mut();
    unsafe {
        if ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl.as_ptr(),
            SDDL_REVISION_1,
            &mut descriptor,
            ptr::null_mut(),
        ) == 0
        {
            return Err(io::Error::last_os_error());
        }
    }
    let attrs = SECURITY_ATTRIBUTES {
        nLength: std::mem::size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: descriptor,
        bInheritHandle: 0,
    };
    Ok((SecurityDescriptor(descriptor), attrs))
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
async fn write_frame(stream: &mut (impl AsyncWriteExt + Unpin), payload: &[u8]) -> io::Result<()> {
    stream.write_u32_le(payload.len() as u32).await?;
    stream.write_all(payload).await
}

#[tokio::main]
async fn main() -> io::Result<()> {
    let sid = current_user_sid()?;
    let expected_pipe = format!(r"\\.\pipe\openstream-v4-studio-spike-{sid}");
    let pipe_name =
        env::var("OPENSTREAM_TAURI_TEST_PIPE").unwrap_or_else(|_| expected_pipe.clone());
    if pipe_name != expected_pipe {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "pipe name must contain the current user SID",
        ));
    }
    let nonce = env::var("OPENSTREAM_TAURI_TEST_NONCE").map_err(|_| {
        io::Error::new(
            io::ErrorKind::InvalidInput,
            "OPENSTREAM_TAURI_TEST_NONCE missing",
        )
    })?;
    if nonce.len() < 32 || nonce.len() > 128 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "nonce length"));
    }
    let (_descriptor, mut attrs) = user_only_security(&sid)?;
    let server = unsafe {
        ServerOptions::new()
            .first_pipe_instance(true)
            .create_with_security_attributes_raw(
                &pipe_name,
                (&mut attrs as *mut SECURITY_ATTRIBUTES).cast(),
            )?
    };
    println!("READY {pipe_name}");
    server.connect().await?;
    let mut server = server;
    let request: HelloRequest =
        serde_json::from_slice(&read_frame(&mut server).await?).map_err(io::Error::other)?;
    if request.protocol_version != PROTOCOL_VERSION || request.nonce != nonce {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "hello rejected",
        ));
    }
    let previews = (1..=4)
        .map(|index| PreviewDescriptor {
            id: format!("fake-shared-texture-{index}"),
            label: format!("Camera {index}"),
            width: 1920,
            height: 1080,
            adapter_luid: "FAKE:0000ABCD".into(),
            generation: 7,
            health: if index == 4 { "warning" } else { "ready" }.into(),
        })
        .collect();
    let response = HelloResponse {
        protocol_version: PROTOCOL_VERSION,
        request_id: request.request_id,
        snapshot: StudioSnapshot {
            protocol_version: PROTOCOL_VERSION,
            service_instance: uuid::Uuid::new_v4().to_string(),
            previews,
        },
    };
    write_frame(
        &mut server,
        &serde_json::to_vec(&response).map_err(io::Error::other)?,
    )
    .await?;
    server.flush().await
}
