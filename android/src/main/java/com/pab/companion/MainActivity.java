package com.pab.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.List;

public final class MainActivity extends Activity {
    private static final int INK=0xff18243b, MUTED=0xff50617b, BLUE=0xff2357c6, PAPER=0xfff1f5fa;
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout page, gate, workspace;
    private EditText address, code;
    private TextView connection, counts, reason, checked, pairingStatus, folderLocation;
    private Button pairButton, resume;
    private SharedPreferences prefs;
    private boolean pairing, wantPhotos, formatting;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            boolean ready=PhotosInspector.ready();
            gate.setVisibility(ready?View.GONE:View.VISIBLE);
            workspace.setVisibility(ready?View.VISIBLE:View.GONE);
            TransferService service=TransferService.running;
            if (ready && service!=null) {
                connection.setText(service.connectionText());
                counts.setText(service.counts());
                reason.setText(service.status());
                checked.setText(service.lastChecked());
                folderLocation.setText("내부 저장소 / "+displayedPath());
                String prompt=service.consumePrompt();
                if (wantPhotos) {
                    wantPhotos=false;
                    openPhotos();
                } else if (!prompt.isEmpty()) {
                    new AlertDialog.Builder(MainActivity.this).setTitle("구글 포토를 열까요?")
                            .setMessage("PC에서 전송을 시작했어요. 포토를 열면 원본 백업 확인을 시작해요.")
                            .setPositiveButton("구글 포토 열기",(d,w)->openPhotos())
                            .setNegativeButton("나중에",null).show();
                }
            } else if (ready) {
                connection.setText("PC 연결 대기");
                counts.setText("연결한 뒤 PC에서 전송을 시작하세요.");
                reason.setText("");
                checked.setText("");
                if (wantPhotos) { wantPhotos=false; openPhotos(); }
            }
            main.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences("job",MODE_PRIVATE);
        wantPhotos=TransferService.ACTION_PHOTOS.equals(getIntent().getAction());
        getWindow().setStatusBarColor(INK);
        getWindow().setNavigationBarColor(PAPER);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        page=column();
        page.setPadding(dp(19),dp(20),dp(19),dp(24));
        scroll.addView(page);
        setContentView(scroll);
        title(page,"오토픽셀",29);

