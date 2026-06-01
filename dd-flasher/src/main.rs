// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

use std::env;
use std::fs::{self, File, OpenOptions};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::process::ExitCode;

/// Size of each read/write buffer used for streaming image data.
const BUFFER_SIZE: usize = 1024 * 1024;

/// Operation requested by the Java SDK process adapter.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum Operation {
    /// Copy bytes from the source image to the target device or file.
    Write,

    /// Compare target bytes with the source image without modifying the target.
    Verify,

    /// Copy bytes and then compare them before releasing the target handle.
    WriteVerify,
}

impl Operation {
    /// Returns the stable wire-format operation name.
    fn as_str(self) -> &'static str {
        match self {
            Operation::Write => "write",
            Operation::Verify => "verify",
            Operation::WriteVerify => "write-verify",
        }
    }
}

/// Parsed dd-flasher command request.
#[derive(Debug)]
struct Request {
    /// Operation to execute.
    operation: Operation,

    /// Source image path.
    source: PathBuf,

    /// Target raw device or file path.
    target: PathBuf,

    /// Human-readable target display name supplied by the caller.
    target_display_name: String,

    /// Exact number of bytes expected in the source image.
    total_bytes: u64,

    /// Whether the target was identified as removable by the caller.
    removable: bool,

    /// Optional NDJSON event log used when stdout is not available.
    event_log: Option<PathBuf>,

    /// Optional file whose existence requests cancellation.
    cancel_file: Option<PathBuf>,

    /// Whether NDJSON events should be written to stdout.
    stdout: bool,
}

/// Parses CLI arguments, runs the requested operation, and emits a final NDJSON event.
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

/// Validates and dispatches one request.
fn run_request(request: &Request, sink: &mut EventSink) -> Result<bool, String> {
    validate_request(request)?;
    match request.operation {
        Operation::Write => {
            write_image(request, sink)?;
            Ok(true)
        }
        Operation::Verify => verify_image(request, sink),
        Operation::WriteVerify => write_and_verify_image(request, sink),
    }
}

/// Parses command-line arguments into a request.
fn parse_args<I>(args: I) -> Result<Request, String>
where
    I: IntoIterator<Item = String>,
{
    let mut args = args.into_iter();
    let operation = match args.next().as_deref() {
        Some("write") => Operation::Write,
        Some("verify") => Operation::Verify,
        Some("write-verify") => Operation::WriteVerify,
        Some(other) => return Err(format!("unsupported operation: {other}")),
        None => return Err("missing operation".to_string()),
    };

    let mut source = None;
    let mut target = None;
    let mut target_display_name = None;
    let mut total_bytes = None;
    let mut removable = None;
    let mut event_log = None;
    let mut cancel_file = None;
    let mut stdout = true;
    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--source" => source = Some(PathBuf::from(next_value(&mut args, "--source")?)),
            "--target" => target = Some(PathBuf::from(next_value(&mut args, "--target")?)),
            "--target-display-name" => {
                target_display_name = Some(parse_target_display_name(next_value(
                    &mut args,
                    "--target-display-name",
                )?)?)
            }
            "--event-log" => event_log = Some(PathBuf::from(next_value(&mut args, "--event-log")?)),
            "--cancel-file" => {
                cancel_file = Some(PathBuf::from(next_value(&mut args, "--cancel-file")?))
            }
            "--no-stdout" => stdout = false,
            "--total-bytes" => {
                let value = next_value(&mut args, "--total-bytes")?;
                total_bytes = Some(
                    value
                        .parse::<u64>()
                        .map_err(|_| format!("invalid --total-bytes value: {value}"))?,
                );
            }
            "--removable" => {
                let value = next_value(&mut args, "--removable")?;
                removable = Some(parse_bool(&value, "--removable")?);
            }
            "--help" | "-h" => return Err(usage()),
            other => return Err(format!("unknown argument: {other}")),
        }
    }

    Ok(Request {
        operation,
        source: source.ok_or_else(|| "missing --source".to_string())?,
        target: target.ok_or_else(|| "missing --target".to_string())?,
        target_display_name: target_display_name
            .ok_or_else(|| "missing --target-display-name".to_string())?,
        total_bytes: total_bytes.ok_or_else(|| "missing --total-bytes".to_string())?,
        removable: removable.ok_or_else(|| "missing --removable".to_string())?,
        event_log,
        cancel_file,
        stdout,
    })
}

/// Reads the next option value.
fn next_value<I>(args: &mut I, name: &str) -> Result<String, String>
where
    I: Iterator<Item = String>,
{
    args.next()
        .ok_or_else(|| format!("missing value for {name}"))
}

/// Parses and validates a target display name option value.
fn parse_target_display_name(value: String) -> Result<String, String> {
    validate_target_display_name(&value)?;
    Ok(value.trim().to_string())
}

