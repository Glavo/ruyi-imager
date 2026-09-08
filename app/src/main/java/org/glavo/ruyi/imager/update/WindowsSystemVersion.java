// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.update;

import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;

/// Reads the Windows major, minor, and build numbers without launching a subprocess.
@NotNullByDefault
final class WindowsSystemVersion {
    /// Native OSVERSIONINFOW structure, whose DWORD and WCHAR fields are 32 and 16 bits.
    private static final StructLayout VERSION_INFO = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("size"),
            ValueLayout.JAVA_INT.withName("major"),
            ValueLayout.JAVA_INT.withName("minor"),
            ValueLayout.JAVA_INT.withName("build"),
            ValueLayout.JAVA_INT.withName("platform"),
            MemoryLayout.sequenceLayout(128, ValueLayout.JAVA_CHAR).withName("servicePack"));

    /// Prevents construction.
    private WindowsSystemVersion() {
    }

    /// Queries RtlGetVersion and releases the native allocation and library lookup before returning.
    ///
    /// @return decimal major.minor.build version.
    /// @throws IOException if the native invocation fails or returns an unsuccessful status.
    /// @throws IllegalArgumentException if the Windows library or symbol cannot be loaded.
    /// @throws IllegalCallerException if native access is not enabled.
    static String read() throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            var function = Linker.nativeLinker().downcallHandle(
                    SymbolLookup.libraryLookup("ntdll", arena).findOrThrow("RtlGetVersion"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment info = arena.allocate(VERSION_INFO);
            info.set(ValueLayout.JAVA_INT, 0, (int) VERSION_INFO.byteSize());
            int status;
            try {
                status = (int) function.invokeExact(info);
            } catch (Error error) {
                throw error;
            } catch (Throwable exception) {
                throw new IOException("RtlGetVersion invocation failed.", exception);
            }
            if (status != 0) {
                throw new IOException("RtlGetVersion failed with status: " + Integer.toUnsignedString(status));
            }
            return component(info, "major") + "." + component(info, "minor") + "." + component(info, "build");
        }
    }

    /// Returns one unsigned DWORD from the version structure.
    ///
    /// @param info live version structure.
    /// @param name named DWORD field.
    /// @return unsigned decimal field value.
    private static String component(MemorySegment info, String name) {
        return Integer.toUnsignedString(info.get(ValueLayout.JAVA_INT,
                VERSION_INFO.byteOffset(MemoryLayout.PathElement.groupElement(name))));
    }
}
