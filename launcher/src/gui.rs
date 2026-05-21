// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

#![cfg_attr(all(windows, not(test)), windows_subsystem = "windows")]

mod launcher;

use std::io;
use std::process;

fn main() {
    if let Err(error) = launcher::run(launcher::LaunchKind::Gui) {
        report_error(&error);
        process::exit(1);
    }
}

#[cfg(windows)]
fn report_error(error: &io::Error) {
    use std::ffi::c_void;

    const MB_ICONERROR: u32 = 0x00000010;
    let message = wide_string(&format!("Ruyi Imager launcher failed:\n{error}"));
    let caption = wide_string("Ruyi Imager");

    #[link(name = "User32")]
    unsafe extern "system" {
        fn MessageBoxW(
            h_wnd: *mut c_void,
            lp_text: *const u16,
            lp_caption: *const u16,
            u_type: u32,
        ) -> i32;
    }

    unsafe {
        MessageBoxW(
            std::ptr::null_mut(),
            message.as_ptr(),
            caption.as_ptr(),
            MB_ICONERROR,
        );
    }
}

#[cfg(not(windows))]
fn report_error(error: &io::Error) {
    eprintln!("Ruyi Imager launcher failed: {error}");
}

#[cfg(windows)]
fn wide_string(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(std::iter::once(0)).collect()
}
