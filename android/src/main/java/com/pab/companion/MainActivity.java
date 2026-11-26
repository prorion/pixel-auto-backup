package com.pab.companion;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.*;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private EditText address, code;
    private TextView status, diagnostic, pairingStatus;
    private Button pairButton, resume;
    private SharedPreferences prefs;
    private boolean pairing;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            TransferService s = TransferService.running;
            PhotosInspector inspector = PhotosInspector.active;
            status.setText(s == null ? "PC 연결 대기 중" : s.status()
                    + (s.nextName().isEmpty() ? "" : "\n다음 확인 대상 · " + s.nextName()));
            diagnostic.setText(inspector == null ? "접근성 서비스 꺼짐" : inspector.diagnostic());
            main.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("job", MODE_PRIVATE);
        ScrollView scroll = new ScrollView(this);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(20 * getResources().getDisplayMetrics().density);
        page.setPadding(pad,pad,pad,pad);
        scroll.addView(page);
        setContentView(scroll);
        TextView title = text("오토픽셀", 27);
        title.setTypeface(null, Typeface.BOLD);
        status = text("PC 연결 대기 중", 16);
        text("PC에서 새 연결 코드를 만든 뒤 입력하세요.", 15);

        Switch usb = new Switch(this);
        usb.setText("USB로 연결");
        usb.setChecked(prefs.getBoolean("pairUSB",false));
        page.addView(usb);
        address = input("PC 주소 (예: 192.168.0.24)");
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        address.setText(prefs.getString(usb.isChecked() ? "usbAddress" : "wifiAddress", usb.isChecked() ? "127.0.0.1" : ""));
        usb.setOnCheckedChangeListener((button, checked) -> {
            address.setText(prefs.getString(checked ? "usbAddress" : "wifiAddress", checked ? "127.0.0.1" : ""));
        });
        code = input("연결 코드 (예: 7K2M-4H9P-8TWX)");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setFilters(new InputFilter[]{new InputFilter.AllCaps(),new InputFilter.LengthFilter(24)});
        pairingStatus = text("", 14);
        pairButton = button("연결하기", v -> pair(usb.isChecked()));
        resume = button("기존 연결 재개", v -> startTransfer());
        resume.setEnabled(!prefs.getString("pair","").isEmpty());

        button("전송 일시 중지", v -> {
            PhotosInspector inspector = PhotosInspector.active;
            if (inspector != null) inspector.pauseInspection();
            stopService(new Intent(this,TransferService.class));
        });
        text("구글 포토 확인", 20).setTypeface(null, Typeface.BOLD);
        diagnostic = text("", 14);
        button("접근성 서비스 설정", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button("구글 포토 열기", v -> {
            Intent open = getPackageManager().getLaunchIntentForPackage(TransferService.PHOTOS);
            if (open != null) startActivity(open);
            else toast("구글 포토 앱을 찾을 수 없습니다.");
        });
        button("다음 영상부터 연속 확인", v -> {
            PhotosInspector inspector = PhotosInspector.active;
            if (inspector == null) toast("접근성 서비스를 먼저 켜 주세요.");
            else if (!inspector.startInspection()) toast("백업 설정에서 계정을 확인하고 대상 영상의 세부정보를 열어 주세요.");
        });
        button("화면 자동 확인 중지", v -> {
            PhotosInspector inspector = PhotosInspector.active;
            if (inspector != null) inspector.pauseInspection();
        });
        text("구글 포토에서 PixelAutoBackup 폴더 백업을 켜고 백업 설정의 계정 화면을 여세요. "
                + "세부정보가 자동으로 열리지 않으면 대상 영상에서 위로 쓸어올리세요.", 14);
    }
    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }
    private TextView text(String content, int size) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setPadding(0,8,0,8);
        page.addView(view);
        return view;
    }
    private EditText input(String hint) {
        EditText view = new EditText(this);
        view.setHint(hint);
        view.setSingleLine(true);
        page.addView(view);
        return view;
    }
    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        page.addView(button);
        return button;
    }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void startTransfer() {
        startForegroundService(new Intent(this,TransferService.class));
    }
    private void pair(boolean usb) {
        if (pairing) return;
        String pc = address.getText().toString().trim();
        String input = code.getText().toString();
        pairing = true;
        pairButton.setEnabled(false);
        resume.setEnabled(false);
        pairingStatus.setText("PC에 연결 중…");
        new Thread(() -> {
            try {
                String id = Pairing.device(prefs);
                String trusted = Pairing.join(pc,input,id);
                if (!prefs.edit().putString("pair",trusted).putBoolean("pairUSB",usb)
                        .putString(usb ? "usbAddress" : "wifiAddress",pc).commit()) {
                    throw new IllegalStateException("연결 정보를 저장할 수 없습니다.");
                }
                main.post(() -> finishPair(null));
            } catch (Exception error) {
                main.post(() -> finishPair(error.getMessage() == null ? "PC 연결을 확인해 주세요." : error.getMessage()));
            }
        }, "autopixel-pair").start();
    }
    private void finishPair(String error) {
        if (isFinishing() || isDestroyed()) return;
        pairing = false;
        pairButton.setEnabled(true);
        resume.setEnabled(!prefs.getString("pair","").isEmpty());
        if (error != null) {
            pairingStatus.setText(error);
            return;
        }
        code.setText("");
        pairingStatus.setText("PC 연결 정보를 저장했어요.");
        startTransfer();
    }
}
