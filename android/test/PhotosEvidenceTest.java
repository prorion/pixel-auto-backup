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

        // Pixel 1 · Android 10 · Photos 8.2.1 (labels observed on the device, personal values replaced).
        java.util.List<String> pixel1Details=Arrays.asList("2024년 3월 18일 (월) • 오전 10:12",
                "미디어의 날짜와 시간을 수정합니다.","설명 추가...","세부정보","백업됨","원본 화질",
                "이 항목은 계정 저장용량의 공간을 차지하지 않습니다. 자세히 알아보기",name,"0.3 MP","720 x 480",
                "백업하는 사진과 동영상은 안전하게 보관됩니다. 자세히 알아보기","위치 추가","정보 닫기");
        require(PhotosEvidence.inspect(pixel1Details,name::equals).verified(), "Pixel 1 Photos 8.2 details");
        java.util.List<String> saver=new java.util.ArrayList<>(pixel1Details);
        saver.set(saver.indexOf("원본 화질"),"저장용량 절약");
        require(!PhotosEvidence.inspect(saver,name::equals).verified(), "Pixel 1 storage saver details");
        require(!PhotosEvidence.inspect(pixel1Details,"other.mp4"::equals).verified(), "Pixel 1 details of another file");
        require(!PhotosEvidence.inspect(Arrays.asList("2024. 3. 18. 오전 10:12에 촬영한 동영상","백업 완료","포토"),
                name::equals).verified(), "Photos home backup chip");

        java.util.List<String> overview=Arrays.asList("위로 이동","백업 설정","백업 사용 중",
                "이 기기의 사진 및 동영상 자동 백업하기","사용","백업 계정",account,"콘텐츠의 품질","원본");
        require(PhotosEvidence.hasAccount(overview,account), "Photos 8.2 backup overview account");
        java.util.List<String> otherOverview=new java.util.ArrayList<>(overview);
        otherOverview.set(otherOverview.indexOf(account),"other@example.org");
        require(!PhotosEvidence.hasAccount(otherOverview,account) && PhotosEvidence.otherAccount(otherOverview,account),
                "Photos 8.2 backup overview with another account");

        java.util.List<String> menu=Arrays.asList(account,account+" 계정으로 로그인함","닫기","홍길동",
                "Google 계정 관리","15GB 중 0% 사용","백업","백업 완료","포토 설정","고객센터");
        require(PhotosEvidence.accountMenu(menu), "Photos 8.2 account menu");
        require(!PhotosEvidence.hasAccount(menu,account), "signed-in account is not the backup account");
        require(!PhotosEvidence.accountMenu(overview) && !PhotosEvidence.accountMenu(pixel1Details), "not the account menu");

        require(PhotosEvidence.accountMenuButton("com.google.android.apps.photos:id/selected_account_disc",null), "avatar id");
        require(PhotosEvidence.accountMenuButton(null,"홍길동 "+account+" 계정으로 로그인함\n계정 및 설정."), "Photos 8.2 avatar");
        require(PhotosEvidence.accountMenuButton(null,"Signed in as "+account+"\nAccount and settings."), "English avatar");
        require(PhotosEvidence.accountMenuButton(null,"Google 계정: 홍길동 ("+account+")"), "older avatar");
        require(!PhotosEvidence.accountMenuButton("com.google.android.apps.photos:id/search_destination","검색"), "search button");
        require(!PhotosEvidence.accountMenuButton(null,"만든 항목, 새 콘텐츠"), "create button");
        System.out.println("Photos accessibility evidence tests passed");
    }
}
