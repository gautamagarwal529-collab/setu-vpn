mod directory;
mod order;
mod profile;

use base64::{engine::general_purpose::STANDARD, Engine};
use jni::objects::{JByteArray, JClass, JLongArray, JString};
use jni::sys::{jboolean, jbyteArray, jint, jintArray, jlong, jstring};
use jni::JNIEnv;
use serde_json::Value;
use sha2::{Digest, Sha256};

fn error(
    env: &mut JNIEnv<'_>,
    result: Result<*mut jni::sys::_jobject, String>,
) -> *mut jni::sys::_jobject {
    match result {
        Ok(value) => value,
        Err(message) => {
            let _ = env.throw_new("java/io/IOException", message);
            std::ptr::null_mut()
        }
    }
}

fn write_text(buffer: &mut Vec<u8>, text: &str) {
    buffer.extend_from_slice(&(text.len() as u32).to_le_bytes());
    buffer.extend_from_slice(text.as_bytes());
}

fn serialize(edition: directory::Edition) -> Vec<u8> {
    let mut buffer = Vec::with_capacity(24 + edition.relays.len() * 216);
    buffer.extend_from_slice(&edition.source_time.to_le_bytes());
    buffer.push(u8::from(edition.from_csv));
    write_text(&mut buffer, &edition.version);
    buffer.extend_from_slice(&(edition.relays.len() as u32).to_le_bytes());
    for relay in edition.relays {
        buffer.extend_from_slice(&relay.ip.octets());
        buffer.extend_from_slice(relay.country.as_bytes());
        write_text(&mut buffer, &relay.host);
        buffer.extend_from_slice(&relay.ping.to_le_bytes());
        buffer.extend_from_slice(&relay.port.to_le_bytes());
        buffer.extend_from_slice(&relay.speed.to_le_bytes());
        buffer.extend_from_slice(&relay.score.to_le_bytes());
        buffer.extend_from_slice(&relay.last_seen.to_le_bytes());
        buffer.extend_from_slice(&relay.checked.to_le_bytes());
        buffer.push(u8::from(relay.current));
        write_text(&mut buffer, &relay.tcp);
        write_text(&mut buffer, &relay.hash);
        write_text(&mut buffer, &relay.json_hash);
        buffer.extend_from_slice(&relay.json_length.to_le_bytes());
        write_text(&mut buffer, &relay.inline);
    }
    buffer
}

fn jni_error(error: jni::errors::Error) -> String {
    format!("Setu VPN core could not read Android data: {error}")
}

fn optional_string(env: &mut JNIEnv<'_>, value: &JString<'_>) -> Result<Option<String>, String> {
    if value.is_null() {
        return Ok(None);
    }
    let value = env.get_string(value).map_err(jni_error)?;
    Ok(Some(value.into()))
}

