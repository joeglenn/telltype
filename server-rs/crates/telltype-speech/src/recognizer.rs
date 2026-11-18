//! The recogniser: a trait the engine depends on, and the sherpa-onnx implementation.

use std::path::{Path, PathBuf};

use sherpa_onnx::{OfflineRecognizer, OfflineRecognizerConfig};

use crate::decode::SAMPLE_RATE;
use crate::error::{LoadError, RecognizeError};
use crate::hotwords::bpe_vocab_from_tokens;

/// Turns 16 kHz mono samples into text. Called from blocking worker threads.
pub trait Recognizer: Send + Sync + 'static {
    fn recognize(&self, samples: &[f32], hotwords: &[String]) -> Result<String, RecognizeError>;
}

/// How to load Parakeet (or another `NeMo` transducer export of the same shape).
#[derive(Debug, Clone)]
pub struct SherpaOptions {
    pub model_dir: PathBuf,
    pub threads: u16,
    /// `Some((score, vocab_dir))` switches on modified beam search with per-request hot words.
    pub hotwords: Option<(f32, PathBuf)>,
}

/// Parakeet Unified EN 0.6B (int8) through sherpa-onnx on the CPU. Greedy search by default.
pub struct SherpaRecognizer {
    recognizer: OfflineRecognizer,
    hotwords: bool,
}

impl SherpaRecognizer {
    /// Load the model files `encoder.int8.onnx`, `decoder.int8.onnx`, `joiner.int8.onnx` and
    /// `tokens.txt` from `options.model_dir`. Takes about a second for Parakeet.
    pub fn load(options: &SherpaOptions) -> Result<Self, LoadError> {
        let file = |name: &str| -> Result<String, LoadError> {
            let path = options.model_dir.join(name);
            if path.is_file() {
                Ok(path.to_string_lossy().into_owned())
            } else {
                Err(LoadError::MissingFile(path.display().to_string()))
            }
        };
        let mut config = OfflineRecognizerConfig::default();
        config.model_config.transducer.encoder = Some(file("encoder.int8.onnx")?);
        config.model_config.transducer.decoder = Some(file("decoder.int8.onnx")?);
        config.model_config.transducer.joiner = Some(file("joiner.int8.onnx")?);
        let tokens = file("tokens.txt")?;
        config.model_config.tokens = Some(tokens.clone());
        config.model_config.num_threads = i32::from(options.threads);
        config.model_config.provider = Some("cpu".to_owned());
        config.model_config.model_type = Some("nemo_transducer".to_owned());
        config.decoding_method = Some("greedy_search".to_owned());
        if let Some((score, vocab_dir)) = &options.hotwords {
            let vocab = write_vocab(Path::new(&tokens), vocab_dir)?;
            config.decoding_method = Some("modified_beam_search".to_owned());
            config.max_active_paths = 4;
            config.model_config.modeling_unit = Some("bpe".to_owned());
            config.model_config.bpe_vocab = Some(vocab);
            config.hotwords_score = *score;
        }
        let recognizer = OfflineRecognizer::create(&config).ok_or(LoadError::Create)?;
        Ok(Self {
            recognizer,
            hotwords: options.hotwords.is_some(),
        })
    }
}

fn write_vocab(tokens: &Path, dir: &Path) -> Result<String, LoadError> {
    let text = std::fs::read_to_string(tokens).map_err(|e| LoadError::Vocabulary(e.to_string()))?;
    let vocab = bpe_vocab_from_tokens(&text).map_err(LoadError::Vocabulary)?;
    let path = dir.join("bpe.vocab");
    std::fs::write(&path, vocab).map_err(|e| LoadError::Vocabulary(format!("{}: {e}", path.display())))?;
    Ok(path.to_string_lossy().into_owned())
}

impl Recognizer for SherpaRecognizer {
    fn recognize(&self, samples: &[f32], hotwords: &[String]) -> Result<String, RecognizeError> {
        let stream = if self.hotwords && !hotwords.is_empty() {
            // parse_hotwords removed NUL bytes, which the C API cannot carry.
            self.recognizer.create_stream_with_hotwords(&hotwords.join("\n"))
        } else {
            self.recognizer.create_stream()
        };
        #[allow(clippy::cast_possible_wrap, reason = "16000 fits in i32")]
        stream.accept_waveform(SAMPLE_RATE as i32, samples);
        self.recognizer.decode(&stream);
        let result = stream.get_result().ok_or(RecognizeError::NoResult)?;
        Ok(result.text.trim().to_owned())
    }
}

#[cfg(test)]
mod tests {
    use super::{SherpaOptions, SherpaRecognizer};
    use crate::error::LoadError;

    #[test]
    fn missing_model_files_are_named() {
        let options = SherpaOptions {
            model_dir: "/nonexistent/parakeet".into(),
            threads: 1,
            hotwords: None,
        };
        match SherpaRecognizer::load(&options) {
            Err(LoadError::MissingFile(path)) => assert!(path.ends_with("encoder.int8.onnx"), "{path}"),
            Err(other) => panic!("unexpected error {other}"),
            Ok(_) => panic!("loaded without files"),
        }
    }
}
