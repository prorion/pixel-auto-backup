package com.pab.companion;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.LinkedHashSet;
import java.util.Set;

public final class PhotosInspector extends AccessibilityService {
    static volatile PhotosInspector active;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean continuous;
    private String opened = "";
    private long openedAt;
    private final Runnable followUp = new Runnable() {
        @Override public void run() {
            if (!continuous) return;
            if (!windowLabels().isEmpty()) {
                inspectWindow();
                if (!opened.isEmpty() && SystemClock.elapsedRealtime() - openedAt > 30_000) opened = "";
                openNext();
            }
            main.postDelayed(this, 4000);
        }
    };
    private volatile String diagnostic = "구글 포토 화면을 열어 주세요.";

    @Override protected void onServiceConnected() { active = this; }
    @Override public void onDestroy() {
        if (active == this) active = null;
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    @Override public void onInterrupt() { pauseInspection(); }
    String diagnostic() { return diagnostic; }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null || !TransferService.PHOTOS.contentEquals(event.getPackageName())) return;
        inspectWindow();
    }
    private void inspectWindow() {
        TransferService transfer = TransferService.running;
        if (transfer == null || !TransferService.unlocked(this)) return;
        Set<String> labels = windowLabels();
        if (labels.isEmpty()) return;
        String expected = transfer.expectedAccount();
        boolean account = PhotosEvidence.hasAccount(labels, expected);
        if (account) transfer.confirmAccount(expected);
        else if (PhotosEvidence.otherAccount(labels, expected)) transfer.clearAccountProof();

        PhotosEvidence.Item result = PhotosEvidence.inspect(labels, transfer::acceptsName);
        diagnostic = "포토 텍스트 " + labels.size() + "개 · 대상 파일 " + (result.name.isEmpty() ? "미확인" : "확인")
                + " · 백업 표시 " + (result.backedUp ? "있음" : "없음")
                + " · 원본 화질 " + (result.original ? "있음" : "없음")
                + " · 계정 " + (transfer.readyToInspect() ? "확인" : "대기");
        if (result.verified() && transfer.verify(result.name) && continuous) {
            opened = "";
            main.postDelayed(this::openNext, 750);
        }
    }
    private Set<String> windowLabels() {
        Set<String> labels = new LinkedHashSet<>();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return labels;
        try {
            if (root.getPackageName() != null && TransferService.PHOTOS.contentEquals(root.getPackageName())) collect(root,labels,0);
        } finally { root.recycle(); }
        return labels;
    }
    private void collect(AccessibilityNodeInfo node, Set<String> labels, int depth) {
        if (depth > 32 || labels.size() >= 400 || !node.isVisibleToUser()) return;
        String text = PhotosEvidence.clean(node.getText());
        String desc = PhotosEvidence.clean(node.getContentDescription());
        if (!text.isEmpty()) labels.add(text);
        if (!desc.isEmpty()) labels.add(desc);
        for (int i=0; i<node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { collect(child,labels,depth+1); } finally { child.recycle(); }
        }
    }
    boolean startInspection() {
        continuous = true;
        opened = "";
        main.removeCallbacks(followUp);
        main.postDelayed(followUp, 4000);
        return openNext();
    }
    void pauseInspection() {
        continuous = false;
        main.removeCallbacksAndMessages(null);
    }
    private boolean openNext() {
        TransferService transfer = TransferService.running;
        if (!continuous || transfer == null || !transfer.readyToInspect() || !TransferService.unlocked(this)) return false;
        Uri uri = transfer.nextUri();
        String name = transfer.nextName();
        if (uri == null || name.isEmpty() || name.equals(opened)) return false;
        opened = name;
        openedAt = SystemClock.elapsedRealtime();
        try {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/*")
                    .setPackage(TransferService.PHOTOS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
            main.postDelayed(() -> revealDetails(name), 1400);
            return true;
        } catch (RuntimeException error) {
            diagnostic = "포토에서 PixelAutoBackup 폴더의 대상 영상을 직접 열어 주세요.";
            return false;
        }
    }
    private void revealDetails(String name) {
        if (!continuous || !opened.equals(name) || !TransferService.unlocked(this)) return;
        Set<String> labels = windowLabels();
        if (labels.isEmpty() || labels.contains("Details") || labels.contains("세부정보")
                || PhotosEvidence.inspect(labels, name::equals).verified()) return;
        DisplayMetrics screen = getResources().getDisplayMetrics();
        Path path = new Path();
        path.moveTo(screen.widthPixels * .5f, screen.heightPixels * .72f);
        path.lineTo(screen.widthPixels * .5f, screen.heightPixels * .34f);
        dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,320)).build(),
                null,null);
    }
}