/// Validates a target display name.
fn validate_target_display_name(value: &str) -> Result<(), String> {
    if value.trim().is_empty() {
        return Err("invalid --target-display-name value: must not be empty".to_string());
    }
    if value.chars().any(char::is_control) {
        return Err(
            "invalid --target-display-name value: must not contain control characters".to_string(),
        );
    }
    Ok(())
}

/// Parses one boolean option value.
fn parse_bool(value: &str, name: &str) -> Result<bool, String> {
    match value {
        "true" => Ok(true),
        "false" => Ok(false),
        _ => Err(format!("invalid {name} value: {value}")),
    }
}

/// Returns the CLI usage text.
fn usage() -> String {
    "usage: dd-flasher <write|verify|write-verify> --source <path> --target <path> --target-display-name <name> --total-bytes <bytes> --removable <true|false> [--event-log <path>] [--cancel-file <path>] [--no-stdout]".to_string()
}

/// Validates source metadata, event sink configuration, and self-write safety.
fn validate_request(request: &Request) -> Result<(), String> {
    validate_target_display_name(&request.target_display_name)?;
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
    if !request.removable {
        return Err(format!(
            "target is not removable: {}",
            request.target_display_name
        ));
    }
    if !request.stdout && request.event_log.is_none() {
        return Err("--no-stdout requires --event-log".to_string());
    }
    if same_path(&request.source, &request.target) {
        return Err("source and target refer to the same path".to_string());
    }
    Ok(())
}

