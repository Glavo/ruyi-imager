// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

use std::env;
#[cfg(test)]
use std::ffi::{OsStr, OsString};
use std::fs;
use std::io;
use std::path::{Path, PathBuf};
use std::process::Command;

const MAIN_CLASS: &str = "org.glavo.ruyi.imager.Main";
const JVM_ARGS_FILE: &str = "ruyi-imager.jvmargs";

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
#[allow(dead_code)]
pub enum LaunchKind {
    Console,
    Gui,
}

pub fn run(kind: LaunchKind) -> io::Result<i32> {
    let args = env::args_os().skip(1).collect::<Vec<_>>();
    let current_exe = env::current_exe()?;
    let bin_dir = parent(&current_exe, "launcher executable directory")?;
    let app_home = parent(bin_dir, "application home directory")?;
    let mut command = Command::new(java_executable(app_home, kind));

    for arg in jvm_args(bin_dir)? {
        command.arg(arg);
    }
    command.arg("-cp");
    command.arg(app_home.join("lib").join("*"));
    command.arg(MAIN_CLASS);
    command.args(args);

    match kind {
        LaunchKind::Console => {
            let status = command.status()?;
            Ok(status.code().unwrap_or(1))
        }
        LaunchKind::Gui => {
            command.spawn()?;
            Ok(0)
        }
    }
}

fn parent<'a>(path: &'a Path, name: &str) -> io::Result<&'a Path> {
    path.parent()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, format!("missing {name}")))
}

fn java_executable(app_home: &Path, kind: LaunchKind) -> PathBuf {
    let bin_dir = app_home.join("runtime").join("bin");
    if kind == LaunchKind::Gui {
        let javaw = bin_dir.join("javaw.exe");
        if javaw.is_file() {
            return javaw;
        }
    }
    bin_dir.join("java.exe")
}

fn jvm_args(bin_dir: &Path) -> io::Result<Vec<String>> {
    let path = bin_dir.join(JVM_ARGS_FILE);
    match fs::read_to_string(path) {
        Ok(text) => Ok(parse_jvm_args(&text)),
        Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(default_jvm_args()),
        Err(error) => Err(error),
    }
}

fn parse_jvm_args(text: &str) -> Vec<String> {
    text.lines()
        .map(str::trim)
        .filter(|line| !line.is_empty() && !line.starts_with('#'))
        .map(str::to_owned)
        .collect()
}

fn default_jvm_args() -> Vec<String> {
    vec![
        "--enable-native-access=ALL-UNNAMED,javafx.graphics".to_owned(),
        "--add-modules=javafx.base,javafx.controls,javafx.graphics".to_owned(),
    ]
}

#[cfg(test)]
fn is_gui_launch(args: &[OsString]) -> bool {
    args.is_empty() || (args.len() == 1 && args[0] == OsStr::new("gui"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_jvm_args_file() {
        let parsed = parse_jvm_args(
            "\n\
             # comment\n\
             --enable-native-access=ALL-UNNAMED,javafx.graphics\n\
             --add-modules=javafx.base,javafx.controls,javafx.graphics\n",
        );

        assert_eq!(
            parsed,
            vec![
                "--enable-native-access=ALL-UNNAMED,javafx.graphics",
                "--add-modules=javafx.base,javafx.controls,javafx.graphics",
            ]
        );
    }

    #[test]
    fn detects_gui_launch_arguments() {
        assert!(is_gui_launch(&[]));
        assert!(is_gui_launch(&[OsString::from("gui")]));
        assert!(!is_gui_launch(&[OsString::from("list")]));
        assert!(!is_gui_launch(&[
            OsString::from("gui"),
            OsString::from("--help")
        ]));
    }
}