#[no_mangle]
pub extern "system" fn Java_com_paperline_vpn_RustCore_parseDirectory(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    data: JByteArray<'_>,
    commit: JString<'_>,
    now: jlong,
) -> jbyteArray {
    let result = (|| {
        let bytes = env.convert_byte_array(data).map_err(jni_error)?;
        let commit = optional_string(&mut env, &commit)?;
        let edition = directory::parse(&bytes, commit.as_deref(), now)?;
        env.byte_array_from_slice(&serialize(edition))
            .map(JByteArray::into_raw)
            .map_err(jni_error)
    })();
    error(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_paperline_vpn_RustCore_orderRelays(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input: JLongArray<'_>,
    directory: jboolean,
    rotate: jboolean,
    previous: jint,
    seed: jlong,
) -> jintArray {
    let result = (|| {
        let length = env.get_array_length(&input).map_err(jni_error)? as usize;
        if length % order::FIELDS != 0 {
            return Err("Invalid relay metadata".into());
        }
        let mut flat = vec![0; length];
        env.get_long_array_region(&input, 0, &mut flat)
            .map_err(jni_error)?;
        let (rows, _) = flat.as_chunks::<{ order::FIELDS }>();
        let ordered: Vec<i32> = order::order(
            rows,
            directory != 0,
            previous as u16,
            (rotate != 0).then_some(seed as u64),
        )
        .into_iter()
        .map(|index| index as i32)
        .collect();
        let array = env.new_int_array(ordered.len() as i32).map_err(jni_error)?;
        env.set_int_array_region(&array, 0, &ordered)
            .map_err(jni_error)?;
        Ok(array.into_raw())
    })();
    error(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_paperline_vpn_RustCore_cleanProfile(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    content: JString<'_>,
    host: JString<'_>,
    requires_login: jboolean,
) -> jstring {
    let result = (|| {
        let content: String = env.get_string(&content).map_err(jni_error)?.into();
        let host = optional_string(&mut env, &host)?;
        let clean = profile::clean(&content, host.as_deref(), requires_login != 0)?;
        env.new_string(clean)
            .map(JString::into_raw)
            .map_err(jni_error)
    })();
    error(&mut env, result)
}

fn unpack_profile(bytes: &[u8], hash: Option<&str>, host: &str) -> Result<String, String> {
    let Some(hash) = hash else {
        return clean_downloaded(&decode_base64(bytes)?, host);
    };
    let raw: Value = serde_json::from_slice(bytes).map_err(|_| "Unsupported relay profile")?;
    if raw["schema_version"].as_i64() != Some(1)
        || raw["kind"].as_str() != Some("vpngate-pool-config")
    {
        return Err("Unsupported relay profile".into());
    }
    let encoded = raw["openvpn_config_base64"]
        .as_str()
        .ok_or("Missing relay profile")?;
    let data = decode_base64(encoded.as_bytes())?;
    if raw["openvpn_config_bytes"].as_u64() != Some(data.len() as u64)
        || format!("{:x}", Sha256::digest(&data)) != hash
    {
        return Err("Relay profile changed".into());
    }
    clean_downloaded(&data, host)
}

fn decode_base64(encoded: &[u8]) -> Result<Vec<u8>, String> {
    // Android's decoder accepts ASCII line breaks and allows missing padding.
    let mut compact: Vec<_> = encoded
        .iter()
        .copied()
        .filter(|b| !b.is_ascii_whitespace())
        .collect();
    while compact.len() % 4 != 0 {
        compact.push(b'=');
    }
    STANDARD
        .decode(compact)
        .map_err(|_| "Invalid relay profile encoding".into())
}

fn clean_downloaded(bytes: &[u8], host: &str) -> Result<String, String> {
    if bytes.len() > profile::MAX_PROFILE {
        return Err("Profile is too large".into());
    }
    profile::clean(&String::from_utf8_lossy(bytes), Some(host), false)
}

#[no_mangle]
pub extern "system" fn Java_com_paperline_vpn_RustCore_verifyProfile(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    data: JByteArray<'_>,
    hash: JString<'_>,
    host: JString<'_>,
) -> jstring {
    let result = (|| {
        let bytes = env.convert_byte_array(data).map_err(jni_error)?;
        let hash = optional_string(&mut env, &hash)?;
        let host: String = env.get_string(&host).map_err(jni_error)?.into();
        let profile = unpack_profile(&bytes, hash.as_deref(), &host)?;
        env.new_string(profile)
            .map(JString::into_raw)
            .map_err(jni_error)
    })();
    error(&mut env, result)
}

#[no_mangle]
pub extern "system" fn Java_com_paperline_vpn_RustCore_sha256(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    data: JByteArray<'_>,
) -> jstring {
    let result = (|| {
        let bytes = env.convert_byte_array(data).map_err(jni_error)?;
        let text = format!("{:x}", Sha256::digest(bytes));
        env.new_string(text)
            .map(JString::into_raw)
            .map_err(jni_error)
    })();
    error(&mut env, result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn verifies_profile_hash_before_use() {
        let config = b"remote 8.8.8.8 443\n<ca>\ntest certificate\n</ca>\n";
        let hash = format!("{:x}", Sha256::digest(config));
        let data = serde_json::json!({
            "schema_version": 1,
            "kind": "vpngate-pool-config",
            "openvpn_config_base64": STANDARD.encode(config),
            "openvpn_config_bytes": config.len(),
        });
        let raw = data.to_string();
        assert!(unpack_profile(raw.as_bytes(), Some(&hash), "8.8.8.8").is_ok());
        assert!(unpack_profile(raw.as_bytes(), Some("bad hash"), "8.8.8.8").is_err());
        assert!(unpack_profile(raw.as_bytes(), Some(&hash), "1.1.1.1").is_err());
        assert_eq!(decode_base64(b"YQ\n").unwrap(), b"a");
    }
}
