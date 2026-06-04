// Copyright (c) 2026 Glavo
// SPDX-License-Identifier: MPL-2.0

package org.glavo.ruyi.imager.i18n;

import javafx.beans.binding.StringBinding;
import javafx.beans.property.SimpleStringProperty;
import org.glavo.ruyi.imager.core.SdkMessages;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

/// Tests for localized message lookup and bindings.
@NotNullByDefault
public final class MessagesTest {
    /// Verifies that a message binding is invalidated when the selected locale changes.
    @Test
    public void bindingUpdatesWhenLocaleChanges() {
        Locale originalLocale = Messages.locale();
        StringBinding binding = Messages.binding("gui.button.flash");
        try {
            Messages.setLocale(Locale.ENGLISH);
            assertEquals(Messages.get("gui.button.flash"), binding.get());

            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals(Messages.get("gui.button.flash"), binding.get());
        } finally {
            binding.dispose();
            Messages.setLocale(originalLocale);
        }
    }

    /// Verifies that formatted bindings observe observable format arguments.
    @Test
    public void formattedBindingUpdatesWhenArgumentChanges() {
        Locale originalLocale = Messages.locale();
        SimpleStringProperty fileName = new SimpleStringProperty("image.raw");
        StringBinding binding = Messages.binding("gui.value.local.selected", fileName);
        try {
            Messages.setLocale(Locale.ENGLISH);
            assertEquals(Messages.get("gui.value.local.selected", "image.raw"), binding.get());

            fileName.set("other.raw");
            assertEquals(Messages.get("gui.value.local.selected", "other.raw"), binding.get());

            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals(Messages.get("gui.value.local.selected", "other.raw"), binding.get());
        } finally {
            binding.dispose();
            Messages.setLocale(originalLocale);
        }
    }

    /// Verifies that GUI detail labels use the active application locale.
    @Test
    public void guiDetailLabelsUseApplicationLocale() {
        Locale originalLocale = Messages.locale();
        try {
            Messages.setLocale(Locale.ENGLISH);
            assertEquals("Serial: abc - State: fastboot", Messages.get("gui.fastboot.details", "abc", "fastboot"));

            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals("序列号：abc - 状态：fastboot", Messages.get("gui.fastboot.details", "abc", "fastboot"));
        } finally {
            Messages.setLocale(originalLocale);
        }
    }

    /// Verifies that SDK diagnostic messages use the active application locale.
    @Test
    public void sdkMessagesUseApplicationLocale() {
        Locale originalLocale = Messages.locale();
        try {
            Messages.setLocale(Locale.ENGLISH);
            assertEquals("Writing image to target", SdkMessages.get("core.flash.writing"));
            assertEquals(
                    "Sending fastboot partition root chunk 1/4",
                    SdkMessages.get("core.fastboot.sendingSparsePartition", "root", 1, 4));
            assertEquals("Downloading image.raw", SdkMessages.get("core.download.downloading", "image.raw"));
            assertEquals("Downloaded test-image", SdkMessages.get("core.download.imageComplete", "test-image"));
            assertEquals("Preparing image file image.tar.zst", SdkMessages.get("core.materialize.materializing", "image.tar.zst"));
            assertEquals("Prepared image test-image", SdkMessages.get("core.materialize.complete", "test-image"));

            Messages.setLocale(Locale.SIMPLIFIED_CHINESE);
            assertEquals("正在将镜像写入目标设备", SdkMessages.get("core.flash.writing"));
            assertEquals(
                    "正在发送 fastboot 分区 root 的 sparse chunk 1/4",
                    SdkMessages.get("core.fastboot.sendingSparsePartition", "root", 1, 4));
            assertEquals("正在下载 image.raw", SdkMessages.get("core.download.downloading", "image.raw"));
            assertEquals("已下载 test-image", SdkMessages.get("core.download.imageComplete", "test-image"));
            assertEquals("正在准备镜像文件 image.tar.zst", SdkMessages.get("core.materialize.materializing", "image.tar.zst"));
            assertEquals("镜像 test-image 已准备", SdkMessages.get("core.materialize.complete", "test-image"));
        } finally {
            Messages.setLocale(originalLocale);
        }
    }
}
