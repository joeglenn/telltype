//! Dictionary terms as hot words, and the vocabulary sherpa-onnx needs to tokenise them.

/// Split the request's comma-separated dictionary terms into clean, unique hot words.
///
/// `:` introduces a per-word score in sherpa-onnx's hot-word syntax and NUL cannot cross the C API,
/// so both become spaces; whitespace is collapsed; duplicates (ignoring case) are dropped; at most
/// `limit` terms are kept.
#[must_use]
pub fn parse_hotwords(prompt: Option<&str>, limit: usize) -> Vec<String> {
    let Some(prompt) = prompt else { return Vec::new() };
    let mut seen = std::collections::HashSet::new();
    let mut terms = Vec::new();
    for raw in prompt.split(',') {
        let cleaned = raw.replace([':', '\0'], " ");
        let term = cleaned.split_whitespace().collect::<Vec<_>>().join(" ");
        if !term.is_empty() && seen.insert(term.to_lowercase()) {
            terms.push(term);
            if terms.len() == limit {
                break;
            }
        }
    }
    terms
}

/// Derive the `bpe.vocab` text from the model's `tokens.txt`.
///
/// The model ships only `tokens.txt`, whose order is the `SentencePiece` BPE merge order, and a BPE
/// vocabulary's score is the negative merge rank, so `-id` reproduces the tokenisation. `<blk>` is
/// not a piece; `<unk>` scores 0.
pub fn bpe_vocab_from_tokens(tokens: &str) -> Result<String, String> {
    let mut out = String::with_capacity(tokens.len() + tokens.len() / 4);
    for (number, line) in tokens.lines().enumerate() {
        if line.is_empty() {
            continue;
        }
        let (token, id) = line
            .rsplit_once(' ')
            .ok_or_else(|| format!("tokens.txt line {} has no id", number + 1))?;
        let id: i64 = id
            .parse()
            .map_err(|_| format!("tokens.txt line {} has a bad id", number + 1))?;
        if token == "<blk>" {
            continue;
        }
        let score = if token == "<unk>" { 0 } else { -id };
        out.push_str(token);
        out.push('\t');
        out.push_str(&score.to_string());
        out.push('\n');
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::{bpe_vocab_from_tokens, parse_hotwords};

    #[test]
    fn hotwords_are_cleaned_deduplicated_and_limited() {
        assert_eq!(
            parse_hotwords(Some("Kubernetes, kubernetes , Hyrule:3,,Igor"), 100),
            ["Kubernetes", "Hyrule 3", "Igor"]
        );
        assert_eq!(parse_hotwords(Some("a, b, c"), 2), ["a", "b"]);
        assert_eq!(
            parse_hotwords(Some("x\0y,  spaced   out "), 10),
            ["x y", "spaced out"]
        );
        assert!(parse_hotwords(None, 10).is_empty());
        assert!(parse_hotwords(Some(" , ,"), 10).is_empty());
    }

    #[test]
    fn vocab_uses_negative_rank_and_skips_blank() {
        let vocab = bpe_vocab_from_tokens("<blk> 1024\n<unk> 0\n\u{2581}the 1\nab 2\n").unwrap();
        assert_eq!(vocab, "<unk>\t0\n\u{2581}the\t-1\nab\t-2\n");
        assert!(bpe_vocab_from_tokens("broken").unwrap_err().contains("line 1"));
        assert!(bpe_vocab_from_tokens("a x").unwrap_err().contains("bad id"));
    }
}
