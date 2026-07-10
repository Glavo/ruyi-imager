// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

use std::fs;
use std::path::Path;

/// Caller-supplied target properties that must still match after elevation.
pub(crate) struct TargetExpectation<'a> {
    /// Target path opened by the helper.
    pub(crate) path: &'a Path,

    /// Human-readable target name used in diagnostics.
    pub(crate) display_name: &'a str,

    /// Expected target capacity in bytes.
    pub(crate) size_bytes: u64,

    /// Whether the caller identified the target as removable.
    pub(crate) removable: bool,

    /// Whether the target is an explicitly supported regular-file fixture.
    pub(crate) file_backed: bool,

    /// Expected model when available.
    pub(crate) model: Option<&'a str>,

    /// Expected bus type when available.
    pub(crate) bus_type: Option<&'a str>,

    /// Expected stable hardware identity when available.
    pub(crate) hardware_id: Option<&'a str>,
}

/// Target properties observed by the elevated helper.
pub(crate) struct TargetObservation {
    /// Observed target capacity in bytes.
    pub(crate) size_bytes: u64,

    /// Whether the operating system reports removable media.
    pub(crate) removable: bool,

    /// Whether the target contains the running system.
    pub(crate) system: bool,

    /// Whether the operating system reports a read-only target.
    pub(crate) read_only: bool,

    /// Observed model when available.
    pub(crate) model: Option<String>,

    /// Observed bus type when available.
    pub(crate) bus_type: Option<String>,

    /// Observed stable hardware identity when available.
    pub(crate) hardware_id: Option<String>,
}

/// Validates that the target still matches the caller's selected device.
pub(crate) fn validate(expectation: TargetExpectation<'_>) -> Result<(), String> {
    if !expectation.removable {
        return Err(format!(
            "target is not removable: {}",
            expectation.display_name
        ));
    }
    if expectation.size_bytes == 0 {
        return Err(format!(
            "target size is unknown: {}",
            expectation.display_name
        ));
    }

    if expectation.file_backed {
        return validate_file_backed(expectation);
    }

    let observation = inspect_target(expectation.path).map_err(|error| {
        format!(
            "failed to inspect target ({}): {error}",
            expectation.display_name
        )
    })?;
    if observation.system {
        return Err(format!(
            "target is a system disk: {}",
            expectation.display_name
        ));
    }
    if !observation.removable {
        return Err(format!(
            "operating system reports a non-removable target: {}",
            expectation.display_name
        ));
    }
    if observation.read_only {
        return Err(format!("target is read-only: {}", expectation.display_name));
    }
    if observation.size_bytes != expectation.size_bytes {
        return Err(format!(
            "target size changed ({}): expected {}, actual {}",
            expectation.display_name, expectation.size_bytes, observation.size_bytes
        ));
    }
    if !known_text_matches(expectation.model, observation.model.as_deref()) {
        return Err(format!(
            "target model changed: {}",
            expectation.display_name
        ));
    }
    if !known_text_matches(expectation.bus_type, observation.bus_type.as_deref()) {
        return Err(format!(
            "target bus type changed: {}",
            expectation.display_name
        ));
    }
    if hardware_identity_match(expectation.hardware_id, observation.hardware_id.as_deref())
        == Some(false)
    {
        return Err(format!(
            "target hardware identity changed: {}",
            expectation.display_name
        ));
    }
    Ok(())
}

/// Validates an explicitly declared regular-file target.
fn validate_file_backed(expectation: TargetExpectation<'_>) -> Result<(), String> {
    let metadata = fs::metadata(expectation.path).map_err(|error| {
        format!(
            "failed to inspect file-backed target ({}): {error}",
            expectation.display_name
        )
    })?;
    if !metadata.is_file() {
        return Err(format!(
            "file-backed target is not a regular file: {}",
            expectation.display_name
        ));
    }
    if metadata.len() != expectation.size_bytes {
        return Err(format!(
            "target size changed ({}): expected {}, actual {}",
            expectation.display_name,
            expectation.size_bytes,
            metadata.len()
        ));
    }
    Ok(())
}

