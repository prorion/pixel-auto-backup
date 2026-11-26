package com.pab.companion;

import java.util.Arrays;

public final class PhotosEvidenceTest {
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String name = "camera_1234.mp4";
        String account = "expected@example.org";
        require(PhotosEvidence.hasAccount(Arrays.asList("Backup account", account), account), "expected account");
        require(!PhotosEvidence.hasAccount(Arrays.asList("Photos", account), account), "account alone");
        require(PhotosEvidence.otherAccount(Arrays.asList("Backup account", "other@example.org"), account), "wrong account");
        require(!PhotosEvidence.hasAccount(Arrays.asList("Backing up to " + account, "Backing up to other@example.org"), account), "ambiguous account");
        require(PhotosEvidence.inspect(Arrays.asList(name, "Backed up (120 MB) • Original quality"), name::equals).verified(), "backed up original");
        require(PhotosEvidence.inspect(Arrays.asList(name, "백업됨", "원본 화질"), name::equals).verified(), "Korean backed up original");
        require(!PhotosEvidence.inspect(Arrays.asList(name, "Backup complete", "Original quality"), name::equals).verified(), "global complete");
        require(!PhotosEvidence.inspect(Arrays.asList(name, "Backed up", "Storage saver"), name::equals).verified(), "storage saver");
        require(!PhotosEvidence.inspect(Arrays.asList("another.mp4", "Backed up", "Original quality"), name::equals).verified(), "wrong file");
        require(!PhotosEvidence.inspect(Arrays.asList(name, "Backed up", "Original quality", "백업되지 않음"), name::equals).verified(), "conflicting status");
        System.out.println("Photos accessibility evidence tests passed");
    }
}
