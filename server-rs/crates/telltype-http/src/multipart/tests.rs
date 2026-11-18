use proptest::prelude::*;

use super::{MultipartError, boundary, parse_parts};

fn encode(boundary: &str, parts: &[(String, Option<String>, Vec<u8>)]) -> Vec<u8> {
    let mut out = Vec::new();
    for (name, filename, data) in parts {
        out.extend_from_slice(format!("--{boundary}\r\n").as_bytes());
        let file = filename
            .as_ref()
            .map(|f| format!("; filename=\"{f}\""))
            .unwrap_or_default();
        out.extend_from_slice(
            format!("Content-Disposition: form-data; name=\"{name}\"{file}\r\n").as_bytes(),
        );
        if filename.is_some() {
            out.extend_from_slice(b"Content-Type: audio/wav\r\n");
        }
        out.extend_from_slice(b"\r\n");
        out.extend_from_slice(data);
        out.extend_from_slice(b"\r\n");
    }
    out.extend_from_slice(format!("--{boundary}--\r\n").as_bytes());
    out
}

#[test]
fn boundary_from_content_type() {
    assert_eq!(
        boundary("multipart/form-data; boundary=abc").as_deref(),
        Some("abc")
    );
    assert_eq!(
        boundary("Multipart/Form-Data; charset=utf-8; BOUNDARY=\"a b;c\"").as_deref(),
        Some("a b;c")
    );
    assert_eq!(boundary("multipart/form-data"), None);
    assert_eq!(boundary("multipart/form-data; boundary="), None);
    assert_eq!(boundary("application/json; boundary=abc"), None);
    assert_eq!(
        boundary(&format!("multipart/form-data; boundary={}", "x".repeat(71))),
        None
    );
}

#[test]
fn parses_fields_files_preamble_and_padding() {
    let mut body = b"preamble\r\n".to_vec();
    body.extend_from_slice(&encode(
        "XyZ",
        &[
            (
                "file".into(),
                Some("clip.wav".into()),
                b"RIFF\r\n--XyA-not-a-boundary".to_vec(),
            ),
            ("language".into(), None, b"en".to_vec()),
        ],
    ));
    let parts = parse_parts(&body, "XyZ").unwrap();
    assert_eq!(parts.len(), 2);
    assert_eq!(parts[0].name.as_deref(), Some("file"));
    assert_eq!(parts[0].filename.as_deref(), Some("clip.wav"));
    assert_eq!(parts[0].content_type.as_deref(), Some("audio/wav"));
    assert_eq!(&body[parts[0].data.clone()], b"RIFF\r\n--XyA-not-a-boundary");
    assert_eq!(&body[parts[1].data.clone()], b"en");
    let padded = b"--b \t\r\nContent-Disposition: form-data; name=x\r\n\r\n1\r\n--b--";
    assert_eq!(parse_parts(padded, "b").unwrap()[0].name.as_deref(), Some("x"));
    let no_headers = b"--b\r\n\r\nraw\r\n--b--";
    let parts = parse_parts(no_headers, "b").unwrap();
    assert_eq!(
        (parts[0].name.clone(), &no_headers[parts[0].data.clone()]),
        (None, &b"raw"[..])
    );
    assert_eq!(parse_parts(b"--b--", "b").unwrap(), []);
}

#[test]
fn malformed_bodies_are_typed_errors() {
    assert_eq!(
        parse_parts(b"nothing here", "b"),
        Err(MultipartError::NoFirstBoundary)
    );
    assert_eq!(
        parse_parts(
            b"--b\r\nContent-Disposition: form-data; name=x\r\n\r\nno end",
            "b"
        ),
        Err(MultipartError::NoClosingBoundary)
    );
    assert_eq!(parse_parts(b"--bX", "b"), Err(MultipartError::NoClosingBoundary));
    assert_eq!(
        parse_parts(b"--b\r\nno colon\r\n\r\nx\r\n--b--", "b"),
        Err(MultipartError::BadHeaders)
    );
    assert_eq!(
        parse_parts(b"--b\r\nContent-Disposition: attachment\r\n\r\nx\r\n--b--", "b"),
        Err(MultipartError::BadHeaders)
    );
    assert_eq!(
        parse_parts(b"--b\r\nX: \xff\r\n\r\nx\r\n--b--", "b"),
        Err(MultipartError::BadHeaders)
    );
    assert_eq!(parse_parts(b"--b\r\nX: y", "b"), Err(MultipartError::BadHeaders));
    let many: Vec<_> = (0..65).map(|i| (format!("f{i}"), None, vec![])).collect();
    assert_eq!(
        parse_parts(&encode("b", &many), "b"),
        Err(MultipartError::TooManyParts(64))
    );
}

proptest! {
    #[test]
    fn encoded_forms_parse_back(
        fields in proptest::collection::vec(("[a-z_]{1,12}", proptest::option::of("[a-z.]{1,12}"), proptest::collection::vec(any::<u8>(), 0..300)), 0..8),
    ) {
        let boundary = "telltype-boundary-7f3a";
        let parts: Vec<_> = fields.into_iter().filter(|(_, _, d)| !d.windows(boundary.len()).any(|w| w == boundary.as_bytes())).collect();
        let body = encode(boundary, &parts);
        let parsed = parse_parts(&body, boundary).unwrap();
        prop_assert_eq!(parsed.len(), parts.len());
        for (got, (name, filename, data)) in parsed.iter().zip(&parts) {
            prop_assert_eq!(got.name.as_ref(), Some(name));
            prop_assert_eq!(got.filename.as_ref(), filename.as_ref());
            prop_assert_eq!(&body[got.data.clone()], data.as_slice());
        }
    }

    #[test]
    fn any_bytes_never_panic(data in proptest::collection::vec(any::<u8>(), 0..400), cut in 0usize..400) {
        let _ = parse_parts(&data, "b");
        let mut framed = b"--b\r\n".to_vec();
        framed.extend_from_slice(&data);
        let _ = parse_parts(&framed, "b");
        let form = encode("b", &[("file".into(), Some("f".into()), data.clone())]);
        let _ = parse_parts(&form[..cut.min(form.len())], "b");
    }

    #[test]
    fn content_type_parsing_never_panics(text in ".{0,80}") {
        let _ = boundary(&text);
        let _ = boundary(&format!("multipart/form-data;{text}"));
    }
}
