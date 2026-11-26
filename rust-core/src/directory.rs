use std::collections::HashSet;
use std::net::Ipv4Addr;

use chrono::DateTime;
use serde_json::Value;
use sha2::{Digest, Sha256};

use crate::profile::public_v4;

pub(crate) const HOUR: i64 = 3_600_000;
const FRESH: i64 = 18 * HOUR;
const RETENTION: i64 = 7 * 24 * HOUR;

pub(crate) struct Edition {
    pub(crate) source_time: i64,
    pub(crate) version: String,
    pub(crate) from_csv: bool,
    pub(crate) relays: Vec<Relay>,
}

pub(crate) struct Relay {
    pub(crate) country: String,
    pub(crate) ip: Ipv4Addr,
    pub(crate) host: String,
    pub(crate) ping: i32,
    pub(crate) port: i32,
    pub(crate) speed: i64,
    pub(crate) score: i64,
    pub(crate) last_seen: i64,
    pub(crate) checked: i64,
    pub(crate) current: bool,
    pub(crate) tcp: String,
    pub(crate) hash: String,
    pub(crate) json_hash: String,
    pub(crate) json_length: i32,
    pub(crate) inline: String,
}

fn timestamp(val: &Value) -> i64 {
    val.as_str()
        .and_then(|s| DateTime::parse_from_rfc3339(s).ok())
        .map_or(0, |time| time.timestamp_millis())
}

fn country_code(s: &str) -> Option<&str> {
    (s.len() == 2 && s.bytes().all(|b| b.is_ascii_uppercase())).then_some(s)
}

fn text(val: &Value) -> &str {
    val.as_str().unwrap_or("")
}

fn int(val: &Value) -> i64 {
    val.as_i64()
        .or_else(|| val.as_str().and_then(|s| s.parse().ok()))
        .unwrap_or(0)
}

fn truth(val: &Value) -> bool {
    val.as_bool()
        .or_else(|| val.as_str().map(|s| s.eq_ignore_ascii_case("true")))
        .unwrap_or(false)
}

fn hex(s: &str, length: usize) -> bool {
    s.len() == length
        && s.bytes()
            .all(|b| b.is_ascii_digit() || (b'a'..=b'f').contains(&b))
}

pub(crate) fn reachable(status: &str, checked: i64, now: i64) -> bool {
    status == "reachable" && checked > now.saturating_sub(FRESH)
}

fn parse_row(row: &Value, source_time: i64, now: i64) -> Option<Relay> {
    let ip_text = text(&row["ip"]);
    let ip = public_v4(ip_text)?;
    let country = if row["country_code"].is_null() {
        "XX"
    } else {
        country_code(text(&row["country_code"]))?
    };
    let last_seen = timestamp(&row["last_seen_at"]);
    let current =
        truth(&row["present_in_latest_source"]) && source_time > now.saturating_sub(FRESH);
    let tcp = text(&row["tcp_probe"]["status"]);
    let checked = timestamp(&row["tcp_probe"]["checked_at"]);
    if last_seen < now.saturating_sub(RETENTION)
        || (!current && tcp == "unreachable" && checked > now.saturating_sub(16 * HOUR))
    {
        return None;
    }
    let config = &row["config"];
    let hash = text(&row["openvpn_config_sha256"]);
    if !hex(hash, 64)
        || text(&config["path"])
            .strip_prefix("pool/configs/")?
            .strip_suffix(".json")?
            != hash
    {
        return None;
    }
    let port = if text(&row["probe_reason"]) == "tcp" {
        row["probe_targets"]
            .as_array()
            .into_iter()
            .flatten()
            .find_map(|target| {
                let port = int(&target["port"]) as i32;
                (text(&target["ip"]) == ip_text && (1..=65535).contains(&port)).then_some(port)
            })
            .unwrap_or_default()
    } else {
        0
    };
    Some(Relay {
        country: country.to_owned(),
        ip,
        host: text(&row["hostname"]).to_owned(),
        ping: int(&row["ping_ms"]) as i32,
        port,
        speed: int(&row["speed_bps"]),
        score: int(&row["score"]),
        last_seen,
        checked,
        current,
        tcp: tcp.to_owned(),
        hash: hash.to_owned(),
        json_hash: text(&config["sha256"]).to_owned(),
        json_length: int(&config["bytes"]) as i32,
        inline: String::new(),
    })
}

fn csv_fields(line: &str) -> Vec<String> {
    let mut columns = Vec::with_capacity(15);
    let mut field = String::new();
    let mut quoted = false;
    let mut chars = line.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '"' if quoted && chars.peek() == Some(&'"') => {
                chars.next();
                field.push('"');
            }
            '"' => quoted = !quoted,
            ',' if !quoted => columns.push(std::mem::take(&mut field)),
            _ => field.push(c),
        }
    }
    columns.push(field);
    columns
}

