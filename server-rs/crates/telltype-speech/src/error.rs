//! Typed errors of the speech crate. The HTTP layer maps them to statuses and codes in one place.

/// Why a transcription request failed.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SpeechError {
    #[error("the speech model is still loading")]
    NotReady,
    #[error("{in_flight} transcriptions in flight, limit {limit}")]
    Busy { in_flight: usize, limit: usize },
    #[error("{0}")]
    Decode(#[from] DecodeError),
    #[error("recognition did not finish within {seconds} s")]
    Timeout { seconds: u64 },
    #[error("speech recognition failed: {0}")]
    Recognize(#[from] RecognizeError),
}

/// Why an upload could not be turned into samples.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum DecodeError {
    #[error("the upload is not audio this server can decode: {0}")]
    Unsupported(String),
    #[error("the upload contains no audio")]
    Empty,
    #[error("audio is longer than {minutes} minutes")]
    TooLong { minutes: String },
}

/// A failure inside the recogniser for one stream.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum RecognizeError {
    #[error("the recogniser returned no result")]
    NoResult,
    #[error("the recognition task stopped unexpectedly")]
    TaskFailed,
}

/// Why the recogniser could not be loaded at start.
#[derive(Debug, thiserror::Error)]
pub enum LoadError {
    #[error("model file {0} does not exist")]
    MissingFile(String),
    #[error("cannot derive the hot-word vocabulary: {0}")]
    Vocabulary(String),
    #[error("sherpa-onnx could not create the recogniser (see its message above)")]
    Create,
}