/// Returns whether two paths resolve to the same filesystem object.
fn same_path(left: &Path, right: &Path) -> bool {
    match (fs::canonicalize(left), fs::canonicalize(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => false,
    }
}

/// Streams source image bytes to the target and fsyncs before returning.
fn write_image(request: &Request, sink: &mut EventSink) -> Result<(), String> {
    let mut source =
        File::open(&request.source).map_err(|error| format!("failed to open source: {error}"))?;
    let _target_locks = lock_target_for_write(&request.target).map_err(|error| {
        format!(
            "failed to lock target volumes ({}): {error}",
            request.target_display_name
        )
    })?;
    let mut target = open_target_for_write(&request.target).map_err(|error| {
        format!(
            "failed to open target for writing ({}): {error}",
            request.target_display_name
        )
    })?;

    write_image_stream(request, &mut source, &mut target, sink)
}

/// Streams source image bytes to the target, verifies them, and releases the target afterward.
fn write_and_verify_image(request: &Request, sink: &mut EventSink) -> Result<bool, String> {
    let mut source =
        File::open(&request.source).map_err(|error| format!("failed to open source: {error}"))?;
    let _target_locks = lock_target_for_write(&request.target).map_err(|error| {
        format!(
            "failed to lock target volumes ({}): {error}",
            request.target_display_name
        )
    })?;
    let mut target = open_target_for_write_verify(&request.target).map_err(|error| {
        format!(
            "failed to open target for writing ({}): {error}",
            request.target_display_name
        )
    })?;

    write_image_stream(request, &mut source, &mut target, sink)?;
    source
        .seek(SeekFrom::Start(0))
        .map_err(|error| format!("failed to seek source: {error}"))?;
    target.seek(SeekFrom::Start(0)).map_err(|error| {
        format!(
            "failed to seek target ({}): {error}",
            request.target_display_name
        )
    })?;
    sink.progress(Operation::Verify, 0, request.total_bytes)?;
    verify_image_stream(request, &mut source, &mut target, sink)
}

/// Streams source image bytes to an already opened target and fsyncs before returning.
fn write_image_stream(
    request: &Request,
    source: &mut File,
    target: &mut File,
    sink: &mut EventSink,
) -> Result<(), String> {
    let mut buffer = vec![0u8; BUFFER_SIZE];
    let mut written = 0u64;
    while written < request.total_bytes {
        check_cancelled(request)?;
        let remaining = request.total_bytes - written;
        let chunk_size = if remaining < BUFFER_SIZE as u64 {
            remaining as usize
        } else {
            BUFFER_SIZE
        };
        let read = source
            .read(&mut buffer[..chunk_size])
            .map_err(|error| format!("failed to read source: {error}"))?;
        if read == 0 {
            break;
        }
        target.write_all(&buffer[..read]).map_err(|error| {
            format!(
                "failed to write target ({}): {error}",
                request.target_display_name
            )
        })?;
        written += read as u64;
        sink.progress(Operation::Write, written, request.total_bytes)?;
    }

    if written != request.total_bytes {
        return Err(format!(
            "write length mismatch: expected {}, actual {}",
            request.total_bytes, written
        ));
    }
    let mut extra = [0u8; 1];
    let extra_read = source
        .read(&mut extra)
        .map_err(|error| format!("failed to read source: {error}"))?;
    if extra_read != 0 {
        return Err(format!(
            "source size changed during write: expected {} bytes",
            request.total_bytes
        ));
    }
    target.sync_all().map_err(|error| {
        format!(
            "failed to flush target ({}): {error}",
            request.target_display_name
        )
    })?;
    Ok(())
}

/// Compares target bytes with the source image and reports progress snapshots.
fn verify_image(request: &Request, sink: &mut EventSink) -> Result<bool, String> {
    let mut source =
        File::open(&request.source).map_err(|error| format!("failed to open source: {error}"))?;
    let mut target = open_target_for_verify(&request.target).map_err(|error| {
        format!(
            "failed to open target for reading ({}): {error}",
            request.target_display_name
        )
    })?;

    verify_image_stream(request, &mut source, &mut target, sink)
}

/// Opens a target handle for a write request.
fn open_target_for_write(target: &Path) -> io::Result<File> {
    platform::open_target_for_write(target)
}

/// Opens a read/write target handle for combined write and verification.
fn open_target_for_write_verify(target: &Path) -> io::Result<File> {
    platform::open_target_for_write_verify(target)
}

/// Opens a read-only target handle for verification.
fn open_target_for_verify(target: &Path) -> io::Result<File> {
    platform::open_target_for_verify(target)
}

/// Locks mounted target volumes that can block direct disk writes.
fn lock_target_for_write(target: &Path) -> io::Result<TargetVolumeLocks> {
    platform::lock_target_for_write(target)
}

/// Platform-specific target volume locks kept alive during destructive writes.
struct TargetVolumeLocks {
    /// Native lock handles released when the write operation finishes.
    #[cfg(windows)]
    _locks: Vec<platform::VolumeLock>,
}

#[cfg(windows)]
mod platform {
    use super::*;
    use std::ffi::c_void;
    use std::mem;
    use std::os::windows::io::{AsRawHandle, FromRawHandle, RawHandle};
    use std::ptr;

    /// Windows `BOOL`.
    type Bool = i32;

    /// Windows `DWORD`.
    type Dword = u32;

    /// Windows raw handle pointer.
    type Handle = *mut c_void;

    /// Invalid Win32 handle value.
    const INVALID_HANDLE_VALUE: Handle = -1isize as Handle;

    /// No more entries exist in an enumeration.
    const ERROR_NO_MORE_FILES: i32 = 18;

    /// The output buffer was too small for every disk extent.
    const ERROR_MORE_DATA: i32 = 234;

    /// Opens an existing file or device.
    const OPEN_EXISTING: Dword = 3;

    /// Generic read access.
    const GENERIC_READ: Dword = 0x80000000;

    /// Generic write access.
    const GENERIC_WRITE: Dword = 0x40000000;

    /// Shared read access.
    const FILE_SHARE_READ: Dword = 0x00000001;

    /// Shared write access.
    const FILE_SHARE_WRITE: Dword = 0x00000002;

    /// Writes through intermediate caches when possible.
    const FILE_FLAG_WRITE_THROUGH: Dword = 0x80000000;

    /// Hints that verification reads are sequential.
    const FILE_FLAG_SEQUENTIAL_SCAN: Dword = 0x08000000;

    /// Returns the disk extents backing a volume.
    const IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS: Dword = 0x00560000;

    /// Locks a volume for exclusive access through the lock handle.
    const FSCTL_LOCK_VOLUME: Dword = 0x00090018;

    /// Dismounts a volume.
    const FSCTL_DISMOUNT_VOLUME: Dword = 0x00090020;

    /// Unlocks a previously locked volume.
    const FSCTL_UNLOCK_VOLUME: Dword = 0x0009001c;

    unsafe extern "system" {
        fn CreateFileW(
            lp_file_name: *const u16,
            dw_desired_access: Dword,
            dw_share_mode: Dword,
            lp_security_attributes: *mut c_void,
            dw_creation_disposition: Dword,
            dw_flags_and_attributes: Dword,
            h_template_file: Handle,
        ) -> Handle;

        fn DeviceIoControl(
            h_device: Handle,
            dw_io_control_code: Dword,
            lp_in_buffer: *mut c_void,
            n_in_buffer_size: Dword,
            lp_out_buffer: *mut c_void,
            n_out_buffer_size: Dword,
            lp_bytes_returned: *mut Dword,
            lp_overlapped: *mut c_void,
        ) -> Bool;

        fn FindFirstVolumeW(lpsz_volume_name: *mut u16, cch_buffer_length: Dword) -> Handle;

        fn FindNextVolumeW(
            h_find_volume: Handle,
            lpsz_volume_name: *mut u16,
            cch_buffer_length: Dword,
        ) -> Bool;

        fn FindVolumeClose(h_find_volume: Handle) -> Bool;
    }

    /// One disk extent returned by `IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS`.
    #[repr(C)]
    #[derive(Clone, Copy)]
    struct DiskExtent {
        /// Physical disk number.
        disk_number: Dword,

        /// Byte offset at which the extent begins.
        _starting_offset: i64,

        /// Extent length in bytes.
        _extent_length: i64,
    }

    /// Volume handle kept open to preserve a successful `FSCTL_LOCK_VOLUME`.
    pub struct VolumeLock {
        /// Locked volume path, used only to retain useful debugger state.
        _path: String,

        /// Native volume handle. Closing this handle releases the lock.
        _handle: File,
    }

    /// Opens a target for a write request.
    pub fn open_target_for_write(target: &Path) -> io::Result<File> {
        if let Some(raw_target) = normalized_raw_physical_drive_path(target) {
            open_existing(
                &raw_target,
                GENERIC_READ | GENERIC_WRITE,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                FILE_FLAG_WRITE_THROUGH,
            )
        } else {
            OpenOptions::new().write(true).open(target)
        }
    }

    /// Opens a target for combined writing and verification.
    pub fn open_target_for_write_verify(target: &Path) -> io::Result<File> {
        if let Some(raw_target) = normalized_raw_physical_drive_path(target) {
            open_existing(
                &raw_target,
                GENERIC_READ | GENERIC_WRITE,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                FILE_FLAG_WRITE_THROUGH,
            )
        } else {
            OpenOptions::new().read(true).write(true).open(target)
        }
    }

    /// Opens a target for verification.
    pub fn open_target_for_verify(target: &Path) -> io::Result<File> {
        if let Some(raw_target) = normalized_raw_physical_drive_path(target) {
            open_existing(
                &raw_target,
                GENERIC_READ,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                FILE_FLAG_SEQUENTIAL_SCAN,
            )
        } else {
            File::open(target)
        }
    }

    /// Locks and dismounts all volumes backed by a raw physical drive target.
    pub fn lock_target_for_write(target: &Path) -> io::Result<TargetVolumeLocks> {
        let target_text = target.to_string_lossy();
        let Some(disk_number) = raw_physical_drive_number(target_text.as_ref()) else {
            return Ok(TargetVolumeLocks { _locks: Vec::new() });
        };

        let mut locks = Vec::new();
        for volume in enumerate_volumes()? {
            if volume_is_on_disk(&volume, disk_number) {
                locks.push(lock_and_dismount_volume(volume)?);
            }
        }

        Ok(TargetVolumeLocks { _locks: locks })
    }

    /// Returns the canonical raw physical drive path.
    fn normalized_raw_physical_drive_path(target: &Path) -> Option<String> {
        let target_text = target.to_string_lossy();
        raw_physical_drive_number(target_text.as_ref())
            .map(|number| format!(r"\\.\PHYSICALDRIVE{number}"))
    }

    /// Returns whether a volume has an extent on a disk.
    fn volume_is_on_disk(volume: &str, disk_number: u32) -> bool {
        let normalized = normalize_volume_path(volume);
        let handle = match open_existing(&normalized, 0, FILE_SHARE_READ | FILE_SHARE_WRITE, 0) {
            Ok(handle) => handle,
            Err(_) => return false,
        };
        let disk_numbers = match volume_disk_numbers(&handle) {
            Ok(disk_numbers) => disk_numbers,
            Err(_) => return false,
        };
        disk_numbers.contains(&disk_number)
    }

    /// Locks and dismounts a volume, returning a handle that keeps the lock alive.
    fn lock_and_dismount_volume(volume: String) -> io::Result<VolumeLock> {
        let normalized = normalize_volume_path(&volume);
        let handle = open_existing(
            &normalized,
            GENERIC_READ | GENERIC_WRITE,
            FILE_SHARE_READ | FILE_SHARE_WRITE,
            0,
        )?;

        device_io_control(&handle, FSCTL_LOCK_VOLUME)?;
        if let Err(error) = device_io_control(&handle, FSCTL_DISMOUNT_VOLUME) {
            let _ = device_io_control(&handle, FSCTL_UNLOCK_VOLUME);
            return Err(error);
        }

        Ok(VolumeLock {
            _path: volume,
            _handle: handle,
        })
    }

    /// Enumerates known Windows volume GUID paths.
    fn enumerate_volumes() -> io::Result<Vec<String>> {
        const VOLUME_BUFFER_LEN: usize = 1024;

        let mut buffer = vec![0u16; VOLUME_BUFFER_LEN];
        let handle = unsafe { FindFirstVolumeW(buffer.as_mut_ptr(), buffer.len() as Dword) };
        if handle == INVALID_HANDLE_VALUE {
            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(ERROR_NO_MORE_FILES) {
                return Ok(Vec::new());
            }
            return Err(error);
        }

        let finder = FindVolumeHandle(handle);
        let mut volumes = vec![wide_buffer_to_string(&buffer)];
        loop {
            let found =
                unsafe { FindNextVolumeW(finder.0, buffer.as_mut_ptr(), buffer.len() as Dword) };
            if found != 0 {
                volumes.push(wide_buffer_to_string(&buffer));
                continue;
            }

            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(ERROR_NO_MORE_FILES) {
                break;
            }
            return Err(error);
        }

        Ok(volumes)
    }

    /// Finds the disk numbers backing a volume handle.
    fn volume_disk_numbers(volume: &File) -> io::Result<Vec<u32>> {
        let mut buffer_size = aligned_disk_extents_offset() + mem::size_of::<DiskExtent>() * 16;
        loop {
            let mut buffer = vec![0u8; buffer_size];
            let mut bytes_returned = 0;
            let ok = unsafe {
                DeviceIoControl(
                    volume.as_raw_handle() as Handle,
                    IOCTL_VOLUME_GET_VOLUME_DISK_EXTENTS,
                    ptr::null_mut(),
                    0,
                    buffer.as_mut_ptr() as *mut c_void,
                    buffer.len() as Dword,
                    &mut bytes_returned,
                    ptr::null_mut(),
                )
            };
            if ok != 0 {
                return parse_volume_disk_extents(&buffer[..bytes_returned as usize]);
            }

            let error = io::Error::last_os_error();
            if error.raw_os_error() == Some(ERROR_MORE_DATA) {
                buffer_size *= 2;
                continue;
            }
            return Err(error);
        }
    }

    /// Parses `VOLUME_DISK_EXTENTS` output bytes.
    fn parse_volume_disk_extents(buffer: &[u8]) -> io::Result<Vec<u32>> {
        if buffer.len() < aligned_disk_extents_offset() {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "volume disk extents response is too short",
            ));
        }

        let extent_count = u32::from_ne_bytes(buffer[..4].try_into().unwrap()) as usize;
        let extents_offset = aligned_disk_extents_offset();
        let extents_size = mem::size_of::<DiskExtent>() * extent_count;
        if buffer.len() < extents_offset + extents_size {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                "volume disk extents response is truncated",
            ));
        }

        let mut disk_numbers = Vec::with_capacity(extent_count);
        for index in 0..extent_count {
            let extent_offset = extents_offset + mem::size_of::<DiskExtent>() * index;
            let extent = unsafe {
                (buffer.as_ptr().add(extent_offset) as *const DiskExtent).read_unaligned()
            };
            disk_numbers.push(extent.disk_number);
        }
        Ok(disk_numbers)
    }

    /// Returns the aligned byte offset of `VOLUME_DISK_EXTENTS.Extents`.
    fn aligned_disk_extents_offset() -> usize {
        let alignment = mem::align_of::<DiskExtent>();
        (mem::size_of::<Dword>() + alignment - 1) & !(alignment - 1)
    }

    /// Sends a no-buffer `DeviceIoControl` request.
    fn device_io_control(handle: &File, control_code: Dword) -> io::Result<()> {
        let mut bytes_returned = 0;
        let ok = unsafe {
            DeviceIoControl(
                handle.as_raw_handle() as Handle,
                control_code,
                ptr::null_mut(),
                0,
                ptr::null_mut(),
                0,
                &mut bytes_returned,
                ptr::null_mut(),
            )
        };
        if ok == 0 {
            Err(io::Error::last_os_error())
        } else {
            Ok(())
        }
    }

    /// Opens an existing Windows file or device path.
    fn open_existing(
        path: &str,
        desired_access: Dword,
        share_mode: Dword,
        flags: Dword,
    ) -> io::Result<File> {
        let path = wide_null(path);
        let handle = unsafe {
            CreateFileW(
                path.as_ptr(),
                desired_access,
                share_mode,
                ptr::null_mut(),
                OPEN_EXISTING,
                flags,
                ptr::null_mut(),
            )
        };
        if handle == INVALID_HANDLE_VALUE {
            Err(io::Error::last_os_error())
        } else {
            Ok(unsafe { File::from_raw_handle(handle as RawHandle) })
        }
    }

    /// Converts a Rust string to a nul-terminated UTF-16 buffer.
    fn wide_null(value: &str) -> Vec<u16> {
        value.encode_utf16().chain(std::iter::once(0)).collect()
    }

    /// Converts a nul-terminated UTF-16 output buffer to a string.
    fn wide_buffer_to_string(buffer: &[u16]) -> String {
        let len = buffer
            .iter()
            .position(|ch| *ch == 0)
            .unwrap_or(buffer.len());
        String::from_utf16_lossy(&buffer[..len])
    }

    /// Converts a volume GUID path to the form accepted by `CreateFileW`.
    fn normalize_volume_path(volume: &str) -> String {
        let mut normalized = volume.trim().to_string();
        while normalized.ends_with('\\') && normalized.len() > 4 {
            normalized.pop();
        }
        normalized
    }

    /// Native find-volume handle closed on drop.
    struct FindVolumeHandle(Handle);

    impl Drop for FindVolumeHandle {
        fn drop(&mut self) {
            unsafe {
                FindVolumeClose(self.0);
            }
        }
    }
}