/// Compares text fields only when both sides are available.
fn known_text_matches(expected: Option<&str>, actual: Option<&str>) -> bool {
    match (expected, actual) {
        (Some(expected), Some(actual)) => expected.trim().eq_ignore_ascii_case(actual.trim()),
        _ => true,
    }
}

/// Compares overlapping key/value components from platform hardware identities.
fn hardware_identity_match(expected: Option<&str>, actual: Option<&str>) -> Option<bool> {
    let (Some(expected), Some(actual)) = (expected, actual) else {
        return None;
    };
    let expected_parts = identity_parts(expected);
    let actual_parts = identity_parts(actual);
    if expected_parts.is_empty() || actual_parts.is_empty() {
        return Some(expected.trim().eq_ignore_ascii_case(actual.trim()));
    }

    let mut compared = false;
    for (name, expected_value) in expected_parts {
        if let Some(actual_value) = actual_parts
            .iter()
            .find_map(|(actual_name, value)| (actual_name == &name).then_some(value))
        {
            compared = true;
            if !expected_value.eq_ignore_ascii_case(actual_value) {
                return Some(false);
            }
        }
    }
    compared.then_some(true)
}

/// Splits a platform identity into normalized key/value components.
fn identity_parts(value: &str) -> Vec<(String, String)> {
    value
        .split([';', '|'])
        .filter_map(|part| {
            let (name, value) = part.split_once('=')?;
            let name = name.trim();
            let value = value.trim();
            (!name.is_empty() && !value.is_empty())
                .then(|| (name.to_ascii_lowercase(), value.to_string()))
        })
        .collect()
}

/// Inspects a real target on Windows through native storage APIs.
#[cfg(windows)]
fn inspect_target(path: &Path) -> Result<TargetObservation, String> {
    crate::platform::inspect_target(path).map_err(|error| error.to_string())
}

/// Inspects a real target on Linux through fixed-path lsblk JSON output.
#[cfg(target_os = "linux")]
fn inspect_target(path: &Path) -> Result<TargetObservation, String> {
    linux::inspect_target(path)
}

/// Inspects a real target on macOS through fixed-path diskutil plist output.
#[cfg(target_os = "macos")]
fn inspect_target(path: &Path) -> Result<TargetObservation, String> {
    macos::inspect_target(path)
}

/// Rejects real-device writes on unsupported operating systems.
#[cfg(not(any(windows, target_os = "linux", target_os = "macos")))]
fn inspect_target(_path: &Path) -> Result<TargetObservation, String> {
    Err("target identity validation is not supported on this operating system".to_string())
}

/// Linux target inspection.
#[cfg(target_os = "linux")]
mod linux {
    use super::TargetObservation;
    use serde_json::Value;
    use std::path::{Path, PathBuf};
    use std::process::Command;

