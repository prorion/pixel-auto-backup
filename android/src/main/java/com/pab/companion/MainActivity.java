package com.pab.companion;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private EditText code;
    private TextView status, diagnostic;
    private SharedPreferences prefs;
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
        TextView title = text("Pixel Auto Backup", 27);
        title.setTypeface(null, Typeface.BOLD);
        status = text("PC 연결 대기 중", 16);
        text("PC에 표시된 코드를 붙여넣거나 페어링 파일을 선택하세요.", 15);
        code = new EditText(this);
        code.setSingleLine(true);
        code.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        code.setHint("페어링 코드");
        page.addView(code);
        button("PC 연결 / 재개", v -> connect());
        button("페어링 파일 열기", v -> selectPairFile());
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
    private void button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setOnClickListener(action);
        page.addView(button);
    }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void connect() {
        String entered = code.getText().toString().trim();
        String pairing = entered.isEmpty() ? prefs.getString("pair", "") : entered;
        try {
            new Link(pairing, "validation-only");
            if (!prefs.edit().putString("pair",pairing).commit()) throw new IllegalStateException("연결 정보를 저장할 수 없습니다.");
            code.setText("");
            startForegroundService(new Intent(this,TransferService.class));
        } catch (Exception error) {
            toast(error.getMessage()==null ? "페어링 코드를 확인해 주세요." : error.getMessage());
        }
    }
    private void selectPairFile() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("text/plain").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(pick,11);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request,result,data);
        if (request != 11 || result != RESULT_OK || data == null || data.getData() == null) return;
        try (InputStream in = getContentResolver().openInputStream(data.getData())) {
            if (in == null) throw new IllegalStateException();
            code.setText(new String(Link.read(in,2048), StandardCharsets.UTF_8).trim());
            connect();
        } catch (Exception error) { toast("페어링 파일을 읽을 수 없습니다."); }
    }
}
