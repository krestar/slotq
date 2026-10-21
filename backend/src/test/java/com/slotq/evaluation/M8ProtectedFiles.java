package com.slotq.evaluation;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;
import static com.slotq.evaluation.M8FixtureValidator.*;

final class M8ProtectedFiles {
    static Path root(String runId) {
        require(runId.matches("[a-zA-Z0-9_-]{1,100}"), "unsafe run ID");
        return Path.of("..").toAbsolutePath().normalize().resolve("build/m8-evidence").resolve(runId);
    }

    static String create(Path root) throws IOException {
        require(!Files.exists(root), "evidence already exists; new runId required");
        Files.createDirectories(root);
        var acl = Files.getFileAttributeView(root, AclFileAttributeView.class);
        if (acl != null) {
            var owner = Files.getOwner(root);
            acl.setAcl(List.of(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.DIRECTORY_INHERIT, AclEntryFlag.FILE_INHERIT).build()));
        } else {
            require(Files.getFileAttributeView(root, PosixFileAttributeView.class) != null, "protected evidence ACL unavailable");
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwx------"));
        }
        return verify(root);
    }

    static String verify(Path root) throws IOException {
        require(!Files.isSymbolicLink(root) && root.toRealPath().equals(root.toAbsolutePath().normalize()), "redirected evidence root");
        var acl = Files.getFileAttributeView(root, AclFileAttributeView.class);
        if (acl != null) {
            var entries = acl.getAcl(); var owner = Files.getOwner(root);
            require(!entries.isEmpty() && entries.stream().filter(e -> e.type() == AclEntryType.ALLOW)
                .allMatch(e -> e.principal().equals(owner)), "evidence ACL allows other principals");
            return entries.toString();
        }
        var permissions = Files.getPosixFilePermissions(root);
        require(permissions.equals(PosixFilePermissions.fromString("rwx------")), "evidence permissions not private");
        return permissions.toString();
    }

    static Path resolve(Path root, String relative) throws IOException {
        require(relative.matches("[a-zA-Z0-9_./-]+") && !relative.contains(".."), "unsafe artifact path");
        Path path = root.resolve(relative).normalize();
        require(path.startsWith(root) && !path.equals(root), "artifact escapes evidence root");
        for (Path parent = path; parent != null && parent.startsWith(root); parent = parent.getParent()) {
            require(!Files.isSymbolicLink(parent), "symlink artifact");
            if (Files.exists(parent)) require(parent.toRealPath().startsWith(root.toRealPath()), "redirected artifact path");
        }
        return path;
    }
    private M8ProtectedFiles() { }
}