    /// Returns target properties from lsblk.
    pub(super) fn inspect_target(path: &Path) -> Result<TargetObservation, String> {
        let executable = lsblk_executable()
            .ok_or_else(|| "cannot find lsblk at /usr/bin/lsblk or /bin/lsblk".to_string())?;
        let output = Command::new(executable)
            .args([
                "--json",
                "--bytes",
                "--output",
                "PATH,TYPE,SIZE,RM,RO,MOUNTPOINTS,MODEL,TRAN,SERIAL,WWN,HOTPLUG",
                "--",
            ])
            .arg(path)
            .output()
            .map_err(|error| format!("failed to run lsblk: {error}"))?;
        if !output.status.success() {
            return Err(format!(
                "lsblk exited with {}: {}",
                output.status,
                String::from_utf8_lossy(&output.stderr).trim()
            ));
        }

        let root: Value = serde_json::from_slice(&output.stdout)
            .map_err(|error| format!("failed to parse lsblk JSON: {error}"))?;
        let devices = root
            .get("blockdevices")
            .and_then(Value::as_array)
            .ok_or_else(|| "lsblk JSON has no blockdevices array".to_string())?;
        if devices.len() != 1 {
            return Err(format!(
                "lsblk returned {} target devices instead of one",
                devices.len()
            ));
        }
        let device = &devices[0];
        if text_value(device, "type").as_deref() != Some("disk") {
            return Err("target is not a whole disk".to_string());
        }

        let bus_type = text_value(device, "tran");
        let removable = bool_value(device, "rm")
            || (bool_value(device, "hotplug")
                && bus_type
                    .as_deref()
                    .is_some_and(|value| value.eq_ignore_ascii_case("usb")));
        Ok(TargetObservation {
            size_bytes: integer_value(device, "size")
                .ok_or_else(|| "lsblk did not report a target size".to_string())?,
            removable,
            system: has_mount_point(device, "/"),
            read_only: bool_value(device, "ro"),
            model: text_value(device, "model"),
            bus_type,
            hardware_id: hardware_id(device),
        })
    }

    /// Returns the trusted lsblk executable path.
    fn lsblk_executable() -> Option<PathBuf> {
        [PathBuf::from("/usr/bin/lsblk"), PathBuf::from("/bin/lsblk")]
            .into_iter()
            .find(|path| path.is_file())
    }

    /// Reads one trimmed JSON text field.
    fn text_value(node: &Value, name: &str) -> Option<String> {
        let value = node.get(name)?.as_str()?.trim();
        (!value.is_empty()).then(|| value.to_string())
    }

    /// Reads one JSON integer field that may be encoded as text.
    fn integer_value(node: &Value, name: &str) -> Option<u64> {
        let value = node.get(name)?;
        value
            .as_u64()
            .or_else(|| value.as_i64().and_then(|number| number.try_into().ok()))
            .or_else(|| value.as_str()?.parse().ok())
    }

    /// Reads one JSON boolean field that may be encoded as a number or text.
    fn bool_value(node: &Value, name: &str) -> bool {
        let Some(value) = node.get(name) else {
            return false;
        };
        value.as_bool().unwrap_or_else(|| {
            value.as_i64().is_some_and(|number| number != 0)
                || value
                    .as_str()
                    .is_some_and(|text| text == "1" || text.eq_ignore_ascii_case("true"))
        })
    }

    /// Returns whether this node or one of its children has a mount point.
    fn has_mount_point(node: &Value, expected: &str) -> bool {
        let direct = node
            .get("mountpoints")
            .and_then(Value::as_array)
            .is_some_and(|mounts| {
                mounts
                    .iter()
                    .filter_map(Value::as_str)
                    .any(|mount| mount == expected)
            });
        direct
            || node
                .get("children")
                .and_then(Value::as_array)
                .is_some_and(|children| {
                    children
                        .iter()
                        .any(|child| has_mount_point(child, expected))
                })
    }

    /// Builds the same stable identity used by the Java Linux enumerator.
    fn hardware_id(node: &Value) -> Option<String> {
        let serial = text_value(node, "serial");
        let wwn = text_value(node, "wwn");
        let mut parts = Vec::new();
        if let Some(serial) = serial {
            parts.push(format!("serial={serial}"));
        }
        if let Some(wwn) = wwn {
            parts.push(format!("wwn={wwn}"));
        }
        (!parts.is_empty()).then(|| parts.join(";"))
    }
}

/// macOS target inspection.
#[cfg(target_os = "macos")]
mod macos {
    use super::TargetObservation;
    use plist::{Dictionary, Value};
    use std::path::Path;
    use std::process::Command;