#[cfg(not(windows))]
mod platform {
    use super::*;

    /// Opens a target for a write request.
    pub fn open_target_for_write(target: &Path) -> io::Result<File> {
        OpenOptions::new().write(true).open(target)
    }

    /// Opens a target for combined writing and verification.
    pub fn open_target_for_write_verify(target: &Path) -> io::Result<File> {
        OpenOptions::new().read(true).write(true).open(target)
    }

    /// Opens a target for verification.
    pub fn open_target_for_verify(target: &Path) -> io::Result<File> {
        File::open(target)
    }

    /// Returns an empty target lock holder.
    pub fn lock_target_for_write(_target: &Path) -> io::Result<TargetVolumeLocks> {
        Ok(TargetVolumeLocks {})
    }
}

/// Parses a Windows raw physical drive path.
#[cfg(any(windows, test))]
fn raw_physical_drive_number(path: &str) -> Option<u32> {
    let path = path.strip_suffix('\\').unwrap_or(path);
    let prefix = r"\\.\PHYSICALDRIVE";
    if path.len() <= prefix.len() || !path[..prefix.len()].eq_ignore_ascii_case(prefix) {
        return None;
    }

    let disk_number = &path[prefix.len()..];
    if disk_number.is_empty() || !disk_number.bytes().all(|ch| ch.is_ascii_digit()) {
        return None;
    }
    disk_number.parse().ok()
}

