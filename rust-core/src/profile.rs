use std::net::Ipv4Addr;

pub(crate) const MAX_PROFILE: usize = 96_000;
const INLINE: &[&str] = &["ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2"];

pub(crate) fn public_v4(address: &str) -> Option<Ipv4Addr> {
    let ip: Ipv4Addr = address.parse().ok()?;
    let [a, b, c, _] = ip.octets();
    match (a, b, c) {
        (0 | 10 | 127 | 224..=255, _, _)
        | (100, 64..=127, _)
        | (169, 254, _)
        | (172, 16..=31, _)
        | (192, 168, _)
        | (192, 0, 0 | 2)
        | (198, 18 | 19, _)
        | (198, 51, 100)
        | (203, 0, 113) => None,
        _ => Some(ip),
    }
}

pub(crate) fn clean(
    content: &str,
    expected_host: Option<&str>,
    requires_login: bool,
) -> Result<String, String> {
    if content.contains('\0') || content.encode_utf16().count() > MAX_PROFILE {
        return Err("Invalid OpenVPN file".into());
    }

    let mut output = String::with_capacity(content.len().saturating_add(64));
    let mut block = None;
    let mut has_ca = false;
    let mut pinned = false;
    let mut verifies_server = false;
    let mut has_remote = false;
    let mut has_login = false;

    for raw in content.lines() {
        let line = raw.trim_matches(|c: char| c <= ' ');
        let mut line = line
            .strip_prefix('\u{feff}')
            .map_or(line, |rest| rest.trim_matches(|c: char| c <= ' '));

        if let Some(expected) = block {
            if line.strip_prefix("</").and_then(|s| s.strip_suffix('>')) == Some(expected) {
                block = None;
            } else if line.contains(['<', '>']) {
                return Err("Invalid certificate block".into());
            }
        } else if line.is_empty() || line.starts_with(['#', ';']) {
            continue;
        } else if let Some(tag) = line.strip_prefix('<').and_then(|s| s.strip_suffix('>')) {
            if !INLINE.contains(&tag) {
                return Err("Unsupported inline OpenVPN data".into());
            }
            block = Some(tag);
            has_ca |= tag == "ca";
        } else {
            let mut fields = line
                .split(|c: char| c == ' ' || matches!(c, '\t'..='\r'))
                .filter(|field| !field.is_empty());
            let option = fields.next().expect("nonempty trimmed line");
            let name = option.to_ascii_lowercase();
            match name.as_str() {
                name if INLINE.contains(&name) => {
                    return Err("Certificates and keys must be embedded in the .ovpn file".into());
                }
                "dev" | "dev-type" => {
                    if fields.next() != Some("tun") || fields.next().is_some() {
                        return Err("This app supports TUN profiles".into());
                    }
                }
                "remote" => {
                    let host = fields.next().unwrap_or("");
                    if host.is_empty()
                        || !host
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b".:%_-".contains(&b))
                        || expected_host
                            .is_some_and(|expected| !expected.eq_ignore_ascii_case(host))
                    {
                        return Err("Unexpected VPN server address".into());
                    }
                    let port = fields.next();
                    let _protocol = fields.next();
                    if fields.next().is_some() {
                        return Err("Unexpected VPN server address".into());
                    }
                    if let Some(port) = port {
                        if !matches!(port.parse::<u16>(), Ok(1..=65535)) {
                            return Err("Invalid VPN port".into());
                        }
                    }
                    has_remote = true;
                }
                "setenv" => match fields.next() {
                    Some("opt") => continue,
                    Some("CLIENT_CERT") => {}
                    _ => return Err("Unsupported environment option".into()),
                },
                "pull-filter" if expected_host.is_some() => continue,
                "redirect-gateway" => continue,
                "auth-user-pass" => {
                    line = "auth-user-pass";
                    has_login = true;
                }
                "peer-fingerprint" => pinned = true,
                "remote-cert-tls" => {
                    if fields.next() != Some("server") || fields.next().is_some() {
                        return Err("Server certificate check required".into());
                    }
                    verifies_server = true;
                }
                "remote-cert-eku" | "verify-x509-name" | "ns-cert-type" => verifies_server = true,
                "auth"
                | "auth-nocache"
                | "auth-retry"
                | "block-outside-dns"
                | "cipher"
                | "client"
                | "comp-lzo"
                | "compress"
                | "connect-retry"
                | "connect-retry-max"
                | "connect-timeout"
                | "data-ciphers"
                | "data-ciphers-fallback"
                | "dhcp-option"
                | "explicit-exit-notify"
                | "float"
                | "fragment"
                | "inactive"
                | "keepalive"
                | "key-direction"
                | "mssfix"
                | "nobind"
                | "persist-key"
                | "persist-tun"
                | "ping"
                | "ping-exit"
                | "ping-restart"
                | "proto"
                | "pull"
                | "pull-filter"
                | "remote-cert-ku"
                | "remote-random"
                | "reneg-sec"
                | "resolv-retry"
                | "sndbuf"
                | "rcvbuf"
                | "tls-cipher"
                | "tls-client"
                | "tls-version-min"
                | "tls-version-max"
                | "tun-mtu"
                | "verb"
                | "mute"
                | "fast-io" => {}
                _ => return Err(format!("Unsupported OpenVPN option: {name}")),
            }
        }

        output.push_str(line);
        output.push('\n');
    }

    if block.is_some() || !has_remote || !(has_ca || pinned) {
        return Err("Profile needs a server and inline CA or fingerprint".into());
    }
    if requires_login && !has_login {
        return Err("Provider profile needs VPN account authentication".into());
    }
    if !verifies_server && !pinned {
        output.push_str("remote-cert-tls server\n");
    }
    output.push_str("redirect-gateway def1 ipv6\n");
    Ok(output)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preserves_certificate_checks_and_rejects_unsafe_profiles() {
        let input = "\u{feff}client\r\nremote Example.test 1194 udp\r\n<ca>\r\nCA\r\n</ca>\r\n";
        assert_eq!(
            clean(input, Some("example.TEST"), false).unwrap(),
            "client\nremote Example.test 1194 udp\n<ca>\nCA\n</ca>\nremote-cert-tls server\nredirect-gateway def1 ipv6\n"
        );
        assert!(clean(input, Some("other.test"), false).is_err());
        for option in [
            "up /tmp/script",
            "ca file",
            "dev tap",
            "<key>\n<ca>",
            "remote-cert-tls client",
        ] {
            assert!(
                clean(&format!("{input}{option}\n"), None, false).is_err(),
                "{option}"
            );
        }
    }

    #[test]
    fn normalizes_provider_auth_and_removes_route_overrides() {
        let input = "remote x.test +1194\npeer-fingerprint 00:11\nAUTH-USER-PASS local-file\nredirect-gateway\n";
        let wrapped = format!("{input}pull-filter ignore redirect-gateway\n");
        let result = clean(&wrapped, Some("x.test"), true).unwrap();
        assert_eq!(
            result,
            "remote x.test +1194\npeer-fingerprint 00:11\nauth-user-pass\nredirect-gateway def1 ipv6\n"
        );
        assert!(clean("remote x.test\npeer-fingerprint 40\n", Some("x.test"), true).is_err());
    }

    #[test]
    fn rejects_private_and_noncanonical_directory_ips() {
        for address in [
            "010.0.0.1",
            "127.0.0.1",
            "100.127.0.1",
            "192.0.2.1",
            "203.0.113.4",
            "8.8.8.8.",
            "224.1.2.3",
        ] {
            assert!(public_v4(address).is_none(), "{address}");
        }
        assert_eq!(public_v4("8.8.8.8"), Some(Ipv4Addr::new(8, 8, 8, 8)));
    }
}
