package org.pepsoft.worldpainter.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/** Credential files are separate from world/configuration files, private, atomic and locked across processes. */
final class ChatGptSessionStore {
    ChatGptSessionStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

    <T> T locked(Operation<T> operation) throws IOException, InterruptedException {
        JVM_LOCK.lockInterruptibly();
        try {
            Files.createDirectories(directory);
            protect(directory, true);
            Path lock = directory.resolve("session.lock");
            rejectLink(lock);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                protect(lock, false);
                try (var ignored = channel.lock()) { return operation.run(); }
            }
        } catch (java.nio.channels.FileLockInterruptionException e) {
            throw new InterruptedException("ChatGPT sign-in canceled");
        } finally { JVM_LOCK.unlock(); }
    }

    String hostId() throws IOException {
        Path path = directory.resolve("host-id");
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            String value = read(path).trim();
            try {
                if (value.startsWith("urn:uuid:") && UUID.fromString(value.substring(9)).version() == 4) return value;
            } catch (IllegalArgumentException ignored) { }
            throw new IOException("Stored ChatGPT host identifier is invalid");
        }
        String value = "urn:uuid:" + UUID.randomUUID();
        write(path, value);
        return value;
    }

    Map<?, ?> load() throws IOException {
        Path path = directory.resolve("session.json");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        try {
            Object value = JsonProtocol.read(read(path));
            if (value instanceof Map<?, ?> map && Long.valueOf(1).equals(map.get("version"))) return map;
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        throw new IOException("Stored ChatGPT sign-in is invalid; reset the connection before signing in again");
    }

    void save(Map<String, Object> state) throws IOException { write(directory.resolve("session.json"), JsonProtocol.write(state)); }

    void forget() throws IOException {
        Path path = directory.resolve("session.json");
        rejectLink(path);
        Files.deleteIfExists(path);
    }

    private String read(Path path) throws IOException {
        rejectLink(path);
        protect(path, false);
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] data = input.readNBytes(65_537);
            if (data.length > 65_536) throw new IOException("Stored ChatGPT sign-in exceeds its size limit");
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private void write(Path path, String value) throws IOException {
        byte[] data = value.getBytes(StandardCharsets.UTF_8);
        if (data.length > 65_536) throw new IOException("ChatGPT sign-in exceeds its storage limit");
        rejectLink(path);
        Path temporary = Files.createTempFile(directory, "session-", ".tmp");
        try {
            protect(temporary, false);
            try (FileChannel out = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) out.write(buffer);
                out.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("ChatGPT credential storage must not use symbolic links");
    }

    private static void protect(Path path, boolean directory) throws IOException {
        rejectLink(path);
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("This filesystem cannot protect ChatGPT credentials");
        var entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(acl.getOwner())
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT);
        acl.setAcl(List.of(entry.build()));
    }

    @FunctionalInterface interface Operation<T> { T run() throws IOException, InterruptedException; }
    private final Path directory;
    private static final ReentrantLock JVM_LOCK = new ReentrantLock();
}
