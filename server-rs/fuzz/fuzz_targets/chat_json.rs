//! The chat request check the server runs before routing: never a panic.
#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let _ = telltype_upstream::check_chat_request(data);
});
