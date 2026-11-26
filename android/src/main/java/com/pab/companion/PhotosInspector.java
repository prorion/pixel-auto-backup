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
    private int accountSteps;
    private long accountActionAt;
    static boolean ready() { return active!=null; }
    private String opened = "";
    private long openedAt;
    private final Runnable followUp = new Runnable() {
        @Override public void run() {
            if (!continuous) return;
            if (!windowLabels().isEmpty()) {
                inspectWindow();
                if (!opened.isEmpty() && SystemClock.elapsedRealtime() - openedAt > 8_000) opened = "";
                TransferService transfer=TransferService.running;
                if (transfer!=null && transfer.waitingForAccount()) advanceAccount(windowLabels());
                else openNext();
            }
            main.postDelayed(this, 2000);
        }
    };
    private volatile String diagnostic = "구글 포토 화면을 열어 주세요.";

    @Override protected void onServiceConnected() {
        active = this;
        TransferService transfer=TransferService.running;
        if(transfer!=null)transfer.accessibilityChanged();
    }
    @Override public boolean onUnbind(Intent intent) {
        if (active == this) active = null;
        pauseInspection();
        TransferService transfer=TransferService.running;
        if (transfer!=null) transfer.accessibilityChanged();
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        if (active == this) active = null;
        TransferService transfer=TransferService.running;
        if(transfer!=null)transfer.accessibilityChanged();
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
        boolean otherAccount=PhotosEvidence.otherAccount(labels,expected);
        if (account) transfer.confirmAccount(expected);
        else if (otherAccount) transfer.clearAccountProof();

        PhotosEvidence.Item result = PhotosEvidence.inspect(labels, transfer::acceptsName);
        diagnostic = "포토 텍스트 " + labels.size() + "개 · 대상 파일 " + (result.name.isEmpty() ? "미확인" : "확인")
                + " · 백업 표시 " + (result.backedUp ? "있음" : "없음")
                + " · 원본 화질 " + (result.original ? "있음" : "없음")
                + " · 계정 " + (transfer.readyToInspect() ? "확인" : "대기");
        if (result.verified() && transfer.verify(result.name)) {
            transfer.inspectionHint("");
            if (continuous) {
                opened = "";
                main.postDelayed(this::openNext, 350);
            }
        } else if (PhotosEvidence.backupDisabled(labels)) {
            transfer.inspectionHint("구글 포토의 기본 백업을 켜 주세요.");
        } else if (otherAccount) {
            transfer.inspectionHint("구글 포토의 백업 계정을 PC 설정과 맞춰 주세요.");
        } else if (transfer.readyToInspect() && transfer.nextUnverified()!=null) {
            if (result.name.isEmpty()) transfer.inspectionHint("구글 포토에서 대상 영상의 세부정보를 찾는 중");
            else if (!result.backedUp) transfer.inspectionHint("영상 복사 완료 · 구글 포토 백업 상태 대기");
            else if (!result.original) transfer.inspectionHint("구글 포토에서 원본 화질 확인 대기");
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
    void beginFlow() {
        continuous = true;
        opened = "";
        accountSteps=0;
        accountActionAt=0;
        main.removeCallbacks(followUp);
        main.postDelayed(followUp,750);
    }

    private void advanceAccount(Set<String> labels) {
        if (accountSteps>=4 || !TransferService.unlocked(this) || SystemClock.elapsedRealtime()-accountActionAt<900) return;
        boolean claimed=false;
        if (labels.contains("Photos settings") || labels.contains("포토 설정")) {
            claimed=clickVisible("Photos settings","포토 설정",false);
            if (!claimed && (labels.contains("Backup") || labels.contains("백업"))) {
                claimed=clickVisible("Backup","백업",false);
            }
        } else if (labels.contains("Settings") || labels.contains("설정")) {
            claimed=clickVisible("Backup","백업",false);
        } else if (accountSteps==0) {
            claimed=clickVisible("Google Account","Google 계정",true);
        }
        if (claimed) {
            accountSteps++;
            accountActionAt=SystemClock.elapsedRealtime();
        }
    }

    private boolean clickVisible(String first,String second,boolean prefixes) {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return false;
        try {
            return root.getPackageName()!=null && TransferService.PHOTOS.contentEquals(root.getPackageName())
                    && clickLabel(root,first,second,prefixes,0);
        } finally { root.recycle(); }
    }

    private boolean clickLabel(AccessibilityNodeInfo node,String first,String second,boolean prefixes,int depth) {
        if(depth>24 || !node.isVisibleToUser())return false;
        String text=PhotosEvidence.clean(node.getText()), desc=PhotosEvidence.clean(node.getContentDescription());
        if (first.equals(text) || second.equals(text) || first.equals(desc) || second.equals(desc)
                || prefixes && (desc.startsWith(first+":") || desc.startsWith(second+":"))) {
            if(node.isClickable())return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo parent=node.getParent();
            if(parent!=null)try {
                if(parent.isClickable())return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            } finally { parent.recycle(); }
        }
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try { if(clickLabel(child,first,second,prefixes,depth+1))return true; }
            finally { child.recycle(); }
        }
        return false;
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
            main.postDelayed(() -> revealDetails(name), 900);
            return true;
        } catch (RuntimeException error) {
            diagnostic = "구글 포토에서 대상 영상을 직접 열고 위로 쓸어올려 주세요.";
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
