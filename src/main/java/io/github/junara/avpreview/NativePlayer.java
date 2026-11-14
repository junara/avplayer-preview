package io.github.junara.avpreview;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

/** JNI only; serialize calls on the editor's worker. Native methods marshal to AppKit. */
public final class NativePlayer {
    private static final String PREFIX = "libavpreview-";
    /** Leftovers younger than this may belong to another IDE process that is extracting right now. */
    private static final Duration STALE_AFTER = Duration.ofMinutes(10);
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rwx------");
    private static boolean loaded;

    /**
     * Extracts the bundled dylib once per build (named by its SHA-256) and reuses it across loads and IDE
     * restarts. The cache directory must be a real directory owned by this user with no group/other access,
     * so no other account can plant or swap the library between the content check and System.load.
     */
    public static synchronized void load() throws IOException {
        if (loaded) return;
        // The bundled dylib is arm64-only; fail with a clear message instead of a dlopen architecture error.
        if (!"aarch64".equals(System.getProperty("os.arch")))
            throw new IOException("AVPlayer Preview supports Apple Silicon (arm64) only; this JVM is " + System.getProperty("os.arch"));
        byte[] bytes;
        try (var stream = NativePlayer.class.getResourceAsStream("/native/libavpreview.dylib")) {
            if (stream == null) throw new IOException("Bundled native library not found");
            bytes = stream.readAllBytes();
        }
        var tmp = Path.of(System.getProperty("java.io.tmpdir"));
        var dir = privateCacheDir(tmp.resolve("avpreview-" + System.getProperty("user.name")));
        // Someone else holds the shared name (or it is unsafe): use a fresh private directory, without reuse.
        boolean fallback = dir == null;
        if (fallback) dir = Files.createTempDirectory(tmp, "avpreview-");
        var library = dir.resolve(PREFIX + sha256(bytes) + ".dylib");
        if (fallback) { // deleteOnExit runs in reverse order: the file first, then its directory
            dir.toFile().deleteOnExit();
            library.toFile().deleteOnExit();
        }
        if (!hasContent(library, bytes)) extract(dir, library, bytes);
        System.load(library.toAbsolutePath().toString());
        loaded = true;
        removeStale(dir, library);
    }

    /** Creates {@code dir} owner-only, or returns null when an existing one is not safe to load code from. */
    private static Path privateCacheDir(Path dir) {
        try {
            try {
                Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // checked below
            }
            var me = dir.getFileSystem().getUserPrincipalLookupService()
                    .lookupPrincipalByName(System.getProperty("user.name"));
            boolean safe = Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    && Files.getOwner(dir, LinkOption.NOFOLLOW_LINKS).equals(me)
                    && Files.getPosixFilePermissions(dir, LinkOption.NOFOLLOW_LINKS).equals(OWNER_ONLY);
            return safe ? dir : null;
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    /** True only for a regular file (not a symlink) with exactly these bytes; unreadable counts as a mismatch. */
    private static boolean hasContent(Path library, byte[] bytes) {
        try {
            return Files.isRegularFile(library, LinkOption.NOFOLLOW_LINKS)
                    && Arrays.equals(Files.readAllBytes(library), bytes);
        } catch (IOException e) {
            return false;
        }
    }

    /** Writes beside the target, then renames, so another IDE process never loads a partial file. */
    private static void extract(Path dir, Path library, byte[] bytes) throws IOException {
        var partial = Files.createTempFile(dir, PREFIX, ".partial");
        try {
            Files.write(partial, bytes);
            try {
                Files.move(partial, library, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(partial, library, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    /** Best effort: deletes other builds' dylibs and crashed .partial files that are no longer fresh. */
    private static void removeStale(Path dir, Path keep) {
        var cutoff = FileTime.from(Instant.now().minus(STALE_AFTER));
        try (var files = Files.newDirectoryStream(dir, PREFIX + "*")) {
            for (var file : files) {
                try {
                    if (!file.equals(keep)
                            && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).compareTo(cutoff) < 0)
                        Files.deleteIfExists(file);
                } catch (IOException ignored) {
                    // in use elsewhere or already gone
                }
            }
        } catch (IOException ignored) {
            // cleanup is optional
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every Java platform
        }
    }
    public static native long open(String path);
    public static native void play(long id, double rate);
    public static native void pause(long id);
    public static native void seek(long id, double seconds);
    public static native void volume(long id, double volume);
    /** status (0 loading,1 ready,2 failed), seconds, duration, playback rate. */
    public static native double[] state(long id);
    public static native String error(long id);
    /**
     * Stream metadata, or null for an unknown id. Lines of {@code key\tvalue}; a line without a tab
     * starts a section (e.g. "Video", "Audio #2"); rows before the first section are general.
     * {@code fileBytes} (0 if unknown) yields the "Overall bitrate" row right after "Duration".
     * A "Metadata" row lists keys that failed (with the reason) or missed the shared load deadline.
     * Blocks while metadata loads (bounded by the native kInfoTimeoutSeconds): call it off the EDT and
     * off the playback worker. Not serialized with the other calls; a concurrent {@link #close} is safe.
     */
    public static native String info(long id, long fileBytes);
    /**
     * Scales the newest frame into {@code dest} as xRGB ints (TYPE_INT_RGB layout).
     * Returns 0 if there is no new frame, {@code (width << 16) | height} when written, or the
     * negated value when {@code dest} is null or not exactly width*height long; the frame is then
     * kept and returned by the next call.
     */
    public static native int frame(long id, int[] dest);
    public static native void close(long id);
}
