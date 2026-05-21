// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

mod launcher;

use std::process;

fn main() {
    match launcher::run(launcher::LaunchKind::Console) {
        Ok(exit_code) => process::exit(exit_code),
        Err(error) => {
            eprintln!("Ruyi Imager launcher failed: {error}");
            process::exit(1);
        }
    }
}
