package org.renpy.android;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Installs the game files of Doki Doki Literature Club that the user provides
 * (ddlc-win.zip, from ddlc.moe). The app doesn't carry any of them.
 *
 * Only plain Java (no Android classes), so it can be tested on a PC.
 *
 * The zip is read once. Each of the four .rpa files is copied to a temporary
 * folder while its SHA-256 is computed, and it is compared with the official one.
 * That is enough for the Windows, Mac and Linux zips, which contain the same .rpa.
 * The SHA-256 of the whole zip is only reported (see {@link #OFFICIAL_ZIP_SHA256}).
 */
public class InstallerDDLC {

    /** SHA-256 of the official ddlc-win.zip (DDLC 1.1.1). */
    public static final String OFFICIAL_ZIP_SHA256 =
            "2a3dd7969a06729a32ace0a6ece5f2327e29bdf460b8b39e6a8b0875e545632e";

    /** Text of the last unexpected error of {@link #install}, to write it in the log. */
    public static volatile String lastError = null;

    /** Name of the file that says the installation finished. */
    public static final String MARKER_NAME = ".ddlc_verified";

    private static final String TEMP_DIR_NAME = ".ddlc_tmp";

    /** Extra space asked on top of the files, for the temporary copy. */
    private static final long SPACE_MARGIN = 32L * 1024 * 1024;

    /** One of the files of the original game that is installed. */
    public static final class RpaFile {
        public final String name;
        public final long size;
        public final String sha256;

        RpaFile(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }

    public static final RpaFile[] FILES = new RpaFile[] {
            new RpaFile("audio.rpa", 65683283L,
                    "121fedc50823e2a76d947025cc0f2dfa7c64b2454760b50091a64d1d36b7d2e7"),
            new RpaFile("images.rpa", 137750644L,
                    "6c3dccd4f35723ca1679b95710d4d09cec3d22439e24264bc6ff60d90640d393"),
            new RpaFile("fonts.rpa", 1831581L,
                    "d48beafa7e1f3171b0e8e312f857af0e7eb387ef1e524a5be2595d46652d2018"),
            new RpaFile("scripts.rpa", 2708885L,
                    "da7ba6d3cf9ec1ae666ec29ae07995a65d24cca400cd266e470deb55e03a51d4"),
    };

    public enum Result {
        /** All the files were installed. */
        OK,
        /** The zip is not an official DDLC (a file is missing or different). */
        NOT_OFFICIAL,
        /** The zip couldn't be read (damaged or cut). */
        UNREADABLE,
        /** There isn't enough free space. */
        NO_SPACE,
        /** {@link Progress#isCancelled()} returned true. */
        CANCELLED
    }

    public interface Progress {
        /** @param doneBytes bytes of the .rpa files already copied; @param totalBytes all of them */
        void onProgress(long doneBytes, long totalBytes);

        boolean isCancelled();
    }

    /** Total size of the files that get installed. */
    public static long totalSize() {
        long total = 0;
        for (RpaFile f : FILES) {
            total += f.size;
        }
        return total;
    }

    /**
     * True when the marker exists and the four files have exactly the expected size.
     * The marker alone isn't enough: the user could have deleted a file.
     */
    public static boolean isInstalled(File gameDir) {
        if (!new File(gameDir, MARKER_NAME).isFile()) {
            return false;
        }
        return hasFiles(gameDir);
    }

    /** True when the four files are in the folder with the expected size (no marker needed). */
    public static boolean hasFiles(File dir) {
        for (RpaFile f : FILES) {
            File file = new File(dir, f.name);
            if (!file.isFile() || file.length() != f.size) {
                return false;
            }
        }
        return true;
    }

    /**
     * Reads the zip and installs the four files in `gameDir`.
     * Nothing is left in `gameDir` unless everything is right: the files are copied to a
     * temporary folder inside it and moved at the end.
     */
    public static Result install(InputStream zip, File gameDir, Progress progress) {
        File tempDir = new File(gameDir, TEMP_DIR_NAME);
        lastError = null;
        deleteRecursively(tempDir);

        try {
            if (!gameDir.isDirectory() && !gameDir.mkdirs()) {
                return Result.UNREADABLE;
            }

            if (gameDir.getUsableSpace() < totalSize() + SPACE_MARGIN) {
                return Result.NO_SPACE;
            }

            if (!tempDir.mkdirs()) {
                return Result.UNREADABLE;
            }

            long total = totalSize();
            long done = 0;
            Map<String, String> found = new HashMap<String, String>();
            byte[] buffer = new byte[1024 * 1024];

            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(zip, 1024 * 1024));
            try {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    RpaFile expected = expectedFor(entry);
                    if (expected == null || found.containsKey(expected.name)) {
                        continue;
                    }

                    MessageDigest digest = MessageDigest.getInstance("SHA-256");
                    File target = new File(tempDir, expected.name);
                    long written = 0;
                    OutputStream out = new FileOutputStream(target);
                    try {
                        int read;
                        while ((read = zis.read(buffer)) > 0) {
                            if (progress != null && progress.isCancelled()) {
                                deleteRecursively(tempDir);
                                return Result.CANCELLED;
                            }
                            out.write(buffer, 0, read);
                            digest.update(buffer, 0, read);
                            written += read;
                            done += read;
                            if (progress != null) {
                                progress.onProgress(Math.min(done, total), total);
                            }
                        }
                    } finally {
                        out.close();
                    }

                    if (written != expected.size || !expected.sha256.equals(toHex(digest.digest()))) {
                        deleteRecursively(tempDir);
                        return Result.NOT_OFFICIAL;
                    }
                    found.put(expected.name, expected.sha256);

                    // Nothing else is needed. Stopping here also avoids the entries that
                    // come after, some of which ZipInputStream can't read
                    if (found.size() == FILES.length) {
                        break;
                    }
                }
            } finally {
                zis.close();
            }

            if (found.size() != FILES.length) {
                deleteRecursively(tempDir);
                return Result.NOT_OFFICIAL;
            }

            for (RpaFile f : FILES) {
                File target = new File(gameDir, f.name);
                if (target.exists() && !target.delete()) {
                    deleteRecursively(tempDir);
                    return Result.UNREADABLE;
                }
                if (!new File(tempDir, f.name).renameTo(target)) {
                    deleteRecursively(tempDir);
                    return Result.UNREADABLE;
                }
            }
            deleteRecursively(tempDir);

            writeMarker(gameDir);
            return Result.OK;

        } catch (java.util.zip.ZipException e) {
            lastError = e.toString();
            deleteRecursively(tempDir);
            return Result.UNREADABLE;
        } catch (IOException e) {
            lastError = e.toString();
            deleteRecursively(tempDir);
            // A full disk shows up as an IOException while writing
            return gameDir.getUsableSpace() < SPACE_MARGIN ? Result.NO_SPACE : Result.UNREADABLE;
        } catch (Exception e) {
            lastError = e.toString();
            deleteRecursively(tempDir);
            return Result.UNREADABLE;
        }
    }

    /** SHA-256 of a whole stream, in lowercase hexadecimal. */
    public static String sha256(InputStream in, Progress progress, long totalBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[1024 * 1024];
        long done = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (progress != null && progress.isCancelled()) {
                return null;
            }
            digest.update(buffer, 0, read);
            done += read;
            if (progress != null) {
                progress.onProgress(done, totalBytes);
            }
        }
        return toHex(digest.digest());
    }

    /** The .rpa this entry is, or null. The path must end in "/game/<name>.rpa" or be "game/<name>.rpa". */
    private static RpaFile expectedFor(ZipEntry entry) {
        if (entry.isDirectory()) {
            return null;
        }
        String name = entry.getName().replace('\\', '/').toLowerCase(Locale.ROOT);
        for (RpaFile f : FILES) {
            String tail = "game/" + f.name;
            if (name.equals(tail) || name.endsWith("/" + tail)) {
                return f;
            }
        }
        return null;
    }

    private static void writeMarker(File gameDir) throws IOException {
        StringBuilder text = new StringBuilder();
        text.append("DDLC 1.1.1\n");
        for (RpaFile f : FILES) {
            text.append(f.name).append(' ').append(f.size).append(' ').append(f.sha256).append('\n');
        }
        File marker = new File(gameDir, MARKER_NAME);
        OutputStream out = new FileOutputStream(marker);
        try {
            out.write(text.toString().getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}
