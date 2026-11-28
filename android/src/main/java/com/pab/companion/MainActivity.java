package com.pab.companion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int INK=0xff14213d, MUTED=0xff5b6b84, PAPER=0xfff4f6fa, NAVY=0xff1b2d57, WHITE=0xffffffff,
            GREEN=0xff12b886, AMBER=0xffd98a00, GRAY=0xff98a2b3, LINE=0xffe3e8f0, TINT=0xffe9eef7;
    private static final Typeface MEDIUM=Typeface.create("sans-serif-medium",Typeface.NORMAL);
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout gate, workspace, pairForm, linkRow;
    private EditText address, code;
    private TextView chip, verified, total, transfer, note, checked, files, linkText, pairingStatus;
    private Bar bar;
    private Button photos, pairButton;
    private TextView linkAction, newPc;
    private SharedPreferences prefs;
    private boolean pairing, wantPhotos, formatting, showPairForm;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            update();
            main.postDelayed(this,1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs=getSharedPreferences("job",MODE_PRIVATE);
        wantPhotos=TransferService.ACTION_PHOTOS.equals(getIntent().getAction());
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(PAPER);
        LinearLayout page=column();
        page.setPadding(dp(20),dp(18),dp(20),dp(28));
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout header=row();
        ImageView icon=new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        header.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView name=text(header,"오토픽셀",22,INK);
        name.setTypeface(MEDIUM);
        name.setPadding(dp(10),0,0,0);
        name.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        chip=text(header,"",13,GRAY);
        chip.setTypeface(MEDIUM);
        chip.setPadding(dp(11),dp(5),dp(12),dp(5));
        page.addView(header);

        gate=card(page);
        title(gate,"접근성 서비스를 켜 주세요");
        text(gate,"구글 포토에서 영상별 백업 상태를 읽으려면 오토픽셀 접근성 서비스가 필요해요.",15,MUTED).setPadding(0,dp(6),0,dp(14));
        primary(gate,"접근성 설정 열기",v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        workspace=column();
        page.addView(workspace);

        LinearLayout status=card(workspace);
        text(status,"원본 백업 확인",14,MUTED);
        LinearLayout numbers=row();
        numbers.setGravity(Gravity.BOTTOM);
        verified=text(numbers,"0",46,INK);
        verified.setTypeface(MEDIUM);
        verified.setIncludeFontPadding(false);
        total=text(numbers," / 0개",20,MUTED);
        total.setPadding(dp(4),0,0,dp(6));
        status.addView(numbers);
        bar=new Bar(this);
        LinearLayout.LayoutParams barLayout=new LinearLayout.LayoutParams(-1,dp(8));
        barLayout.topMargin=dp(12);
        barLayout.bottomMargin=dp(12);
        status.addView(bar,barLayout);
        transfer=text(status,"",14,MUTED);
        note=text(status,"",15,INK);
        note.setPadding(0,dp(6),0,0);
        checked=text(status,"",13,GRAY);
        checked.setPadding(0,dp(2),0,dp(14));
        photos=primary(status,"구글 포토에서 백업 확인",v->openPhotos());

        LinearLayout received=card(workspace);
        received.setOrientation(LinearLayout.HORIZONTAL);
        received.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout receivedText=column();
        files=text(receivedText,"받은 영상",16,INK);
        files.setTypeface(MEDIUM);
        text(receivedText,"내부 저장소 · "+TransferService.DIRECTORY,13,MUTED);
        received.addView(receivedText,new LinearLayout.LayoutParams(0,-2,1));
        text(received,"›",26,GRAY);
        pressable(received);
        received.setOnClickListener(v->showFiles());

        LinearLayout connection=card(workspace);
        linkRow=row();
        linkRow.setGravity(Gravity.CENTER_VERTICAL);
        linkText=text(linkRow,"",15,INK);
        linkText.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        linkAction=link(linkRow,"",v->toggleLink());
        connection.addView(linkRow);
        LinearLayout more=row();
        newPc=link(more,"다른 PC와 연결",v->{ showPairForm=true; update(); });
        newPc.setTextColor(MUTED);
        newPc.setTextSize(13);
        newPc.setPadding(0,dp(6),dp(8),0);
        connection.addView(more);

        pairForm=column();
        connection.addView(pairForm);
        title(pairForm,"PC와 연결");
        text(pairForm,"PC 오토픽셀에서 ‘새 연결 코드’를 누르고 코드를 입력하세요.",14,MUTED).setPadding(0,dp(4),0,dp(8));
        Switch usb=new Switch(this);
        usb.setText("USB 케이블로 연결");
        usb.setTextColor(INK);
        usb.setTextSize(15);
        usb.setChecked(prefs.getBoolean("pairUSB",false));
        pairForm.addView(usb,new LinearLayout.LayoutParams(-1,dp(44)));
        address=input(pairForm,"PC 주소 (예: 192.168.0.24)");
        address.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fillAddress(usb.isChecked());
        usb.setOnCheckedChangeListener((view,value)->fillAddress(value));
        code=input(pairForm,"연결 코드 (예: ABC-32F)");
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
        pairingStatus=text(pairForm,"",14,MUTED);
        pairingStatus.setPadding(0,dp(6),0,dp(8));
        pairButton=primary(pairForm,"연결하기",v->pair(usb.isChecked()));

        TextView diagnostics=text(page,"진단 정보",13,GRAY);
        diagnostics.setGravity(Gravity.CENTER);
        diagnostics.setPadding(0,dp(20),0,dp(8));
        diagnostics.setOnClickListener(v->showDiagnostics());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        wantPhotos=TransferService.ACTION_PHOTOS.equals(intent.getAction());
    }
    @Override protected void onResume() { super.onResume(); main.removeCallbacks(refresh); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }

    private void update() {
        boolean ready=PhotosInspector.ready();
        gate.setVisibility(ready?View.GONE:View.VISIBLE);
        workspace.setVisibility(ready?View.VISIBLE:View.GONE);
        boolean paired=!prefs.getString("pair","").isEmpty();
        boolean stopped=prefs.getBoolean("stopped",false);
        TransferService service=TransferService.running;
        if (ready && paired && !stopped && service==null && !pairing) startTransfer();

        if (service!=null) {
            String state=service.connectionText();
            setChip(state,"PC 연결됨".equals(state)?GREEN:"PC 응답 지연".equals(state)?AMBER:GRAY);
            TransferService.Summary s=service.summary();
            verified.setText(String.format(Locale.KOREA,"%,d",s.verified));
            total.setText(String.format(Locale.KOREA," / %,d개",s.total));
            bar.set(s.total==0?0f:(float)s.verified/s.total);
            transfer.setText(s.batchCount>0?String.format(Locale.KOREA,"PC → 픽셀 전송 %,d / %,d개 · %.2f / %.2f GB",
                    s.copied,s.batchCount,s.copiedBytes/1e9,s.batchBytes/1e9):"진행 중인 전송 묶음 없음");
            note.setText(friendly(service.status()));
            checked.setText(service.lastChecked());
            int count=service.received().size();
            files.setText(count==0?"받은 영상 없음":"받은 영상 "+count+"개");
            String prompt=service.consumePrompt();
            if (ready && wantPhotos) {
                wantPhotos=false;
                openPhotos();
            } else if (ready && !prompt.isEmpty()) {
                new AlertDialog.Builder(this).setTitle("구글 포토를 열까요?")
                        .setMessage("PC에서 전송을 시작했어요. 포토를 열면 원본 백업 확인을 시작해요.")
                        .setPositiveButton("구글 포토 열기",(d,w)->openPhotos())
                        .setNegativeButton("나중에",null).show();
            }
        } else {
            setChip(!paired?"연결 필요":stopped?"연결 멈춤":"연결 중…",GRAY);
            verified.setText("0");
            total.setText(" / 0개");
            bar.set(0f);
            transfer.setText("");
            note.setText(paired?"":"PC와 연결하면 진행 상황이 여기에 표시돼요.");
            checked.setText("");
            files.setText("받은 영상");
            if (ready && wantPhotos) { wantPhotos=false; openPhotos(); }
        }
        photos.setEnabled(service!=null);
        photos.setAlpha(service!=null?1f:.45f);

        boolean form=!paired || showPairForm;
        pairForm.setVisibility(form?View.VISIBLE:View.GONE);
        linkRow.setVisibility(paired?View.VISIBLE:View.GONE);
        newPc.setVisibility(paired && !form?View.VISIBLE:View.GONE);
        if (paired) {
            String how=prefs.getBoolean("pairUSB",false)?"USB":"Wi-Fi";
            linkText.setText(service!=null?how+"로 PC와 연결":"PC 연결을 멈췄어요");
            linkAction.setText(service!=null?"연결 해제":"다시 연결");
        }
    }

    private void setChip(String text,int color) {
        chip.setText("● "+text);
        chip.setTextColor(color);
        GradientDrawable background=new GradientDrawable();
        background.setColor((color&0x00ffffff)|0x1f000000);
        background.setCornerRadius(dp(14));
        chip.setBackground(background);
    }

    private void toggleLink() {
        if (TransferService.running!=null) {
            prefs.edit().putBoolean("stopped",true).apply();
            PhotosInspector inspector=PhotosInspector.active;
            if(inspector!=null)inspector.pauseInspection();
            stopService(new Intent(this,TransferService.class));
        } else {
            prefs.edit().putBoolean("stopped",false).apply();
            startTransfer();
        }
        main.postDelayed(this::update,300);
    }

    private void showDiagnostics() {
        PhotosInspector inspector=PhotosInspector.active;
        TransferService service=TransferService.running;
        String detail=inspector==null?"접근성 서비스 대기 중":inspector.diagnostic();
        if(service!=null)detail=service.status()+"\n"+service.lastChecked()+"\n"
                +service.verificationDiagnostic()+"\n\n"+detail;
        final String diagnostic=detail;
        new AlertDialog.Builder(this).setTitle("진단 정보").setMessage(diagnostic)
                .setNeutralButton("복사",(dialog,which)->getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("오토픽셀 · 진단",diagnostic)))
                .setPositiveButton("닫기",null).show();
    }

    private int dp(int value) { return (int)(getResources().getDisplayMetrics().density*value+.5f); }
    private LinearLayout column() {
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() {
        LinearLayout layout=new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    private GradientDrawable rounded(int color,int radius) {
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box=column();
        box.setPadding(dp(18),dp(16),dp(18),dp(16));
        GradientDrawable background=rounded(WHITE,20);
        background.setStroke(dp(1),LINE);
        box.setBackground(background);
        LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);
        layout.topMargin=dp(14);
        parent.addView(box,layout);
        return box;
    }
    private void pressable(View view) {
        GradientDrawable background=rounded(WHITE,20);
        background.setStroke(dp(1),LINE);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(TINT),background,rounded(WHITE,20)));
        view.setClickable(true);
    }
    private TextView title(LinearLayout owner,String value) {
        TextView view=text(owner,value,18,INK);
        view.setTypeface(MEDIUM);
        return view;
    }
    private TextView text(LinearLayout owner,String value,int size,int color) {
        TextView view=new TextView(this);
        view.setText(value);
        view.setTextColor(color);
        view.setTextSize(size);
        owner.addView(view);
        return view;
    }
    private EditText input(LinearLayout owner,String hint) {
        EditText view=new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        view.setTextSize(17);
        owner.addView(view,new LinearLayout.LayoutParams(-1,-2));
        return view;
    }
    private Button primary(LinearLayout owner,String label,View.OnClickListener action) {
        Button view=new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setTextSize(16);
        view.setTypeface(MEDIUM);
        view.setTextColor(WHITE);
        view.setStateListAnimator(null);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33ffffff),rounded(NAVY,14),null));
        view.setOnClickListener(action);
        owner.addView(view,new LinearLayout.LayoutParams(-1,dp(52)));
        return view;
    }
    private TextView link(LinearLayout owner,String label,View.OnClickListener action) {
        TextView view=text(owner,label,15,0xff2f5bd3);
        view.setTypeface(MEDIUM);
        view.setPadding(dp(8),dp(8),dp(8),dp(8));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(TINT),null,rounded(WHITE,10)));
        view.setOnClickListener(action);
        return view;
    }

    /** Short, plain wording for the status line; the raw text stays in the diagnostics. */
    private static String friendly(String status) {
        if (status.startsWith("PC 통신 대기")) return "PC의 오토픽셀이 꺼져 있거나 연결이 끊겼어요.";
        return status;
    }

    static final class Bar extends View {
        private final Paint track=new Paint(Paint.ANTI_ALIAS_FLAG), fill=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float value;
        Bar(Context context) {
            super(context);
            track.setColor(0xffe6ebf2);
            fill.setColor(GREEN);
        }
        void set(float next) {
            next=Math.max(0f,Math.min(1f,next));
            if (next!=value) { value=next; invalidate(); }
        }
        @Override protected void onDraw(Canvas canvas) {
            float h=getHeight(), r=h/2f;
            canvas.drawRoundRect(0,0,getWidth(),h,r,r,track);
            if (value>0) canvas.drawRoundRect(0,0,Math.max(h,getWidth()*value),h,r,r,fill);
        }
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
        List<TransferService.Received> received=service.received();
        if(received.isEmpty())return TransferService.DIRECTORY;
        for(TransferService.Received file:received) if(TransferService.DIRECTORY.equals(file.path)) return TransferService.DIRECTORY;
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
        List<TransferService.Received> received=service==null?java.util.Collections.emptyList():service.received();
        AlertDialog.Builder dialog=new AlertDialog.Builder(this).setNeutralButton("폴더 열기",(d,w)->openFolder())
                .setNegativeButton("닫기",null);
        if(received.isEmpty()) {
            dialog.setTitle("받은 영상").setMessage(service==null?"PC에 연결하면 받은 영상이 표시돼요.":"이 묶음에서 받은 영상이 아직 없어요.").show();
            return;
        }
        String[] names=new String[received.size()];
        for(int i=0;i<names.length;i++){
            TransferService.Received file=received.get(i);
            names[i]=(file.stage>=3?"정리됨  ":file.stage==2?"✓ 확인  ":"· 복사됨  ")+file.name;
        }
        dialog.setTitle("받은 영상 "+received.size()+"개").setItems(names,(d,index)->{
            TransferService.Received file=received.get(index);
            if(file.stage<3)openVideo(file.uri);
            else toast("픽셀 복사본 정리가 끝났어요.");
        }).show();
    }

    private void pair(boolean usb) {
        if(pairing || !PhotosInspector.ready()) return;
        String pc=address.getText().toString().trim(), entered=code.getText().toString();
        pairing=true;
        pairButton.setEnabled(false);
        pairingStatus.setText("PC에 연결 중…");
        new Thread(()->{
            try {
                String id=Pairing.device(prefs), trusted=Pairing.join(pc,entered,id);
                if(!prefs.edit().putString("pair",trusted).putBoolean("pairUSB",usb).putBoolean("stopped",false)
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
        if(error!=null) { pairingStatus.setText(error); return; }
        code.setText("");
        pairingStatus.setText("");
        showPairForm=false;
        toast("PC와 연결했어요. PC에서 전송 시작을 눌러 주세요.");
        startTransfer();
        update();
    }
}