    /// Returns target properties from diskutil info -plist.
    pub(super) fn inspect_target(path: &Path) -> Result<TargetObservation, String> {
        let output = Command::new("/usr/sbin/diskutil")
            .args(["info", "-plist"])
            .arg(path)
            .output()
            .map_err(|error| format!("failed to run diskutil: {error}"))?;
        if !output.status.success() {
            return Err(format!(
                "diskutil exited with {}: {}",
                output.status,
                String::from_utf8_lossy(&output.stderr).trim()
            ));
        }

        let value = Value::from_reader_xml(output.stdout.as_slice())
            .map_err(|error| format!("failed to parse diskutil plist: {error}"))?;
        let info = value
            .as_dictionary()
            .ok_or_else(|| "diskutil plist root is not a dictionary".to_string())?;
        if string_value(info, "VirtualOrPhysical")
            .as_deref()
            .is_some_and(|value| value.eq_ignore_ascii_case("virtual"))
        {
            return Err("target is a virtual disk".to_string());
        }
        let identifier = string_value(info, "DeviceIdentifier")
            .ok_or_else(|| "diskutil did not report a target identifier".to_string())?;
        if whole_disk_identifier(&identifier) != identifier {
            return Err("target is not a whole disk".to_string());
        }

        let read_only =
            bool_value(info, "ReadOnlyMedia") || !bool_value_default(info, "Writable", true);
        Ok(TargetObservation {
            size_bytes: integer_value(info, "TotalSize")
                .ok_or_else(|| "diskutil did not report a target size".to_string())?,
            removable: bool_value(info, "RemovableMedia") || bool_value(info, "Ejectable"),
            system: is_system_target(info)?,
            read_only,
            model: string_value(info, "MediaName").or_else(|| string_value(info, "DeviceModel")),
            bus_type: string_value(info, "BusProtocol"),
            hardware_id: hardware_id(info),
        })
    }

    /// Reads one trimmed plist string field.
    fn string_value(dictionary: &Dictionary, name: &str) -> Option<String> {
        let value = dictionary.get(name)?.as_string()?.trim();
        (!value.is_empty()).then(|| value.to_string())
    }

    /// Reads one unsigned plist integer field.
    fn integer_value(dictionary: &Dictionary, name: &str) -> Option<u64> {
        dictionary.get(name)?.as_unsigned_integer()
    }

    /// Reads one plist boolean field with a false default.
    fn bool_value(dictionary: &Dictionary, name: &str) -> bool {
        bool_value_default(dictionary, name, false)
    }

    /// Reads one plist boolean field with an explicit default.
    fn bool_value_default(dictionary: &Dictionary, name: &str, default: bool) -> bool {
        dictionary
            .get(name)
            .and_then(Value::as_boolean)
            .unwrap_or(default)
    }

    /// Returns whether the target backs a mounted macOS system volume.
    fn is_system_target(target_info: &Dictionary) -> Result<bool, String> {
        let target_identifier = string_value(target_info, "DeviceIdentifier")
            .ok_or_else(|| "diskutil did not report a target identifier".to_string())?;
        let output = Command::new("/usr/sbin/diskutil")
            .args(["list", "-plist"])
            .output()
            .map_err(|error| format!("failed to run diskutil list: {error}"))?;
        if !output.status.success() {
            return Err(format!(
                "diskutil list exited with {}: {}",
                output.status,
                String::from_utf8_lossy(&output.stderr).trim()
            ));
        }

        let value = Value::from_reader_xml(output.stdout.as_slice())
            .map_err(|error| format!("failed to parse diskutil list plist: {error}"))?;
        let disks = value
            .as_dictionary()
            .and_then(|root| root.get("AllDisksAndPartitions"))
            .and_then(Value::as_array)
            .ok_or_else(|| "diskutil list plist has no AllDisksAndPartitions array".to_string())?;
        let target_disk = disks
            .iter()
            .find(|disk| {
                disk.as_dictionary()
                    .and_then(|dictionary| string_value(dictionary, "DeviceIdentifier"))
                    .as_deref()
                    == Some(target_identifier.as_str())
            })
            .ok_or_else(|| "target is missing from diskutil list output".to_string())?;

        for disk in disks {
            if !has_system_mount(disk) {
                continue;
            }
            let Some(dictionary) = disk.as_dictionary() else {
                continue;
            };
            let identifier = string_value(dictionary, "DeviceIdentifier");
            if identifier.as_deref() == Some(target_identifier.as_str())
                || apfs_container_uses_target(dictionary, &target_identifier)
                || identifier.as_deref().is_some_and(|container_identifier| {
                    references_apfs_container(target_disk, container_identifier)
                })
            {
                return Ok(true);
            }
        }
        Ok(false)
    }

