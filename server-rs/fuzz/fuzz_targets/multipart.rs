//! The multipart parser and the boundary from a Content-Type: never a panic, ranges in bounds.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // The first line is a Content-Type, the rest the body.
    let (head, body) = match data.iter().position(|b| *b == b'\n') {
        Some(i) => (&data[..i], &data[i + 1..]),
        None => (&b""[..], data),
    };
    let boundary = std::str::from_utf8(head)
        .ok()
        .and_then(telltype_http::boundary)
        .unwrap_or_else(|| "b0undary".to_owned());
    if let Ok(parts) = telltype_http::parse_parts(body, &boundary) {
        for part in parts {
            assert!(part.data.start <= part.data.end && part.data.end <= body.len());
        }
    }
});
