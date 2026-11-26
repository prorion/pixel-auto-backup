package com.pab.companion;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class TransferService extends Service {
    static final String PHOTOS = "com.google.android.apps.photos";
    static final String DIRECTORY = "Movies/PixelAutoBackup/";
    private static final Uri VIDEOS = MediaStore.Video.Media.getContentUri("external_primary");
    private static final String COL_NAME = MediaStore.MediaColumns.DISPLAY_NAME;
    private static final String COL_PATH = MediaStore.MediaColumns.RELATIVE_PATH;
    private static final String COL_PENDING = MediaStore.MediaColumns.IS_PENDING;
    static volatile TransferService running;
    private final ScheduledExecutorService syncThread = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService ioThread = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean changed = new AtomicBoolean(true);
    private volatile Link link;
    private volatile Work work;
    private volatile String message = "PC 연결 대기 중";
    private volatile String network = "";
    private volatile String current = "";
    private volatile long bytes;
    private volatile long accountUntil;
    private volatile String confirmedAccount = "";
    private volatile boolean pendingReported;
    private long last;
    private int interval = 15;
    private SharedPreferences prefs;

    static final class Clip {
        final String id, name;
        final long size;
        Clip(JSONObject node) throws JSONException {
            id = node.getString("id");
            name = node.getString("name");
            size = node.getLong("size");
            if (!id.matches("[a-f0-9]{64}") || name.contains("/") || name.contains("\\")
                    || size <= 0 || size > 10_000_000_000L) throw new JSONException("영상 정보 오류");
        }
    }
    static final class Work {
        final int batch;
        final boolean cleanup;
        final long reserve;
        final String account, namespace;
        final List<Clip> clips = new ArrayList<>();
        Work(JSONObject response, Link link) throws JSONException {
            JSONObject active = response.getJSONObject("active");
            batch = active.getInt("id");
            cleanup = active.getBoolean("cleanup");
            reserve = Math.max(0, response.getLong("reserve"));
            account = response.getString("account");
            namespace = link.namespace;
            JSONArray files = response.getJSONArray("videos");
            for (int i = 0; i < files.length(); i++) clips.add(new Clip(files.getJSONObject(i)));
        }
        String key(Clip clip) { return namespace + "-" + batch + "-" + clip.id; }
    }

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("job", MODE_PRIVATE);
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("transfer", "백업 진행", NotificationManager.IMPORTANCE_LOW));
        running = this;
        syncThread.scheduleWithFixedDelay(this::checkIn, 1, 1, TimeUnit.SECONDS);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        startForeground(125, new Notification.Builder(this, "transfer")
                .setContentTitle("오토픽셀").setContentText("PC와 백업 상태 동기화 중")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(pending).build());
        try {
            String token = Pairing.device(prefs);
            Link next = new Link(prefs.getString("pair", ""), token);
            if (link != null && !link.namespace.equals(next.namespace)) {
                confirmedAccount = "";
                work = null;
            }
            link = next;
            changed.set(true);
        } catch (Exception error) {
            setMessage(safeMessage(error));
            link = null;
        }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() {
        running = null;
        syncThread.shutdownNow();
        ioThread.shutdownNow();
        super.onDestroy();
    }
    private static String safeMessage(Exception error) {
        String detail = error.getMessage();
        return detail == null ? error.getClass().getSimpleName() : detail;
    }
    static boolean unlocked(Context context) {
        return context.getSystemService(PowerManager.class).isInteractive()
                && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    boolean accountOK(Work w) {
        return w != null && !w.account.isEmpty() && confirmedAccount.equalsIgnoreCase(w.account)
                && SystemClock.elapsedRealtime() < accountUntil;
    }
    private long free() {
        File external = getExternalFilesDir(null);
        if (external == null) return 0;
        return new StatFs(external.getAbsolutePath()).getAvailableBytes();
    }
    private void setMessage(String next) {
        if (!message.equals(next)) {
            message = next;
            changed.set(true);
        }
    }
    String status() {
        String extra = network.isEmpty() ? "" : "\n" + network;
        return message + extra;
    }
    boolean readyToInspect() { return accountOK(work); }
    String expectedAccount() { Work w = work; return w == null ? "" : w.account; }
    void confirmAccount(String account) {
        Work w = work;
        if (w != null && w.account.equalsIgnoreCase(account)) {
            confirmedAccount = w.account;
            accountUntil = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(30);
            setMessage("백업 계정 확인 · 구글 포토에서 이번 묶음 영상의 세부정보를 열어 주세요.");
        }
    }
    void clearAccountProof() {
        confirmedAccount = "";
        accountUntil = 0;
    }
    private int stage(Work w, Clip c) { return prefs.getInt(w.key(c) + ".stage", 0); }
    private void stage(Work w, Clip c, int value) throws IOException {
        if (!prefs.edit().putInt(w.key(c) + ".stage", value).commit()) {
            throw new IOException("파일 진행 기록을 저장할 수 없습니다.");
        }
        changed.set(true);
    }
    private Uri uri(Work w, Clip c) {
        String value = prefs.getString(w.key(c) + ".uri", "");
        if (!value.matches("content://media/external_primary/video/media/[0-9]+")) return null;
        return Uri.parse(value);
    }
    Clip nextUnverified() {
        Work w = work;
        if (w != null) for (Clip c : w.clips) if (stage(w,c) == 1) return c;
        return null;
    }
    Uri nextUri() {
        Work w = work;
        Clip c = nextUnverified();
        return w == null || c == null ? null : uri(w,c);
    }
    String nextName() {
        Clip c = nextUnverified();
        return c == null ? "" : c.name;
    }
    boolean acceptsName(String name) {
        Work w = work;
        if (w == null) return false;
        for (Clip c : w.clips) if (stage(w,c)==1 && c.name.equals(name)) return true;
        return false;
    }
    boolean verify(String name) {
        Work w = work;
        if (!accountOK(w) || !unlocked(this)) return false;
        for (Clip c : w.clips) {
            if (stage(w,c) != 1 || !c.name.equals(name)) continue;
            try {
                if (!sameOwnedItem(w,c,uri(w,c),false)) return false;
                stage(w,c,2);
                setMessage("원본 백업 확인 · " + c.name);
                return true;
            } catch (Exception error) {
                setMessage(safeMessage(error));
                return false;
            }
        }
        return false;
    }
    private JSONObject makeReport(Work w) throws JSONException {
        JSONObject report = new JSONObject();
        JSONObject receipts = new JSONObject();
        if (w != null) for (Clip c : w.clips) {
            int stage = stage(w,c);
            if (stage > 0) receipts.put(c.id,stage);
        }
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return report.put("batch",w==null?0:w.batch).put("files",receipts)
                .put("current",current).put("bytes",bytes).put("free",free())
                .put("locked",!unlocked(this)).put("charging",battery != null
                        && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) != 0)
                .put("message",message);
    }
    private void checkIn() {
        Link activeLink = link;
        if (activeLink == null) return;
        long now = SystemClock.elapsedRealtime();
        if (now-last < interval*1000L && (!changed.get() || now-last < 2000)) return;
        last = now;
        changed.set(false);
        try {
            Work previous = work;
            JSONObject response = activeLink.sync(makeReport(previous));
            if (activeLink != link) return;
            interval = Math.max(5, Math.min(120,response.optInt("interval",15)));
            network = "";
            if (response.isNull("active")) {
                work = null;
                if (!busy.get()) {
                    String reason=response.optString("message");
                    setMessage(reason.isEmpty() ? "PC 연결됨 · 작업 대기 중" : reason);
                }
                return;
            }
            Work next = new Work(response,activeLink);
            work = next;
            if (!busy.compareAndSet(false,true)) return;
            ioThread.execute(() -> process(activeLink,next));
        } catch (Exception error) {
            network = "PC 통신 대기 · " + safeMessage(error);
        }
    }
    private void process(Link activeLink, Work w) {
        try {
            if (w.cleanup) {
                if (!accountOK(w)) {
                    setMessage("정리 전 대기 · 구글 포토의 백업 설정에서 계정을 다시 확인해 주세요.");
                    return;
                }
                for (Clip c : w.clips) {
                    if (activeLink != link || Thread.currentThread().isInterrupted()) return;
                    if (stage(w,c) == 3) continue;
                    if (stage(w,c) != 2) throw new IOException("기기에 확인 기록이 없어 정리를 보류했습니다.");
                    remove(w,c);
                }
                setMessage("픽셀 복사본 정리 완료");
                return;
            }
            boolean allVerified = true;
            for (Clip c : w.clips) {
                if (activeLink != link || Thread.currentThread().isInterrupted()) return;
                if (stage(w,c) < 1) {
                    setMessage("전송 중 · " + c.name);
                    copy(activeLink,w,c);
                }
                allVerified &= stage(w,c) >= 2;
            }
            if (allVerified) setMessage("묶음 백업 확인 완료 · 정리 대기");
            else if (!unlocked(this)) setMessage("화면 잠금 · 잠금 해제 후 백업 확인");
            else if (!accountOK(w)) setMessage("구글 포토의 백업 설정에서 계정을 확인해 주세요.");
            else setMessage("구글 포토에서 백업 및 영상 세부정보 확인 대기");
        } catch (Exception error) {
            setMessage(safeMessage(error));
        } finally {
            current = "";
            bytes = 0;
            busy.set(false);
        }
    }
    private boolean sameOwnedItem(Work w, Clip c, Uri target, boolean allowPending) {
        if (target == null || !target.equals(uri(w,c))) return false;
        try (Cursor cursor = getContentResolver().query(MediaStore.setIncludePending(target),
                new String[]{COL_NAME,COL_PATH,COL_PENDING},null,null,null)) {
            return cursor != null && cursor.moveToFirst() && c.name.equals(cursor.getString(0))
                    && DIRECTORY.equals(cursor.getString(1)) && (allowPending || cursor.getInt(2) == 0);
        }
    }
    private Uri prepare(Work w, Clip c) throws IOException {
        Uri target = uri(w,c);
        if (target != null) {
            if (!sameOwnedItem(w,c,target,true)) throw new IOException("전송 기록의 영상 위치가 바뀌었습니다.");
            return target;
        }
        // Only this scoped-storage app’s own rows are visible without a media read permission.
        try (Cursor existing = getContentResolver().query(MediaStore.setIncludePending(VIDEOS),
                new String[]{MediaStore.MediaColumns._ID,COL_PENDING},
                COL_NAME + "=? AND " + COL_PATH + "=?",new String[]{c.name,DIRECTORY},null)) {
            if (existing != null && existing.moveToFirst()) {
                if (existing.getInt(1) != 1) throw new IOException("같은 이름의 기기 파일이 있어 전송을 보류했습니다.");
                target = ContentUris.withAppendedId(VIDEOS,existing.getLong(0));
                if (existing.moveToNext()) throw new IOException("같은 이름의 기기 파일이 여러 개입니다.");
            }
        }
        if (target == null) {
            ContentValues values = new ContentValues();
            values.put(COL_NAME,c.name);
            values.put(COL_PATH,DIRECTORY);
            String ext = c.name.substring(c.name.lastIndexOf('.')+1);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime != null && mime.startsWith("video/") ? mime : "video/*");
            values.put(COL_PENDING,1);
            target = getContentResolver().insert(VIDEOS,values);
            if (target == null) throw new IOException("픽셀에서 영상 파일을 만들 수 없습니다.");
        }
        if (!prefs.edit().putString(w.key(c)+".uri",target.toString()).commit()) {
            throw new IOException("기기 미디어 ID를 저장할 수 없습니다.");
        }
        if (!sameOwnedItem(w,c,target,true)) throw new IOException("픽셀 영상 이름 또는 폴더가 다릅니다.");
        return target;
    }
    private void copy(Link activeLink, Work w, Clip c) throws Exception {
        Uri target = prepare(w,c);
        current = c.id;
        String hash;
        ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(target,"rw");
        if (pfd == null) throw new IOException("기기 파일을 열 수 없습니다.");
        try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pfd)) {
            FileChannel channel = output.getChannel();
            long offset = channel.size();
            if (offset > c.size) { channel.truncate(0); offset=0; }
            if (c.size - offset > free()-w.reserve) throw new IOException("픽셀 공간 부족 · 설정 또는 저장공간 확인");
            bytes=offset;
            changed.set(true);
            HttpsURLConnection connection = activeLink.connect("/v1/file/"+c.id);
            try {
                if (offset==c.size) connection.setRequestMethod("HEAD");
                else if (offset>0) connection.setRequestProperty("Range","bytes="+offset+"-");
                int status=connection.getResponseCode();
                hash=connection.getHeaderField("X-File-SHA256");
                if (hash==null || !hash.matches("[a-f0-9]{64}")) throw new IOException("원본을 읽을 수 없습니다 · PC 응답 "+status);
                if (offset<c.size) {
                    if (offset==0 && status!=200 || offset>0 && (status!=206 ||
                            !("bytes "+offset+"-"+(c.size-1)+"/"+c.size).equals(connection.getHeaderField("Content-Range")))){
                        throw new IOException("PC 전송 범위 응답 오류");
                    }
                    channel.position(offset);
                    try (InputStream input=connection.getInputStream()) {
                        byte[] buffer=new byte[256*1024];
                        int count;
                        while ((count=input.read(buffer))!=-1) {
                            if (activeLink!=link || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("전송 일시 중지");
                            if (count>c.size-bytes) throw new IOException("전송 바이트가 원본 크기를 넘었습니다.");
                            ByteBuffer data=ByteBuffer.wrap(buffer,0,count);
                            while(data.hasRemaining())channel.write(data);
                            bytes+=count;
                        }
                    }
                    channel.force(true);
                } else if (status != 200) {
                    throw new IOException("원본 검증에 필요한 PC 응답을 받지 못했습니다.");
                }
            } finally { connection.disconnect(); }
        }
        if (bytes != c.size) throw new IOException("전송이 중단되었습니다. 다음 연결에서 이어받습니다.");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long length=0;
        try (InputStream input=getContentResolver().openInputStream(target)) {
            if (input==null) throw new IOException("픽셀 파일을 다시 읽을 수 없습니다.");
            byte[] buffer=new byte[256*1024];
            int count;
            while ((count=input.read(buffer))!=-1) {
                digest.update(buffer,0,count);
                length+=count;
            }
        }
        if (length!=c.size || !hash.equals(Link.hex(digest.digest()))) {
            try (ParcelFileDescriptor file=getContentResolver().openFileDescriptor(target,"rwt")) {
                if (file==null) throw new IOException("해시 불일치 · 손상된 전송을 초기화할 수 없습니다.");
            }
            throw new IOException("해시 불일치 · 처음부터 다시 받습니다.");
        }
        ContentValues values = new ContentValues();
        values.put(COL_PENDING,0);
        if (!sameOwnedItem(w,c,target,false) &&
                (getContentResolver().update(target,values,null,null)!=1 || !sameOwnedItem(w,c,target,false))) {
            throw new IOException("영상 공개 상태를 확인할 수 없습니다.");
        }
        stage(w,c,1);
        current="";
        bytes=0;
    }
    private void remove(Work w, Clip c) throws IOException {
        Uri target=uri(w,c);
        if (target==null) throw new IOException("정리할 픽셀 파일 ID가 없습니다.");
        boolean wasAuthorized=prefs.getBoolean(w.key(c)+".deleteAuthorized",false);
        if (sameOwnedItem(w,c,target,false)) {
            if (!prefs.edit().putBoolean(w.key(c)+".deleteAuthorized",true).commit())
                throw new IOException("정리 시작 상태를 저장할 수 없습니다.");
            if (getContentResolver().delete(target,null,null)!=1) throw new IOException("기기 복사본 정리에 실패했습니다.");
        } else {
            if (!wasAuthorized) throw new IOException("정리 대상의 파일명 또는 위치가 달라 보류했습니다.");
            try (Cursor exists=getContentResolver().query(target,new String[]{COL_NAME},null,null,null)) {
                if (exists==null || exists.moveToFirst()) throw new IOException("정리 대상의 상태를 확인할 수 없습니다.");
            }
        }
        stage(w,c,3);
    }
}