    /// Returns whether a plist subtree contains a macOS system mount point.
    fn has_system_mount(value: &Value) -> bool {
        match value {
            Value::Dictionary(dictionary) => dictionary.iter().any(|(name, nested)| {
                if name == "MountPoint" {
                    nested.as_string().is_some_and(|mount_point| {
                        mount_point == "/" || mount_point.starts_with("/System/Volumes")
                    })
                } else {
                    has_system_mount(nested)
                }
            }),
            Value::Array(values) => values.iter().any(has_system_mount),
            _ => false,
        }
    }

    /// Returns whether an APFS container uses a store on the target whole disk.
    fn apfs_container_uses_target(container: &Dictionary, target_identifier: &str) -> bool {
        container
            .get("APFSPhysicalStores")
            .and_then(Value::as_array)
            .is_some_and(|stores| {
                stores.iter().any(|store| {
                    store
                        .as_dictionary()
                        .and_then(|dictionary| string_value(dictionary, "DeviceIdentifier"))
                        .is_some_and(|identifier| {
                            whole_disk_identifier(&identifier) == target_identifier
                        })
                })
            })
    }

    /// Returns whether a plist subtree references an APFS container identifier.
    fn references_apfs_container(value: &Value, container_identifier: &str) -> bool {
        match value {
            Value::Dictionary(dictionary) => dictionary.iter().any(|(name, nested)| {
                (name == "APFSContainerReference"
                    && nested.as_string() == Some(container_identifier))
                    || references_apfs_container(nested, container_identifier)
            }),
            Value::Array(values) => values
                .iter()
                .any(|nested| references_apfs_container(nested, container_identifier)),
            _ => false,
        }
    }

    /// Returns the containing whole-disk identifier for a disk or partition.
    fn whole_disk_identifier(identifier: &str) -> &str {
        let Some(slice_index) = identifier.rfind('s') else {
            return identifier;
        };
        let suffix = &identifier[slice_index + 1..];
        if slice_index == 0
            || suffix.is_empty()
            || !suffix.bytes().all(|byte| byte.is_ascii_digit())
        {
            return identifier;
        }
        &identifier[..slice_index]
    }

    /// Builds the same stable identity used by the Java macOS enumerator.
    fn hardware_id(info: &Dictionary) -> Option<String> {
        let mut parts = Vec::new();
        for (identity_name, plist_name) in [
            ("mediaUuid", "MediaUUID"),
            ("diskUuid", "DiskUUID"),
            ("deviceTreePath", "DeviceTreePath"),
            ("ioRegistryEntryName", "IORegistryEntryName"),
            ("serialNumber", "SerialNumber"),
        ] {
            if let Some(value) = string_value(info, plist_name) {
                parts.push(format!("{identity_name}={value}"));
            }
        }
        (!parts.is_empty()).then(|| parts.join(";"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Verifies overlapping hardware identity fields are compared independently.
    #[test]
    fn compares_overlapping_hardware_identity_fields() {
        assert_eq!(
            hardware_identity_match(
                Some("uniqueId=x|serialNumber=abc"),
                Some("serialNumber=ABC"),
            ),
            Some(true)
        );
        assert_eq!(
            hardware_identity_match(
                Some("uniqueId=x|serialNumber=abc"),
                Some("serialNumber=xyz"),
            ),
            Some(false)
        );
        assert_eq!(
            hardware_identity_match(Some("uniqueId=x"), Some("serialNumber=abc")),
            None
        );
    }
}
