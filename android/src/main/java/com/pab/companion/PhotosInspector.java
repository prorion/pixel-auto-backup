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
    private boolean scrolledDetails;
    private String lastTarget = "";
    private volatile String diagnostic = "구글 포토 화면을 열어 주세요.";
    private volatile String recentDetails = "";
    private static final class Screen {
        final Set<String> visible=new LinkedHashSet<>();
        final Set<String> details=new LinkedHashSet<>();
    }
    private final Runnable followUp = new Runnable() {
        @Override public void run() {
            if (!continuous) return;
            Screen screen=readScreen();
            if (!screen.visible.isEmpty()) {
                inspectWindow(screen);
                TransferService transfer=TransferService.running;
                if (transfer!=null && transfer.waitingForAccount()) advanceAccount(screen.visible);
                else if (lastTarget.isEmpty()) openNext();
                else if (!scrolledDetails && TransferService.unlocked(PhotosInspector.this)
                        && (!screen.details.isEmpty() || PhotosEvidence.details(screen.visible))) {
                    scrolledDetails=scrollDetails(PhotosEvidence.details(screen.visible));
                }
            }
            main.postDelayed(this,2000);
        }
    };

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
    String diagnostic() {
        return recentDetails.isEmpty()?diagnostic:diagnostic+"\n\n최근 영상 세부정보\n"+recentDetails;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        TransferService transfer=TransferService.running;
        if (transfer==null || transfer.expectedAccount().isEmpty() || !TransferService.unlocked(this)
                || event==null || event.getPackageName()==null
                || !TransferService.PHOTOS.contentEquals(event.getPackageName())) return;
        inspectWindow(readScreen());
    }
    private void inspectWindow(Screen screen) {
        TransferService transfer=TransferService.running;
        if (transfer==null || !TransferService.unlocked(this) || screen.visible.isEmpty()) return;
        String expected=transfer.expectedAccount();
        boolean account=PhotosEvidence.hasAccount(screen.visible,expected);
        boolean otherAccount=PhotosEvidence.otherAccount(screen.visible,expected);
        if (account) transfer.confirmAccount(expected);
        else if (otherAccount) transfer.clearAccountProof();

        PhotosEvidence.Item result=PhotosEvidence.inspect(screen.visible,transfer::acceptsName);
        Set<String> observed=screen.visible;
        if (!screen.details.isEmpty() && !result.conflicts && !result.verified()) {
            PhotosEvidence.Item attached=PhotosEvidence.inspect(screen.details,transfer::acceptsName);
            if (!attached.name.isEmpty() && (result.name.isEmpty() || result.name.equals(attached.name))) {
                result=attached;
                observed=screen.details;
            }
        }
        lastTarget=result.name;
        diagnostic="현재 포토 화면 · 대상 영상 "+(result.name.isEmpty()?"미확인":"식별")
                +" · 백업 "+(result.backedUp?"표시됨":"표시 없음")
                +" · 원본 화질 "+(result.original?"표시됨":"표시 없음")
                +" · 계정 "+(transfer.readyToInspect()?"확인":"대기");
        if (!result.name.isEmpty() || recentDetails.isEmpty()
                && (PhotosEvidence.details(screen.visible) || !screen.details.isEmpty())) {
            String excerpts=PhotosEvidence.redactedEvidence(observed);
            recentDetails=diagnostic+(excerpts.isEmpty()?"":"\n"+excerpts);
        }

        if (result.verified() && transfer.verify(result.name)) {
            transfer.inspectionHint("");
            if (continuous) {
                opened="";
                scrolledDetails=false;
                main.postDelayed(this::openNext,350);
            }
        } else if (PhotosEvidence.backupDisabled(screen.visible)) {
            transfer.inspectionHint("구글 포토의 기본 백업을 켜 주세요.");
        } else if (otherAccount) {
            transfer.inspectionHint("구글 포토의 백업 계정을 PC 설정과 맞춰 주세요.");
        } else if (transfer.nextUnverified()!=null) {
            if (!transfer.readyToInspect()) transfer.inspectionHint("구글 포토의 설정 → 백업에서 계정을 확인해 주세요.");
            else if (result.name.isEmpty()) transfer.inspectionHint("포토에서 대상 영상을 열고 위로 쓸어올려 주세요.");
            else if (result.conflicts) transfer.inspectionHint("이 영상의 백업 또는 원본 화질 표시를 확인해 주세요.");
            else if (!result.backedUp) transfer.inspectionHint("파일 확인 · 구글 포토의 영상별 백업 표시 대기");
            else if (!result.original) transfer.inspectionHint("백업 표시 확인 · 원본 화질 표시 대기");
        }
    }

    private boolean photosWindow(AccessibilityNodeInfo root) {
        return root.getPackageName()!=null && TransferService.PHOTOS.contentEquals(root.getPackageName());
    }

    private Screen readScreen() {
        Screen screen=new Screen();
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if (root==null) return screen;
        try {
            if (!photosWindow(root)) return screen;
            collect(root,screen.visible,0,true);
            AccessibilityNodeInfo pane=findPane(root,0);
            if (pane!=null) try {
                collect(pane,screen.details,0,false);
            } finally { pane.recycle(); }
            else if (PhotosEvidence.details(screen.visible)) collect(root,screen.details,0,false);
        } finally { root.recycle(); }
        return screen;
    }

    private AccessibilityNodeInfo findPane(AccessibilityNodeInfo node,int depth) {
        if (depth>25 || !node.isVisibleToUser()) return null;
        String id=node.getViewIdResourceName();
        if (id!=null && id.matches(".*:id/(?:details_bottom_sheet|photo_details|media_details|info_panel|details_panel|details_container)")) {
            return AccessibilityNodeInfo.obtain(node);
        }
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try {
                AccessibilityNodeInfo match=findPane(child,depth+1);
                if(match!=null)return match;
            } finally { child.recycle(); }
        }
        return null;
    }

    private void collect(AccessibilityNodeInfo node,Set<String> labels,int depth,boolean onlyVisible) {
        if(depth>32 || labels.size()>=400 || onlyVisible && !node.isVisibleToUser())return;
        String text=PhotosEvidence.clean(node.getText()), desc=PhotosEvidence.clean(node.getContentDescription());
        if (!text.isEmpty()) labels.add(text);
        if (!desc.isEmpty()) labels.add(desc);
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try { collect(child,labels,depth+1,onlyVisible); } finally { child.recycle(); }
        }
    }

    private boolean scrollDetails(boolean hasDetailsHeading) {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if (root==null) return false;
        try {
            if (!photosWindow(root)) return false;
            AccessibilityNodeInfo pane=findPane(root,0);
            if(pane!=null)try { return scrollPane(pane,0); } finally { pane.recycle(); }
            return hasDetailsHeading && scrollPane(root,0);
        } finally { root.recycle(); }
    }

    private boolean scrollPane(AccessibilityNodeInfo node,int depth) {
        if (depth>24 || !node.isVisibleToUser()) return false;
        if (node.isScrollable() && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true;
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try { if(scrollPane(child,depth+1))return true; } finally { child.recycle(); }
        }
        return false;
    }

    void beginFlow() {
        continuous = true;
        opened = "";
        lastTarget="";
        scrolledDetails=false;
        recentDetails="";
        accountSteps=0;
        accountActionAt=0;
        main.removeCallbacks(followUp);
        main.postDelayed(followUp,750);
    }

    private void advanceAccount(Set<String> labels) {
        if (accountSteps>=4 || !TransferService.unlocked(this) || SystemClock.elapsedRealtime()-accountActionAt<900) return;
        boolean claimed=false;
        boolean menu=PhotosEvidence.accountMenu(labels);
        if (menu || labels.contains("Photos settings") || labels.contains("포토 설정")) {
            // The account menu's Backup card opens the backup overview, which names the backup account.
            claimed=clickBackupSettings();
            if (!claimed) claimed=clickVisible("Photos settings","포토 설정",false);
            if (!claimed && menu) {
                // The cards sit below the storage card and can be off-screen on small or rotated displays.
                if (scrollWindow()) accountActionAt=SystemClock.elapsedRealtime();
                return;
            }
        } else if (labels.contains("Settings") || labels.contains("설정")) {
            claimed=clickBackupSettings();
        } else if (accountSteps==0) {
            claimed=clickWhere((node,text,desc)->PhotosEvidence.accountMenuButton(node.getViewIdResourceName(),desc));
        }
        if (claimed) {
            accountSteps++;
            accountActionAt=SystemClock.elapsedRealtime();
        }
    }

    private boolean scrollWindow() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if (root==null) return false;
        try { return photosWindow(root) && scrollPane(root,0); } finally { root.recycle(); }
    }

    private boolean clickBackupSettings() {
        return clickVisible("Backup","백업",false)
                || clickVisible("Backup & sync","백업 및 동기화",false)
                || clickVisible("Back up & sync","백업 및 동기화",false);
    }

    private interface Target { boolean matches(AccessibilityNodeInfo node,String text,String desc); }

    private boolean clickVisible(String first,String second,boolean prefixes) {
        return clickWhere((node,text,desc)->first.equals(text) || second.equals(text) || first.equals(desc) || second.equals(desc)
                || prefixes && (desc.startsWith(first+":") || desc.startsWith(second+":")));
    }

    private boolean clickWhere(Target target) {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null)return false;
        try {
            return photosWindow(root) && clickMatch(root,target,0);
        } finally { root.recycle(); }
    }

    private boolean clickMatch(AccessibilityNodeInfo node,Target target,int depth) {
        if(depth>24 || !node.isVisibleToUser())return false;
        String text=PhotosEvidence.clean(node.getText()), desc=PhotosEvidence.clean(node.getContentDescription());
        if (target.matches(node,text,desc) && clickSelfOrAncestor(node)) return true;
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try { if(clickMatch(child,target,depth+1))return true; }
            finally { child.recycle(); }
        }
        return false;
    }

    // Photos 8.x account cards keep the label several layers below the clickable card.
    private static boolean clickSelfOrAncestor(AccessibilityNodeInfo node) {
        if (node.isClickable()) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo current=node.getParent();
        for (int level=0; current!=null && level<5; level++) {
            if (current.isClickable()) {
                try { return current.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                finally { current.recycle(); }
            }
            AccessibilityNodeInfo next=current.getParent();
            current.recycle();
            current=next;
        }
        if (current!=null) current.recycle();
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
        scrolledDetails=false;
        try {
            Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/*")
                    .setPackage(TransferService.PHOTOS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(view);
            main.postDelayed(() -> revealDetails(name,0),900);
            return true;
        } catch (RuntimeException error) {
            diagnostic="구글 포토에서 대상 영상을 직접 열고 위로 쓸어올려 주세요.";
            transfer.inspectionHint(diagnostic);
            return false;
        }
    }
    private static final int REVEAL_ATTEMPTS=6;

    private void revealDetails(String name,int navigationStep) {
        if (!continuous || !opened.equals(name) || !TransferService.unlocked(this)) return;
        Screen window=readScreen();
        String expected=PhotosEvidence.clean(name);
        PhotosEvidence.Item full=PhotosEvidence.inspect(window.details,expected::equals);
        PhotosEvidence.Item visible=PhotosEvidence.inspect(window.visible,expected::equals);
        if (!full.name.isEmpty() || visible.verified()) return;
        if (navigationStep>=REVEAL_ATTEMPTS) {
            TransferService transfer=TransferService.running;
            if (transfer!=null) transfer.inspectionHint("포토에서 대상 영상을 열고 위로 쓸어올려 주세요.");
            return;
        }
        if (navigationStep<2 && (clickVisible("Details","세부정보",false)
                || clickVisible("Details","세부 정보",false)
                || navigationStep==0 && (clickVisible("More options","더보기",false)
                || clickVisible("More options","추가 옵션",false)))) {
            main.postDelayed(()->revealDetails(name,navigationStep+1),600);
            return;
        }
        // A full-screen Photos video hides its controls and exposes no text at all (Pixel 1, Photos 8.2),
        // so recognise the viewer by its pager id. The first swipe can also land before the viewer is
        // ready, so keep trying until the details sheet appears.
        if (viewerOpen()) {
            DisplayMetrics screen = getResources().getDisplayMetrics();
            Path path = new Path();
            path.moveTo(screen.widthPixels * .5f, screen.heightPixels * .72f);
            path.lineTo(screen.widthPixels * .5f, screen.heightPixels * .34f);
            dispatchGesture(new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(path,0,320)).build(),
                    null,null);
        }
        main.postDelayed(()->revealDetails(name,navigationStep+1),1500);
    }

    private boolean viewerOpen() {
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if (root==null) return false;
        try { return photosWindow(root) && hasViewId(root,":id/photo_view_pager",0); } finally { root.recycle(); }
    }

    private static boolean hasViewId(AccessibilityNodeInfo node,String suffix,int depth) {
        if (depth>20 || !node.isVisibleToUser()) return false;
        String id=node.getViewIdResourceName();
        if (id!=null && id.endsWith(suffix)) return true;
        for (int i=0;i<node.getChildCount();i++) {
            AccessibilityNodeInfo child=node.getChild(i);
            if(child!=null)try { if(hasViewId(child,suffix,depth+1))return true; } finally { child.recycle(); }
        }
        return false;
    }
}
