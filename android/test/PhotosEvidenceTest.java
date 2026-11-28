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
        require(PhotosEvidence.inspect(Arrays.asList(
                "세부 정보","파일 이름: "+name+"\n2560 × 1440",
                "백업됨 (원본 화질, 29.3 MB)"),name::equals).verified(), "wrapped Korean details and grouped status");
        require(PhotosEvidence.inspect(Arrays.asList(
                name,"Backed up in original quality"),name::equals).verified(), "English combined status");
        require(PhotosEvidence.inspect(Arrays.asList(
                "File name: "+name,"Original quality (Backed up)"),name::equals).verified(), "original-first status");
        require(PhotosEvidence.inspect(Arrays.asList(
                name,"파일명: "+name,"원본 화질로 백업됨"),name::equals).verified(), "duplicate evidence for same filename");
        require(!PhotosEvidence.inspect(Arrays.asList(
                name,"백업 완료","원본 화질"),name::equals).verified(), "whole-library status cannot verify");
        require(!PhotosEvidence.inspect(Arrays.asList(
                name,"Backed up (Storage saver), Original quality"),name::equals).verified(), "conflicting formats remain blocked");
        require(!PhotosEvidence.inspect(Arrays.asList(
                name,"Backed up","Original quality recommended"),name::equals).verified(), "recommendations are not original quality");
        require(!PhotosEvidence.inspect(Arrays.asList(
                name,"second.mp4","Backed up in original quality"),value->value.endsWith(".mp4")).verified(), "ambiguous file name");
        require(PhotosEvidence.hasAccount(Arrays.asList("계정 및 저장용량",account),account), "older backup settings layout");
        require(PhotosEvidence.hasAccount(Arrays.asList("백업 계정: "+account),account), "combined backup account description");
        require(!PhotosEvidence.redactedEvidence(Arrays.asList("Backing up to "+account)).contains(account), "redact backup account");
        System.out.println("Photos accessibility evidence tests passed");
    }
}
