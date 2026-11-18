#!/usr/bin/env bash
# Download the models Telltype Server uses into TELLTYPE_MODEL_DIR and verify their SHA-256.
# Idempotent: files that are present and verify are left alone; partial downloads resume.
#
#   TELLTYPE_MODEL_DIR=~/models/telltype scripts/download_models.sh
#
# Speech: Parakeet Unified EN 0.6B int8 (sherpa-onnx export). Cleanup: Gemma 4 E4B QAT q4_0.
# Commands: Qwen3.5 4B Q4_K_M. Hashes are the publishers' (GitHub asset digest, Hugging Face
# lfs.sha256).
set -euo pipefail

model_dir="${TELLTYPE_MODEL_DIR:-$HOME/models/telltype}"

parakeet_name="sherpa-onnx-nemo-parakeet-unified-en-0.6b-int8-non-streaming"
parakeet_url="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$parakeet_name.tar.bz2"
parakeet_sha256="99f63605b3a85a54c250c0869670a687b7d6598a47bf2421515e1f839a76e150"
# Files inside the archive, checked after extraction too.
parakeet_files=(
  "encoder.int8.onnx|6716910b7a0833997fec7a410494c995d70124001a0e9b66d6370d6aced577e0"
  "decoder.int8.onnx|a5e223392c90e75f8144cdb5eb95af7625db389e39edef2bd1a9c872b3298fe6"
  "joiner.int8.onnx|869f43f7d24595c55581ad3bf249a935fb8a71389fbdaa7504b9f46f93140f8a"
  "tokens.txt|dc0b4584ab2e4ddbf888425c076c61b736e7356a015250db7d307e6f1a8188ff"
)
# repo|file|sha256
ggufs=(
  "google/gemma-4-E4B-it-qat-q4_0-gguf|gemma-4-E4B_q4_0-it.gguf|676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee"
  "bartowski/Qwen_Qwen3.5-4B-GGUF|Qwen_Qwen3.5-4B-Q4_K_M.gguf|13c16f426047e2de38cd075bdade4a7bcbc8c774384876f677740cda65f8a983"
)

verify() { # file expected-sha256
  local got
  got=$(sha256sum "$1" | cut -d' ' -f1)
  if [ "$got" != "$2" ]; then
    echo "SHA-256 MISMATCH for $1: got $got, expected $2" >&2
    return 1
  fi
  echo "ok  $2  $1"
}

download() { # url destination
  curl -fL --retry 3 --connect-timeout 30 -C - -o "$2.part" "$1"
  mv -- "$2.part" "$2"
}

mkdir -p "$model_dir"
if [ ! -f "$model_dir/$parakeet_name/tokens.txt" ]; then
  archive="$model_dir/$parakeet_name.tar.bz2"
  [ -f "$archive" ] || download "$parakeet_url" "$archive"
  verify "$archive" "$parakeet_sha256"
  tar -xjf "$archive" -C "$model_dir"
  rm -- "$archive"
fi
for entry in "${parakeet_files[@]}"; do
  IFS='|' read -r file sha <<<"$entry"
  verify "$model_dir/$parakeet_name/$file" "$sha"
done

for entry in "${ggufs[@]}"; do
  IFS='|' read -r repo file sha <<<"$entry"
  if [ ! -f "$model_dir/$file" ]; then
    download "https://huggingface.co/$repo/resolve/main/$file" "$model_dir/$file"
  fi
  verify "$model_dir/$file" "$sha"
done