        gate=card(page);
        title(gate,"접근성 서비스 먼저 켜기",21);
        text(gate,"구글 포토에서 영상별 백업 상태를 확인하려면 오토픽셀의 접근성 서비스가 필요해요.",15,MUTED);
        button(gate,"접근성 설정 열기",true,v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        workspace=column();
        workspace.setVisibility(View.GONE);
        page.addView(workspace);
        LinearLayout overview=card(workspace);
        connection=title(overview,"PC 연결 대기",20);
        counts=text(overview,"",17,INK);
        reason=text(overview,"",15,MUTED);
        checked=text(overview,"",14,MUTED);
        button(overview,"구글 포토 열기 · 백업 확인",true,v->openPhotos());

        LinearLayout folder=card(workspace);
        title(folder,"받은 영상",20);
        folderLocation=text(folder,"내부 저장소 / "+TransferService.DIRECTORY,15,MUTED);
        button(folder,"저장 폴더 열기",false,v->openFolder());
        button(folder,"받은 파일 목록",false,v->showFiles());

        LinearLayout pair=card(workspace);
        title(pair,"PC 연결",20);
        Switch usb=new Switch(this);
        usb.setText("USB로 연결");
        usb.setTextColor(INK);
        usb.setTextSize(16);
        usb.setChecked(prefs.getBoolean("pairUSB",false));
        pair.addView(usb);
        address=input(pair,"PC 주소 (예: 192.168.0.24)");
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fillAddress(usb.isChecked());
        usb.setOnCheckedChangeListener((view,value)->fillAddress(value));

        code=input(pair,"연결 코드 (예: ABC-32F)");
        code.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setTypeface(Typeface.MONOSPACE);
        code.setFilters(new InputFilter[]{new InputFilter.AllCaps(),new InputFilter.LengthFilter(12)});
        code.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s,int start,int count,int after) {}
            @Override public void onTextChanged(CharSequence s,int start,int before,int count) {}
            @Override public void afterTextChanged(Editable e) {
                if (formatting) return;
                int cursor=Math.max(0,Math.min(e.length(),code.getSelectionStart()));
                String digits=PairCode.normalize(e.toString()).replaceAll("[^0-9A-Z]","");
                String left=PairCode.normalize(e.subSequence(0,cursor).toString()).replaceAll("[^0-9A-Z]","");
                if (digits.length()>6) digits=digits.substring(0,6);
                String value=digits.length()>3?digits.substring(0,3)+"-"+digits.substring(3):digits;
                if (value.contentEquals(e)) return;
                formatting=true;
                e.replace(0,e.length(),value);
                int position=Math.min(digits.length(),left.length());
                code.setSelection(position+(position>3?1:0));
                formatting=false;
            }
        });
        pairingStatus=text(pair,"",14,MUTED);
        pairButton=button(pair,"연결하기",true,v->pair(usb.isChecked()));
        resume=button(pair,"기존 연결 재개",false,v->startTransfer());
        resume.setEnabled(!prefs.getString("pair","").isEmpty());
        button(pair,"PC 연결 끊기",false,v->{
            PhotosInspector inspector=PhotosInspector.active;
            if(inspector!=null)inspector.pauseInspection();
            stopService(new Intent(this,TransferService.class));
        });

        button(workspace,"진단 보기",false,v->{
            PhotosInspector inspector=PhotosInspector.active;
            TransferService service=TransferService.running;
            String detail=inspector==null?"접근성 서비스 대기 중":inspector.diagnostic();
            if(service!=null)detail=service.status()+"\n"+service.lastChecked()+"\n\n"+detail;
            new AlertDialog.Builder(this).setTitle("백업 확인 진단").setMessage(detail).setPositiveButton("닫기",null).show();
        });
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        wantPhotos=TransferService.ACTION_PHOTOS.equals(intent.getAction());
    }
    @Override protected void onResume() { super.onResume(); main.removeCallbacks(refresh); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }

    private int dp(int value) { return (int)(getResources().getDisplayMetrics().density*value+.5f); }
    private LinearLayout column() {
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box=column();
        box.setPadding(dp(16),dp(13),dp(16),dp(13));
        GradientDrawable background=new GradientDrawable();
        background.setColor(0xffffffff);
        background.setCornerRadius(dp(15));
        background.setStroke(dp(1),0xffdce4f0);
        box.setBackground(background);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
        layout.topMargin=dp(12);
        parent.addView(box,layout);
        return box;
    }
    private TextView title(LinearLayout owner,String value,int size) {
        TextView view=text(owner,value,size,INK);
        view.setTypeface(null,Typeface.BOLD);
        return view;
    }
    private TextView text(LinearLayout owner,String value,int size,int color) {
        TextView view=new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        view.setPadding(0,dp(4),0,dp(4));
        owner.addView(view);
        return view;
    }
    private EditText input(LinearLayout owner,String hint) {
        EditText view=new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setTextSize(18);
        owner.addView(view,new LinearLayout.LayoutParams(-1,-2));
        return view;
    }
    private Button button(LinearLayout owner,String label,boolean primary,View.OnClickListener action) {
        Button view=new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(16);
        view.setTextColor(primary?0xffffffff:BLUE);
        view.setBackgroundTintList(ColorStateList.valueOf(primary?BLUE:0xffeaf0fc));
        view.setOnClickListener(action);
        owner.addView(view,new LinearLayout.LayoutParams(-1,dp(49)));
        return view;
    }
    private void fillAddress(boolean usb) {
        address.setText(prefs.getString(usb?"usbAddress":"wifiAddress",usb?"127.0.0.1":""));
    }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void startTransfer() {
        if (!PhotosInspector.ready()) return;
        startForegroundService(new Intent(this,TransferService.class));
    }
    private void openPhotos() {
        PhotosInspector inspector=PhotosInspector.active;
        if(inspector==null) return;
        Intent open=getPackageManager().getLaunchIntentForPackage(TransferService.PHOTOS);
        if(open==null) { toast("구글 포토 앱을 찾을 수 없습니다."); return; }
        inspector.beginFlow();
        startActivity(open);
    }
    private String displayedPath() {
        TransferService service=TransferService.running;
        if(service==null)return TransferService.DIRECTORY;
        List<TransferService.Received> files=service.received();
        if(files.isEmpty())return TransferService.DIRECTORY;
        for(TransferService.Received file:files) if(TransferService.DIRECTORY.equals(file.path)) return TransferService.DIRECTORY;
        return TransferService.LEGACY_DIRECTORY;
    }
    private void openFolder() {
        String path=displayedPath();
        Intent browse=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .putExtra(DocumentsContract.EXTRA_INITIAL_URI,DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents","primary:"+path.substring(0,path.length()-1)));
        try { startActivityForResult(browse,11); }
        catch (RuntimeException error) { toast("Files 앱의 내부 저장소에서 "+path+"를 여세요."); }
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request==11 && result==RESULT_OK && data!=null && data.getData()!=null) openVideo(data.getData());
    }
    private void openVideo(Uri uri) {
        if(uri==null) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch(RuntimeException error) { toast("기기에서 이 영상을 열 수 없습니다."); }
    }
    private void showFiles() {
        TransferService service=TransferService.running;
        if(service==null) { toast("PC에 연결하면 받은 영상 목록을 볼 수 있어요."); return; }
        List<TransferService.Received> files=service.received();
        if(files.isEmpty()) { toast("이 묶음에서 받은 영상이 아직 없어요."); return; }
        String[] names=new String[files.size()];
        for(int i=0;i<names.length;i++){
            TransferService.Received file=files.get(i);
            names[i]=file.name+"\n"+file.path+" · "+(file.stage>=3?"복사본 정리 완료":file.stage==2?"백업 확인":"복사 완료");
        }
        new AlertDialog.Builder(this).setTitle("받은 영상 "+files.size()+"개").setItems(names,(dialog,index)->{
            TransferService.Received file=files.get(index);
            if(file.stage<3)openVideo(file.uri);
            else toast("픽셀 복사본 정리가 끝났어요.");
        }).setNegativeButton("닫기",null).show();
    }

    private void pair(boolean usb) {
        if(pairing || !PhotosInspector.ready()) return;
        String pc=address.getText().toString().trim(), entered=code.getText().toString();
        pairing=true;
        pairButton.setEnabled(false);
        resume.setEnabled(false);
        pairingStatus.setText("PC에 연결 중…");
        new Thread(()->{
            try {
                String id=Pairing.device(prefs), trusted=Pairing.join(pc,entered,id);
                if(!prefs.edit().putString("pair",trusted).putBoolean("pairUSB",usb)
                        .putString(usb?"usbAddress":"wifiAddress",pc).commit()) throw new IllegalStateException("연결 정보를 저장할 수 없습니다.");
                main.post(()->finishPair(null));
            } catch(Exception error) {
                main.post(()->finishPair(error.getMessage()==null?"PC 연결을 확인해 주세요.":error.getMessage()));
            }
        },"autopixel-pair").start();
    }

    private void finishPair(String error) {
        if(isFinishing() || isDestroyed()) return;
        pairing=false;
        pairButton.setEnabled(true);
        resume.setEnabled(!prefs.getString("pair","").isEmpty());
        if(error!=null) { pairingStatus.setText(error); return; }
        code.setText("");
        pairingStatus.setText("연결했어요. PC에서 전송 시작을 눌러 주세요.");
        startTransfer();
    }
}
