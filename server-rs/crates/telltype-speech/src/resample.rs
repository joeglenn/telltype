//! Resampling to the recogniser's rate with sherpa-onnx's windowed-sinc `LinearResampler`
//! (the Kaldi resampler), the same code sherpa-onnx itself uses for other rates.

use sherpa_onnx::LinearResampler;

use crate::error::DecodeError;

pub(crate) fn to_target_rate(samples: &[f32], from: u32, to: u32) -> Result<Vec<f32>, DecodeError> {
    if from == to {
        return Ok(samples.to_vec());
    }
    let unsupported = || DecodeError::Unsupported(format!("cannot resample from {from} Hz"));
    let from_hz = i32::try_from(from).map_err(|_| unsupported())?;
    let to_hz = i32::try_from(to).map_err(|_| unsupported())?;
    let resampler = LinearResampler::create(from_hz, to_hz).ok_or_else(unsupported)?;
    Ok(resampler.resample(samples, true))
}

#[cfg(test)]
mod tests {
    use super::to_target_rate;

    #[test]
    fn same_rate_is_a_copy_and_other_rates_scale_the_length() {
        let one_second: Vec<f32> = (0..44_100).map(|i| ((i as f32) * 0.01).sin() * 0.5).collect();
        assert_eq!(to_target_rate(&one_second, 44_100, 44_100).unwrap(), one_second);
        let out = to_target_rate(&one_second, 44_100, 16_000).unwrap();
        assert!((15_900..=16_100).contains(&out.len()), "{}", out.len());
        let up = to_target_rate(&out, 16_000, 48_000).unwrap();
        assert!((47_700..=48_300).contains(&up.len()), "{}", up.len());
    }

    #[test]
    fn rates_beyond_i32_are_refused() {
        assert!(to_target_rate(&[0.0], u32::MAX, 16_000).is_err());
    }
}
