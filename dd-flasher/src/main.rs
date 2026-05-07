// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

const BUFFER_SIZE: usize = 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Operation {
    Write,
    Verify,
}

impl Operation {
    fn as_str(self) -> &'static str {
        match self {
            Operation::Write => "write",
            Operation::Verify => "verify",
        }
    }
}

#[derive(Debug)]
struct Request {
    operation: Operation,
    source: PathBuf,
    target: PathBuf,
    total_bytes: u64,
    event_log: Option<PathBuf>,
    stdout: bool,
}

fn main() -> ExitCode {
    let request = match parse_args(env::args().skip(1)) {
        Ok(request) => request,
        Err(error) => {
            print_error(&error.to_string());
            return ExitCode::from(2);
        }
    };

    let mut sink = match EventSink::new(request.event_log.as_deref(), request.stdout) {
        Ok(sink) => sink,
        Err(error) => {
            print_error(&error.to_string());
            return ExitCode::from(2);
        }
    };

    match run_request(&request, &mut sink) {
        Ok(success) => {
            if let Err(error) = sink.complete(success) {
                print_error(&error);
                ExitCode::from(2)
            } else {
                ExitCode::SUCCESS
            }
        }
        Err(error) => {
            let _ = sink.error(&error.to_string());
            ExitCode::from(2)
        }
    }
}

fn run_request(request: &Request, sink: &mut EventSink) -> Result<bool, String> {
    validate_request(&request)?;
    match request.operation {
        Operation::Write => {
            write_image(request, sink)?;
            Ok(true)
        }
        Operation::Verify => verify_image(request, sink),
    }
}

fn parse_args<I>(args: I) -> Result<Request, String>
where
    I: IntoIterator<Item = String>,
{
    let mut args = args.into_iter();
    let operation = match args.next().as_deref() {
        Some("write") => Operation::Write,
        Some("verify") => Operation::Verify,
        Some(other) => return Err(format!("unsupported operation: {other}")),
        None => return Err("missing operation".to_string()),
    };

    let mut source = None;
    let mut target = None;
    let mut total_bytes = None;
    let mut event_log = None;
    let mut stdout = true;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--source" => source = Some(PathBuf::from(next_value(&mut args, "--source")?)),
            "--target" => target = Some(PathBuf::from(next_value(&mut args, "--target")?)),
            "--event-log" => event_log = Some(PathBuf::from(next_value(&mut args, "--event-log")?)),
            "--no-stdout" => stdout = false,
            "--total-bytes" => {
                let value = next_value(&mut args, "--total-bytes")?;
                total_bytes = Some(
                    value
                        .parse::<u64>()
                        .map_err(|_| format!("invalid --total-bytes value: {value}"))?,
                );
            }
            "--help" | "-h" => return Err(usage()),
            other => return Err(format!("unknown argument: {other}")),
        }
    }

    Ok(Request {
        operation,
        source: source.ok_or_else(|| "missing --source".to_string())?,
        target: target.ok_or_else(|| "missing --target".to_string())?,
        total_bytes: total_bytes.ok_or_else(|| "missing --total-bytes".to_string())?,
        event_log,
        stdout,
    })
}

fn next_value<I>(args: &mut I, name: &str) -> Result<String, String>
where
    I: Iterator<Item = String>,
{
    args.next()
        .ok_or_else(|| format!("missing value for {name}"))
}

fn usage() -> String {
    "usage: dd-flasher <write|verify> --source <path> --target <path> --total-bytes <bytes> [--event-log <path>] [--no-stdout]".to_string()
}

fn validate_request(request: &Request) -> Result<(), String> {
    let source_metadata = fs::metadata(&request.source)
        .map_err(|error| format!("failed to read source metadata: {error}"))?;
    if !source_metadata.is_file() {
        return Err("source is not a regular file".to_string());
    }
    if source_metadata.len() != request.total_bytes {
        return Err(format!(
            "source size mismatch: expected {}, actual {}",
            request.total_bytes,
            source_metadata.len()
        ));
    }
    if request.total_bytes == 0 {
        return Err("source image is empty".to_string());
    }
    if !request.stdout && request.event_log.is_none() {
        return Err("--no-stdout requires --event-log".to_string());
    }
    if same_path(&request.source, &request.target) {
        return Err("source and target refer to the same path".to_string());
    }
    Ok(())
}

