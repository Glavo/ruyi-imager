// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

use std::env;
use std::ffi::OsString;
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

const ICON_RESOURCE_ID: u32 = 1;
const ICON_FILE_NAME: &str = "ruyi-logo.ico";

fn main() {
    let icon_path = workspace_root().join("resources").join(ICON_FILE_NAME);
    println!("cargo:rerun-if-changed={}", icon_path.display());

    if env::var("CARGO_CFG_TARGET_OS").as_deref() != Ok("windows") {
        return;
    }

    if let Err(error) = embed_windows_icon(&icon_path) {
        panic!("failed to embed Windows launcher icon: {error}");
    }
}

fn embed_windows_icon(icon_path: &Path) -> io::Result<()> {
    let out_dir = PathBuf::from(
        env::var_os("OUT_DIR")
            .ok_or_else(|| io::Error::new(io::ErrorKind::NotFound, "OUT_DIR is not set"))?,
    );
    let icon_output = out_dir.join(ICON_FILE_NAME);
    let rc_output = out_dir.join("launcher-icon.rc");
    let res_output = out_dir.join("launcher-icon.res");

    fs::copy(icon_path, &icon_output)?;
    fs::write(
        &rc_output,
        format!("{ICON_RESOURCE_ID} ICON \"{ICON_FILE_NAME}\"\n"),
    )?;

    let compiler = resource_compiler()?;
    let output = Command::new(&compiler)
        .current_dir(&out_dir)
        .arg("/nologo")
        .arg(format!("/fo{}", res_output.display()))
        .arg(&rc_output)
        .output()?;
    if !output.status.success() {
        let message = format!(
            "{} failed with status {}:\n{}{}",
            compiler.display(),
            output.status,
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
        return Err(io::Error::other(message));
    }

    println!("cargo:rustc-link-arg-bins={}", res_output.display());
    Ok(())
}

fn resource_compiler() -> io::Result<PathBuf> {
    for env_name in target_resource_compiler_env_names() {
        if let Some(path) = non_empty_env(&env_name) {
            return Ok(path.into());
        }
    }
    for candidate in path_candidates() {
        if candidate.is_file() {
            return Ok(candidate);
        }
    }
    for name in ["llvm-rc.exe", "llvm-rc", "rc.exe", "rc"] {
        if command_exists(name) {
            return Ok(PathBuf::from(name));
        }
    }
    Err(io::Error::new(
        io::ErrorKind::NotFound,
        "no Windows resource compiler found; set RC or install rc.exe/llvm-rc",
    ))
}

fn target_resource_compiler_env_names() -> Vec<String> {
    let mut names = Vec::new();
    if let Ok(target) = env::var("TARGET") {
        let normalized = target.replace('-', "_");
        names.push(format!("RC_{normalized}"));
        names.push(format!("RC_{}", normalized.to_uppercase()));
    }
    names.push("RC".to_string());
    names
}

fn path_candidates() -> Vec<PathBuf> {
    let mut candidates = Vec::new();
    if let Some(program_files_x86) = env::var_os("ProgramFiles(x86)") {
        candidates.extend(windows_kit_resource_compilers(PathBuf::from(
            program_files_x86,
        )));
    }
    if let Some(program_files) = env::var_os("ProgramFiles") {
        candidates.extend(windows_kit_resource_compilers(PathBuf::from(program_files)));
    }
    candidates
}

fn windows_kit_resource_compilers(program_files: PathBuf) -> Vec<PathBuf> {
    let bin_dir = program_files.join("Windows Kits").join("10").join("bin");
    let Ok(entries) = fs::read_dir(&bin_dir) else {
        return Vec::new();
    };

    let mut versions = entries
        .filter_map(Result::ok)
        .map(|entry| entry.path())
        .filter(|path| path.is_dir())
        .collect::<Vec<_>>();
    versions.sort();
    versions.reverse();

    let mut candidates = Vec::new();
    for version in versions {
        candidates.push(version.join("x64").join("rc.exe"));
        candidates.push(version.join("arm64").join("rc.exe"));
        candidates.push(version.join("x86").join("rc.exe"));
    }
    candidates
}

fn command_exists(name: &str) -> bool {
    Command::new(name)
        .arg("/?")
        .output()
        .map(|_| true)
        .unwrap_or(false)
}

fn workspace_root() -> PathBuf {
    PathBuf::from(env::var_os("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR is not set"))
        .parent()
        .expect("launcher manifest directory has no parent")
        .to_path_buf()
}

fn non_empty_env(name: &str) -> Option<OsString> {
    env::var_os(name).filter(|value| !value.is_empty())
}