fn parse_csv_row(line: &str, source_time: i64, now: i64) -> Option<Relay> {
    if line.starts_with(['#', '*']) {
        return None;
    }
    let mut fields = csv_fields(line);
    if fields.len() != 15 || fields[14].is_empty() {
        return None;
    }
    let ip = public_v4(&fields[1])?;
    country_code(&fields[6])?;
    let number = |index: usize| fields[index].parse::<i64>().unwrap_or(0).max(0);
    let (score, speed, ping) = (number(2), number(4), number(3) as i32);
    Some(Relay {
        country: std::mem::take(&mut fields[6]),
        ip,
        host: std::mem::take(&mut fields[0]),
        ping,
        port: 0,
        speed,
        score,
        last_seen: source_time,
        checked: 0,
        current: source_time > now.saturating_sub(FRESH),
        tcp: String::new(),
        hash: String::new(),
        json_hash: String::new(),
        json_length: 0,
        inline: std::mem::take(&mut fields[14]),
    })
}

pub(crate) fn parse(bytes: &[u8], known_commit: Option<&str>, now: i64) -> Result<Edition, String> {
    let document: Value = serde_json::from_slice(bytes).map_err(|_| "Invalid relay catalog")?;
    if known_commit.is_some() && document["schema_version"].as_i64() != Some(1) {
        return Err("Unknown server format".into());
    }
    let source_time = timestamp(&document["source_fetched_at"]);
    let mut edition = Edition {
        source_time,
        version: String::new(),
        from_csv: false,
        relays: Vec::new(),
    };
    if source_time <= now.saturating_sub(RETENTION) {
        return Ok(edition);
    }

    if let Some(feed) = document["paperline_csv"].as_str() {
        edition.from_csv = true;
        edition.version = format!("{:x}", Sha256::digest(feed.as_bytes()))[..8].to_owned();
        edition.relays = feed
            .lines()
            .filter_map(|line| parse_csv_row(line, source_time, now))
            .collect();
    } else {
        let commit = known_commit.unwrap_or_else(|| text(&document["paperline_commit"]));
        if !hex(commit, 40) {
            return Err("Invalid catalog version".into());
        }
        let rows = document["servers"]
            .as_array()
            .ok_or("Missing server catalog")?;
        edition.version = commit.to_owned();
        edition.relays = rows
            .iter()
            .filter_map(|row| parse_row(row, source_time, now))
            .collect();
    }

    // Stable sorting preserves the publisher's order when its metrics tie.
    edition.relays.sort_by_key(|row| {
        (
            crate::order::availability(reachable(&row.tcp, row.checked, now), row.current),
            std::cmp::Reverse(row.last_seen / (3 * HOUR)),
            std::cmp::Reverse(row.score),
        )
    });
    let mut seen = HashSet::with_capacity(edition.relays.len());
    edition.relays.retain(|row| {
        let country = [row.country.as_bytes()[0], row.country.as_bytes()[1]];
        seen.insert((country, row.ip))
    });
    Ok(edition)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn bundled_catalogs_keep_valid_and_unique_relays() {
        for (bytes, expected) in [
            (
                include_bytes!("../../main/src/main/assets/paperline-starter.json").as_slice(),
                243,
            ),
            (
                include_bytes!("../../main/src/main/assets/paperline-directory.json").as_slice(),
                4948,
            ),
        ] {
            let document: Value = serde_json::from_slice(bytes).unwrap();
            let now = timestamp(&document["source_fetched_at"]);
            let edition = parse(bytes, None, now).unwrap();
            assert!(!edition.from_csv);
            assert_eq!(edition.version.len(), 40);
            assert!(!edition.relays.is_empty() && edition.relays.len() <= expected);
            assert!(edition.relays.iter().any(|row| row.country == "JP"));
            assert!(edition.relays.iter().any(|row| row.country == "US"));
            assert!(parse(bytes, None, now + RETENTION)
                .unwrap()
                .relays
                .is_empty());
            let unique: HashSet<_> = edition
                .relays
                .iter()
                .map(|r| (r.country.as_str(), r.ip))
                .collect();
            assert_eq!(edition.relays.len(), unique.len());
        }
    }

    #[test]
    fn official_feed_parses_quoted_fields_and_skips_local_addresses() {
        let data = json!({
            "source_fetched_at": "2026-11-26T02:30:21.595Z",
            "paperline_csv": "*vpn_servers\n#header\n\"relay, nice\",8.8.8.8,42,15,999,Japan,JP,0,0,0,0,0,0,0,YQ==\nlocal,127.0.0.1,40,15,999,Japan,JP,0,0,0,0,0,0,0,YQ==\n*\n"
        });
        let edition = parse(
            data.to_string().as_bytes(),
            None,
            timestamp(&data["source_fetched_at"]),
        )
        .unwrap();
        assert!(edition.from_csv);
        assert_eq!(edition.version.len(), 8);
        assert_eq!(edition.relays.len(), 1);
        assert_eq!(edition.relays[0].host, "relay, nice");
        assert_eq!(edition.relays[0].inline, "YQ==");
    }
}
