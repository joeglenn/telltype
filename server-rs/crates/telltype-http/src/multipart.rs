//! A bounded, in-memory `multipart/form-data` parser (RFC 7578) for uploads already read into memory.
//!
//! It returns byte ranges into the body, so the audio is never copied, and never panics on any
//! input (see the property tests).

use std::ops::Range;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MultipartError {
    #[error("no boundary in the Content-Type")]
    NoBoundary,
    #[error("the first boundary is missing")]
    NoFirstBoundary,
    #[error("the closing boundary is missing")]
    NoClosingBoundary,
    #[error("a part's headers are malformed")]
    BadHeaders,
    #[error("more than {0} parts")]
    TooManyParts(usize),
}

/// One part: its form name, file name and content type (if given) and where its data is.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Part {
    pub name: Option<String>,
    pub filename: Option<String>,
    pub content_type: Option<String>,
    pub data: Range<usize>,
}

const MAX_PARTS: usize = 64;
const MAX_HEADER_BYTES: usize = 8 * 1024;

/// The `boundary` parameter of a `multipart/form-data` Content-Type (1 to 70 characters).
#[must_use]
pub fn boundary(content_type: &str) -> Option<String> {
    let (kind, params) = content_type.split_once(';').unwrap_or((content_type, ""));
    if !kind.trim().eq_ignore_ascii_case("multipart/form-data") {
        return None;
    }
    let value = parameters(params)
        .into_iter()
        .find(|(k, _)| k.eq_ignore_ascii_case("boundary"))?
        .1;
    (1..=70).contains(&value.len()).then_some(value)
}

/// Split `body` into parts.
pub fn parse_parts(body: &[u8], boundary: &str) -> Result<Vec<Part>, MultipartError> {
    let delimiter = [b"--", boundary.as_bytes()].concat();
    // The first delimiter may follow a preamble, but then only after a line break.
    let mut pos = if body.starts_with(&delimiter) {
        delimiter.len()
    } else {
        let next_line = [b"\r\n", delimiter.as_slice()].concat();
        find(body, &next_line, 0).ok_or(MultipartError::NoFirstBoundary)? + next_line.len()
    };
    let separator = [b"\r\n", delimiter.as_slice()].concat();
    let mut parts = Vec::new();
    loop {
        let rest = &body[pos..];
        if rest.starts_with(b"--") {
            return Ok(parts);
        }
        // Transport padding (spaces, tabs) may follow a delimiter before its line break.
        let padding = rest.iter().take_while(|b| **b == b' ' || **b == b'\t').count();
        if !rest[padding..].starts_with(b"\r\n") {
            return Err(MultipartError::NoClosingBoundary);
        }
        pos += padding + 2;
        if parts.len() == MAX_PARTS {
            return Err(MultipartError::TooManyParts(MAX_PARTS));
        }
        // Searching from the line break just consumed finds an empty header block too.
        let limit = body.len().min(pos + MAX_HEADER_BYTES);
        let header_end = find(&body[..limit], b"\r\n\r\n", pos - 2).ok_or(MultipartError::BadHeaders)?;
        let headers =
            std::str::from_utf8(&body[pos..header_end.max(pos)]).map_err(|_| MultipartError::BadHeaders)?;
        let data_start = header_end + 4;
        let data_end = find(body, &separator, data_start).ok_or(MultipartError::NoClosingBoundary)?;
        parts.push(part(headers, data_start..data_end)?);
        pos = data_end + separator.len();
    }
}

fn part(headers: &str, data: Range<usize>) -> Result<Part, MultipartError> {
    let mut part = Part {
        name: None,
        filename: None,
        content_type: None,
        data,
    };
    for line in headers.split("\r\n").filter(|l| !l.is_empty()) {
        let (name, value) = line.split_once(':').ok_or(MultipartError::BadHeaders)?;
        let name = name.trim();
        if name.eq_ignore_ascii_case("content-disposition") {
            let (kind, params) = value.split_once(';').unwrap_or((value, ""));
            if !kind.trim().eq_ignore_ascii_case("form-data") {
                return Err(MultipartError::BadHeaders);
            }
            for (key, value) in parameters(params) {
                if key.eq_ignore_ascii_case("name") {
                    part.name = Some(value);
                } else if key.eq_ignore_ascii_case("filename") {
                    part.filename = Some(value);
                }
            }
        } else if name.eq_ignore_ascii_case("content-type") {
            part.content_type = Some(value.trim().to_owned());
        }
    }
    Ok(part)
}

/// `; key=value; key="quoted \" value"` -> pairs.
fn parameters(text: &str) -> Vec<(String, String)> {
    let mut out = Vec::new();
    let mut chars = text.chars().peekable();
    loop {
        while chars.next_if(|c| *c == ';' || c.is_whitespace()).is_some() {}
        let key: String = std::iter::from_fn(|| chars.next_if(|c| *c != '=' && *c != ';')).collect();
        if key.trim().is_empty() {
            if chars.peek().is_none() {
                return out;
            }
            chars.next();
            continue;
        }
        let mut value = String::new();
        if chars.next_if_eq(&'=').is_some() {
            if chars.next_if_eq(&'"').is_some() {
                while let Some(c) = chars.next() {
                    match c {
                        '"' => break,
                        '\\' => value.extend(chars.next()),
                        other => value.push(other),
                    }
                }
            } else {
                let raw: String = std::iter::from_fn(|| chars.next_if(|c| *c != ';')).collect();
                value.push_str(raw.trim());
            }
        }
        out.push((key.trim().to_owned(), value));
    }
}

/// First position of `needle` in `haystack` at or after `from`.
fn find(haystack: &[u8], needle: &[u8], from: usize) -> Option<usize> {
    let first = *needle.first()?;
    let mut start = from;
    while start + needle.len() <= haystack.len() {
        let offset = haystack[start..=haystack.len() - needle.len()]
            .iter()
            .position(|b| *b == first)?;
        let at = start + offset;
        if haystack[at..at + needle.len()] == *needle {
            return Some(at);
        }
        start = at + 1;
    }
    None
}

#[cfg(test)]
mod tests;