fn same_path(left: &Path, right: &Path) -> bool {
    match (fs::canonicalize(left), fs::canonicalize(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

fn write_image(request: &Request, sink: &mut EventSink) -> Result<(), String> {
    let mut source =
        File::open(&request.source).map_err(|error| format!("failed to open source: {error}"))?;
    let mut target = OpenOptions::new()
        .write(true)
        .open(&request.target)
        .map_err(|error| format!("failed to open target for writing: {error}"))?;

    let mut buffer = vec![0u8; BUFFER_SIZE];
    let mut written = 0u64;
    loop {
        let read = source
            .read(&mut buffer)
            .map_err(|error| format!("failed to read source: {error}"))?;
        if read == 0 {
            break;
        }
        target
            .write_all(&buffer[..read])
            .map_err(|error| format!("failed to write target: {error}"))?;
        written += read as u64;
        sink.progress(request.operation, written, request.total_bytes)?;
    }

    if written != request.total_bytes {
        return Err(format!(
            "write length mismatch: expected {}, actual {}",
            request.total_bytes, written
        ));
    }
    target
        .sync_all()
        .map_err(|error| format!("failed to flush target: {error}"))?;
    Ok(())
}

fn verify_image(request: &Request, sink: &mut EventSink) -> Result<bool, String> {
    let mut source =
        File::open(&request.source).map_err(|error| format!("failed to open source: {error}"))?;
    let mut target = File::open(&request.target)
        .map_err(|error| format!("failed to open target for reading: {error}"))?;

    let mut source_buffer = vec![0u8; BUFFER_SIZE];
    let mut target_buffer = vec![0u8; BUFFER_SIZE];
    let mut verified = 0u64;
    while verified < request.total_bytes {
        let chunk_size = BUFFER_SIZE.min((request.total_bytes - verified) as usize);
        let source_read = read_exact_or_eof(&mut source, &mut source_buffer[..chunk_size])
            .map_err(|error| format!("failed to read source: {error}"))?;
        let target_read = read_exact_or_eof(&mut target, &mut target_buffer[..chunk_size])
            .map_err(|error| format!("failed to read target: {error}"))?;
        if source_read != target_read || source_read != chunk_size {
            return Ok(false);
        }
        if source_buffer[..chunk_size] != target_buffer[..chunk_size] {
            return Ok(false);
        }
        verified += chunk_size as u64;
        sink.progress(request.operation, verified, request.total_bytes)?;
    }
    Ok(true)
}

fn read_exact_or_eof(input: &mut File, buffer: &mut [u8]) -> io::Result<usize> {
    let mut total = 0;
    while total < buffer.len() {
        let read = input.read(&mut buffer[total..])?;
        if read == 0 {
            break;
        }
        total += read;
    }
    Ok(total)
}

struct EventSink {
    stdout: bool,
    file: Option<File>,
}

impl EventSink {
    fn new(event_log: Option<&Path>, stdout: bool) -> Result<Self, String> {
        let file = match event_log {
            Some(path) => Some(
                OpenOptions::new()
                    .create(true)
                    .append(true)
                    .open(path)
                    .map_err(|error| format!("failed to open event log: {error}"))?,
            ),
            None => None,
        };
        Ok(Self { stdout, file })
    }

    fn progress(
        &mut self,
        operation: Operation,
        current_bytes: u64,
        total_bytes: u64,
    ) -> Result<(), String> {
        self.emit(&format!(
            "{{\"type\":\"progress\",\"operation\":\"{}\",\"currentBytes\":{},\"totalBytes\":{}}}",
            operation.as_str(),
            current_bytes,
            total_bytes
        ))
    }

    fn complete(&mut self, success: bool) -> Result<(), String> {
        self.emit(&format!("{{\"type\":\"complete\",\"success\":{success}}}"))
    }

    fn error(&mut self, message: &str) -> Result<(), String> {
        self.emit(&format!(
            "{{\"type\":\"error\",\"message\":\"{}\"}}",
            json_escape(message)
        ))
    }

    fn emit(&mut self, line: &str) -> Result<(), String> {
        if self.stdout {
            println!("{line}");
            io::stdout()
                .flush()
                .map_err(|error| format!("failed to flush stdout: {error}"))?;
        }
        if let Some(file) = &mut self.file {
            writeln!(file, "{line}")
                .map_err(|error| format!("failed to write event log: {error}"))?;
            file.flush()
                .map_err(|error| format!("failed to flush event log: {error}"))?;
        }
        Ok(())
    }
}

fn print_error(message: &str) {
    println!(
        "{{\"type\":\"error\",\"message\":\"{}\"}}",
        json_escape(message)
    );
}

fn json_escape(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for ch in value.chars() {
        match ch {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            '\u{08}' => escaped.push_str("\\b"),
            '\u{0C}' => escaped.push_str("\\f"),
            ch if ch <= '\u{1F}' => escaped.push_str(&format!("\\u{:04x}", ch as u32)),
            ch => escaped.push(ch),
        }
    }
    escaped
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn writes_and_verifies_raw_image() {
        let temp = TempDirectory::new("writes_and_verifies_raw_image");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        let image = image_bytes(4096);
        fs::write(&source, &image).unwrap();
        fs::write(&target, vec![0u8; 8192]).unwrap();

        let write_request = Request {
            operation: Operation::Write,
            source: source.clone(),
            target: target.clone(),
            total_bytes: image.len() as u64,
            event_log: None,
            stdout: true,
        };
        validate_request(&write_request).unwrap();
        let mut sink = EventSink::new(None, true).unwrap();
        write_image(&write_request, &mut sink).unwrap();
        assert_eq!(&fs::read(&target).unwrap()[..image.len()], image.as_slice());

        let verify_request = Request {
            operation: Operation::Verify,
            source,
            target,
            total_bytes: image.len() as u64,
            event_log: None,
            stdout: true,
        };
        assert!(verify_image(&verify_request, &mut sink).unwrap());
    }

    #[test]
    fn rejects_mismatched_target() {
        let temp = TempDirectory::new("rejects_mismatched_target");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        fs::write(&source, [1u8, 2, 3, 4]).unwrap();
        fs::write(&target, [1u8, 2, 3, 9]).unwrap();

        let request = Request {
            operation: Operation::Verify,
            source,
            target,
            total_bytes: 4,
            event_log: None,
            stdout: true,
        };
        let mut sink = EventSink::new(None, true).unwrap();
        assert!(!verify_image(&request, &mut sink).unwrap());
    }

    #[test]
    fn rejects_source_size_mismatch() {
        let temp = TempDirectory::new("rejects_source_size_mismatch");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        fs::write(&source, [1u8, 2, 3, 4]).unwrap();
        fs::write(&target, [0u8; 8]).unwrap();

        let request = Request {
            operation: Operation::Write,
            source,
            target,
            total_bytes: 3,
            event_log: None,
            stdout: true,
        };
        assert!(
            validate_request(&request)
                .unwrap_err()
                .contains("source size mismatch")
        );
    }

    #[test]
    fn rejects_source_target_self_write() {
        let temp = TempDirectory::new("rejects_source_target_self_write");
        let source = temp.path().join("source.raw");
        fs::write(&source, [1u8, 2, 3, 4]).unwrap();

        let request = Request {
            operation: Operation::Write,
            source: source.clone(),
            target: source,
            total_bytes: 4,
            event_log: None,
            stdout: true,
        };
        assert!(
            validate_request(&request)
                .unwrap_err()
                .contains("same path")
        );
    }

    #[test]
    fn writes_events_to_event_log_only() {
        let temp = TempDirectory::new("writes_events_to_event_log_only");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        let event_log = temp.path().join("events.ndjson");
        fs::write(&source, [1u8, 2, 3, 4]).unwrap();
        fs::write(&target, [0u8; 8]).unwrap();

        let request = Request {
            operation: Operation::Write,
            source,
            target,
            total_bytes: 4,
            event_log: Some(event_log.clone()),
            stdout: false,
        };
        let mut sink = EventSink::new(request.event_log.as_deref(), request.stdout).unwrap();
        write_image(&request, &mut sink).unwrap();
        sink.complete(true).unwrap();

        let events = fs::read_to_string(event_log).unwrap();
        assert!(events.contains("\"type\":\"progress\""));
        assert!(events.contains("\"type\":\"complete\""));
    }

    fn image_bytes(size: usize) -> Vec<u8> {
        let mut bytes = vec![0u8; size];
        for (index, byte) in bytes.iter_mut().enumerate() {
            *byte = index as u8;
        }
        bytes
    }

    struct TempDirectory {
        path: PathBuf,
    }

    impl TempDirectory {
        fn new(name: &str) -> Self {
            let nanos = SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let path = env::temp_dir().join(format!(
                "ruyi-imager-dd-flasher-{name}-{}-{nanos}",
                std::process::id()
            ));
            fs::create_dir_all(&path).unwrap();
            Self { path }
        }

        fn path(&self) -> &Path {
            &self.path
        }
    }

    impl Drop for TempDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.path);
        }
    }
}