/// Compares target bytes with an already opened source image and reports progress snapshots.
fn verify_image_stream(
    request: &Request,
    source: &mut File,
    target: &mut File,
    sink: &mut EventSink,
) -> Result<bool, String> {
    let mut source_buffer = vec![0u8; BUFFER_SIZE];
    let mut target_buffer = vec![0u8; BUFFER_SIZE];
    let mut verified = 0u64;
    while verified < request.total_bytes {
        check_cancelled(request)?;
        let chunk_size = BUFFER_SIZE.min((request.total_bytes - verified) as usize);
        let source_read = read_exact_or_eof(&mut *source, &mut source_buffer[..chunk_size])
            .map_err(|error| format!("failed to read source: {error}"))?;
        let target_read = read_exact_or_eof(&mut *target, &mut target_buffer[..chunk_size])
            .map_err(|error| {
                format!(
                    "failed to read target ({}): {error}",
                    request.target_display_name
                )
            })?;
        if source_read != target_read || source_read != chunk_size {
            return Ok(false);
        }
        if source_buffer[..chunk_size] != target_buffer[..chunk_size] {
            return Ok(false);
        }
        verified += chunk_size as u64;
        sink.progress(Operation::Verify, verified, request.total_bytes)?;
    }
    Ok(true)
}

