package com.ysm.parser;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * JNI wrapper for the YSMParser native library.
 */
public final class YSMParser {
    private static volatile Path loadedPath;

    private YSMParser() {
    }

    public static void ensureLoaded() {
        ensureLoaded(Path.of("native", "YSMParserJNI.dll"));
    }

    public static void ensureLoaded(Path nativeLibrary) {
        Path absolute = nativeLibrary.toAbsolutePath().normalize();
        Path current = loadedPath;
        if (current != null) {
            if (!current.equals(absolute)) {
                throw new IllegalStateException("YSMParser JNI 已从其他路径加载: " + current);
            }
            return;
        }

        synchronized (YSMParser.class) {
            current = loadedPath;
            if (current != null) {
                if (!current.equals(absolute)) {
                    throw new IllegalStateException("YSMParser JNI 已从其他路径加载: " + current);
                }
                return;
            }
            if (!Files.isRegularFile(absolute)) {
                throw new IllegalStateException("找不到 YSMParserJNI.dll: " + absolute);
            }
            System.load(absolute.toString());
            loadedPath = absolute;
        }
    }

    public static native boolean parse(String ysmFilePath, String outputDir);

    public static native boolean parseBytes(byte[] ysmData, String outputDir);

    public static native int getVersion(String ysmFilePath);
}
