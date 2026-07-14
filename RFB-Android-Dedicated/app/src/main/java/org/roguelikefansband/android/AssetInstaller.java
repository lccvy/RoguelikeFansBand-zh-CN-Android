package org.roguelikefansband.android;

import android.content.Context;
import android.content.res.AssetManager;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs immutable RFB runtime resources while preserving saves and user data. */
public final class AssetInstaller {
    private static final String ZIP_ASSET = "rfb-data.zip";
    private static final String META_ASSET = "rfb-build-meta.json";
    private static final String MARKER = ".rfb-resource-revision";

    private static final Set<String> PRESERVE_DIRS = new HashSet<>();

    static {
        PRESERVE_DIRS.add("save");
        PRESERVE_DIRS.add("user");
        PRESERVE_DIRS.add("apex");
        PRESERVE_DIRS.add("bone");
        PRESERVE_DIRS.add("data");
    }

    private AssetInstaller() {
    }

    public static File install(Context context) throws IOException {
        File root = new File(context.getFilesDir(), "rfb");
        File lib = new File(root, "lib");
        ensureDirectory(lib);

        String revision = readResourceRevision(context.getAssets());
        File marker = new File(root, MARKER);
        String installed = "";
        if (marker.isFile()) {
            try {
                installed = readUtf8(marker).trim();
            } catch (IOException corruptMarker) {
                // A torn marker must trigger a clean reinstall, not brick startup.
                StartupDiagnostics.recordThrowable("assets.marker.read", corruptMarker);
            }
        }

        if (!revision.equals(installed)) {
            removeReplaceableResourceDirs(lib);
            unzipAssetSafely(context.getAssets(), ZIP_ASSET, root);
            createWritableDirs(lib);
            writeUtf8Atomically(marker, revision + "\n");
        } else {
            createWritableDirs(lib);
        }
        return root;
    }

    private static String readResourceRevision(AssetManager assets) throws IOException {
        try (InputStream in = assets.open(META_ASSET)) {
            String json = readAllUtf8(in);
            JSONObject object = new JSONObject(json);
            String revision = object.optString("resource_revision", "").trim();
            if (revision.isEmpty()) {
                // Backward compatibility for v5 and locally prepared old assets.
                revision = object.optString("source_revision", "").trim();
            }
            if (revision.isEmpty()) {
                throw new IOException("资源元数据缺少 resource_revision/source_revision");
            }
            return revision;
        } catch (Exception ex) {
            if (ex instanceof IOException) {
                throw (IOException) ex;
            }
            throw new IOException("无法解析资源元数据", ex);
        }
    }

    private static void removeReplaceableResourceDirs(File lib) throws IOException {
        File[] children = lib.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (PRESERVE_DIRS.contains(child.getName())) {
                continue;
            }
            deleteRecursively(child);
        }
    }

    private static void createWritableDirs(File lib) throws IOException {
        for (String name : PRESERVE_DIRS) {
            ensureDirectory(new File(lib, name));
        }
    }

    private static void unzipAssetSafely(AssetManager assets, String assetName, File root)
            throws IOException {
        String canonicalRoot = root.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(
                new BufferedInputStream(assets.open(assetName)))) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(root, entry.getName());
                String canonicalTarget = target.getCanonicalPath();
                if (!canonicalTarget.startsWith(canonicalRoot)) {
                    throw new IOException("拒绝不安全的 ZIP 路径：" + entry.getName());
                }
                if (entry.isDirectory()) {
                    ensureDirectory(target);
                } else {
                    File parent = target.getParentFile();
                    if (parent != null) {
                        ensureDirectory(parent);
                    }
                    File temp = new File(target.getPath() + ".tmp");
                    try (BufferedOutputStream out = new BufferedOutputStream(
                            new FileOutputStream(temp))) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) {
                            if (read > 0) {
                                out.write(buffer, 0, read);
                            }
                        }
                    }
                    moveReplace(temp.toPath(), target.toPath());
                }
                zip.closeEntry();
            }
        }
    }

    private static void moveReplace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        if (file.exists() && !file.delete()) {
            throw new IOException("无法删除旧资源：" + file);
        }
    }

    private static void ensureDirectory(File dir) throws IOException {
        if (dir.isDirectory()) {
            return;
        }
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建目录：" + dir);
        }
    }

    private static String readUtf8(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAllUtf8(in);
        }
    }

    private static String readAllUtf8(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            if (read > 0) {
                out.write(buffer, 0, read);
            }
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void writeUtf8Atomically(File file, String text) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        moveReplace(temp.toPath(), file.toPath());
    }
}