/// Returns an error when the caller has requested cancellation.
fn check_cancelled(request: &Request) -> Result<(), String> {
    if request
        .cancel_file
        .as_deref()
        .is_some_and(|path| path.exists())
    {
        return Err("operation cancelled".to_string());
    }
    Ok(())
}

/// Fills a buffer unless EOF is reached first.
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

/// Emits dd-flasher events to stdout, an event log file, or both.
struct EventSink {
    /// Whether events should be emitted to stdout.
    stdout: bool,

    /// Optional append-only event log file.
    file: Option<File>,
}

impl EventSink {
    /// Creates an event sink.
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

    /// Emits a progress event for the current byte position.
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

    /// Emits the final completion event.
    fn complete(&mut self, success: bool) -> Result<(), String> {
        self.emit(&format!("{{\"type\":\"complete\",\"success\":{success}}}"))
    }

    /// Emits an error event.
    fn error(&mut self, message: &str) -> Result<(), String> {
        self.emit(&format!(
            "{{\"type\":\"error\",\"message\":\"{}\"}}",
            json_escape(message)
        ))
    }

    /// Writes one NDJSON event line to every configured destination.
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

/// Prints an error event to stdout before an event sink exists.
fn print_error(message: &str) {
    println!(
        "{{\"type\":\"error\",\"message\":\"{}\"}}",
        json_escape(message)
    );
}

/// Escapes a string for the small JSON event subset emitted by this helper.
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

    /// Verifies the helper wire contract carries target removability.
    #[test]
    fn parses_removable_argument() {
        let request = parse_args([
            "write".to_string(),
            "--source".to_string(),
            "source.raw".to_string(),
            "--target".to_string(),
            "target.raw".to_string(),
            "--target-display-name".to_string(),
            "Test Target".to_string(),
            "--total-bytes".to_string(),
            "4".to_string(),
            "--removable".to_string(),
            "false".to_string(),
        ])
        .unwrap();

        assert_eq!(request.operation, Operation::Write);
        assert_eq!(request.target_display_name, "Test Target");
        assert!(!request.removable);
    }

    /// Verifies callers must explicitly provide a target display name.
    #[test]
    fn rejects_missing_target_display_name_argument() {
        let error = parse_args([
            "write".to_string(),
            "--source".to_string(),
            "source.raw".to_string(),
            "--target".to_string(),
            "target.raw".to_string(),
            "--total-bytes".to_string(),
            "4".to_string(),
            "--removable".to_string(),
            "true".to_string(),
        ])
        .unwrap_err();

        assert!(error.contains("missing --target-display-name"));
    }

    /// Verifies callers must provide a non-empty target display name.
    #[test]
    fn rejects_blank_target_display_name_argument() {
        let error = parse_args([
            "write".to_string(),
            "--source".to_string(),
            "source.raw".to_string(),
            "--target".to_string(),
            "target.raw".to_string(),
            "--target-display-name".to_string(),
            "   ".to_string(),
            "--total-bytes".to_string(),
            "4".to_string(),
            "--removable".to_string(),
            "true".to_string(),
        ])
        .unwrap_err();

        assert!(error.contains("must not be empty"));
    }

    /// Verifies Windows raw physical drive paths are recognized without treating similar paths as devices.
    #[test]
    fn parses_windows_raw_physical_drive_number() {
        assert_eq!(raw_physical_drive_number(r"\\.\PHYSICALDRIVE0"), Some(0));
        assert_eq!(raw_physical_drive_number(r"\\.\PhysicalDrive12"), Some(12));
        assert_eq!(raw_physical_drive_number(r"\\.\PHYSICALDRIVE3\"), Some(3));
        assert_eq!(raw_physical_drive_number(r"\\.\PHYSICALDRIVE"), None);
        assert_eq!(raw_physical_drive_number(r"\\.\PHYSICALDRIVE3\foo"), None);
        assert_eq!(raw_physical_drive_number(r"C:\target.raw"), None);
    }

    /// Verifies callers must explicitly provide target removability.
    #[test]
    fn rejects_missing_removable_argument() {
        let error = parse_args([
            "write".to_string(),
            "--source".to_string(),
            "source.raw".to_string(),
            "--target".to_string(),
            "target.raw".to_string(),
            "--target-display-name".to_string(),
            "Test Target".to_string(),
            "--total-bytes".to_string(),
            "4".to_string(),
        ])
        .unwrap_err();

        assert!(error.contains("missing --removable"));
    }

    /// Verifies the helper wire contract accepts a cancellation signal file.
    #[test]
    fn parses_cancel_file_argument() {
        let request = parse_args([
            "write".to_string(),
            "--source".to_string(),
            "source.raw".to_string(),
            "--target".to_string(),
            "target.raw".to_string(),
            "--target-display-name".to_string(),
            "Test Target".to_string(),
            "--total-bytes".to_string(),
            "4".to_string(),
            "--removable".to_string(),
            "true".to_string(),
            "--cancel-file".to_string(),
            "cancel.signal".to_string(),
        ])
        .unwrap();

        assert_eq!(request.cancel_file, Some(PathBuf::from("cancel.signal")));
    }

    /// Verifies a successful write and verification cycle against temporary files.
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
            target_display_name: "Test Target".to_string(),
            total_bytes: image.len() as u64,
            removable: true,
            event_log: None,
            cancel_file: None,
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
            target_display_name: "Test Target".to_string(),
            total_bytes: image.len() as u64,
            removable: true,
            event_log: None,
            cancel_file: None,
            stdout: true,
        };
        assert!(verify_image(&verify_request, &mut sink).unwrap());
    }

    /// Verifies a combined write and verification cycle against temporary files.
    #[test]
    fn writes_and_verifies_raw_image_in_one_request() {
        let temp = TempDirectory::new("writes_and_verifies_raw_image_in_one_request");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        let event_log = temp.path().join("events.ndjson");
        let image = image_bytes(4096);
        fs::write(&source, &image).unwrap();
        fs::write(&target, vec![0u8; 8192]).unwrap();

        let request = Request {
            operation: Operation::WriteVerify,
            source,
            target: target.clone(),
            target_display_name: "Test Target".to_string(),
            total_bytes: image.len() as u64,
            removable: true,
            event_log: Some(event_log.clone()),
            cancel_file: None,
            stdout: false,
        };
        validate_request(&request).unwrap();
        let mut sink = EventSink::new(request.event_log.as_deref(), request.stdout).unwrap();
        assert!(write_and_verify_image(&request, &mut sink).unwrap());

        assert_eq!(&fs::read(&target).unwrap()[..image.len()], image.as_slice());
        let events = fs::read_to_string(event_log).unwrap();
        assert!(events.contains("\"operation\":\"write\""));
        assert!(events.contains("\"operation\":\"verify\""));
        assert!(!events.contains("\"operation\":\"write-verify\""));
    }

    /// Verifies byte mismatches are reported as verification failures.
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
            target_display_name: "Test Target".to_string(),
            total_bytes: 4,
            removable: true,
            event_log: None,
            cancel_file: None,
            stdout: true,
        };
        let mut sink = EventSink::new(None, true).unwrap();
        assert!(!verify_image(&request, &mut sink).unwrap());
    }

    /// Verifies source size mismatches are rejected before writing.
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
            target_display_name: "Test Target".to_string(),
            total_bytes: 3,
            removable: true,
            event_log: None,
            cancel_file: None,
            stdout: true,
        };
        assert!(
            validate_request(&request)
                .unwrap_err()
                .contains("source size mismatch")
        );
    }

    /// Verifies source growth after validation does not write extra bytes to the target.
    #[test]
    fn rejects_source_growth_without_writing_extra_bytes() {
        let temp = TempDirectory::new("rejects_source_growth_without_writing_extra_bytes");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        let image = [1u8, 2, 3, 4];
        fs::write(&source, image).unwrap();
        fs::write(&target, [0u8; 8]).unwrap();

        let request = Request {
            operation: Operation::Write,
            source: source.clone(),
            target: target.clone(),
            target_display_name: "Test Target".to_string(),
            total_bytes: image.len() as u64,
            removable: true,
            event_log: None,
            cancel_file: None,
            stdout: true,
        };
        validate_request(&request).unwrap();
        fs::write(&source, [1u8, 2, 3, 4, 5, 6]).unwrap();

        let mut sink = EventSink::new(None, true).unwrap();
        assert!(
            write_image(&request, &mut sink)
                .unwrap_err()
                .contains("source size changed")
        );
        assert_eq!(fs::read(target).unwrap(), [1u8, 2, 3, 4, 0, 0, 0, 0]);
    }

    /// Verifies an existing cancellation signal stops writes before data is copied.
    #[test]
    fn cancels_write_when_signal_exists() {
        let temp = TempDirectory::new("cancels_write_when_signal_exists");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        let cancel_file = temp.path().join("cancel.signal");
        fs::write(&source, [1u8, 2, 3, 4]).unwrap();
        fs::write(&target, [0u8; 8]).unwrap();
        fs::write(&cancel_file, "cancel").unwrap();

        let request = Request {
            operation: Operation::Write,
            source,
            target: target.clone(),
            target_display_name: "Test Target".to_string(),
            total_bytes: 4,
            removable: true,
            event_log: None,
            cancel_file: Some(cancel_file),
            stdout: true,
        };
        validate_request(&request).unwrap();

        let mut sink = EventSink::new(None, true).unwrap();
        assert!(
            write_image(&request, &mut sink)
                .unwrap_err()
                .contains("operation cancelled")
        );
        assert_eq!(fs::read(target).unwrap(), [0u8; 8]);
    }

    /// Verifies self-writes are rejected before opening the target for writing.
    #[test]
    fn rejects_source_target_self_write() {
        let temp = TempDirectory::new("rejects_source_target_self_write");
        let source = temp.path().join("source.raw");
        fs::write(&source, [1u8, 2, 3, 4]).unwrap();

        let request = Request {
            operation: Operation::Write,
            source: source.clone(),
            target: source,
            target_display_name: "Test Target".to_string(),
            total_bytes: 4,
            removable: true,
            event_log: None,
            cancel_file: None,
            stdout: true,
        };
        assert!(
            validate_request(&request)
                .unwrap_err()
                .contains("same path")
        );
    }

    /// Verifies non-removable targets are rejected before opening the target.
    #[test]
    fn rejects_non_removable_target() {
        let temp = TempDirectory::new("rejects_non_removable_target");
        let source = temp.path().join("source.raw");
        let target = temp.path().join("target.raw");
        fs::write(&source, [1u8, 2, 3, 4]).unwrap();
        fs::write(&target, [0u8; 8]).unwrap();

        let request = Request {
            operation: Operation::Write,
            source,
            target,
            target_display_name: "Test Target".to_string(),
            total_bytes: 4,
            removable: false,
            event_log: None,
            cancel_file: None,
            stdout: true,
        };
        let error = validate_request(&request).unwrap_err();
        assert!(error.contains("not removable"));
        assert!(error.contains("Test Target"));
    }

    /// Verifies elevated-mode event logging can run without stdout events.
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
            target_display_name: "Test Target".to_string(),
            total_bytes: 4,
            removable: true,
            event_log: Some(event_log.clone()),
            cancel_file: None,
            stdout: false,
        };
        let mut sink = EventSink::new(request.event_log.as_deref(), request.stdout).unwrap();
        write_image(&request, &mut sink).unwrap();
        sink.complete(true).unwrap();

        let events = fs::read_to_string(event_log).unwrap();
        assert!(events.contains("\"type\":\"progress\""));
        assert!(events.contains("\"type\":\"complete\""));
    }

    /// Creates deterministic image bytes for tests.
    fn image_bytes(size: usize) -> Vec<u8> {
        let mut bytes = vec![0u8; size];
        for (index, byte) in bytes.iter_mut().enumerate() {
            *byte = index as u8;
        }
        bytes
    }

    /// Temporary test directory removed when dropped.
    struct TempDirectory {
        /// Filesystem path to the temporary directory.
        path: PathBuf,
    }

    impl TempDirectory {
        /// Creates a temporary directory name unique to this process and timestamp.
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

        /// Returns the temporary directory path.
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
