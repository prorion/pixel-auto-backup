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
    static final String DIRECTORY = "DCIM/Camera/";
    static final String LEGACY_DIRECTORY = "Movies/PixelAutoBackup/";
    static final String ACTION_PHOTOS = "com.pab.companion.OPEN_PHOTOS";
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
    private volatile Work lastWork;
    private volatile boolean pcRunning;
    private volatile long lastConnected;
    private volatile JSONObject totals = new JSONObject();
    private volatile String prompt = "";
    private volatile String inspectionHint = "";
    private String notificationText = "";
    private long last;
    private volatile int interval = 15;
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
        manager.createNotificationChannel(new NotificationChannel("photos", "구글 포토 백업 안내", NotificationManager.IMPORTANCE_HIGH));
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
            if (!PhotosInspector.ready()) {
                stopSelf();
                return START_NOT_STICKY;
            }
            String token = Pairing.device(prefs);
            Link next = new Link(prefs.getString("pair", ""), token);
            if (link != null && !link.namespace.equals(next.namespace)) {
                confirmedAccount = "";
                work = null;
                lastWork = null;
                lastConnected = 0;
                totals = new JSONObject();
                pcRunning = false;
                prompt = "";
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
        if (running == this) running = null;
        link = null;
        getSystemService(NotificationManager.class).cancel(126);
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
    void accessibilityChanged() { changed.set(true); }

    private void requestPhotos(Link active, String id) {
        if (id.isEmpty() || id.equals(prefs.getString(active.namespace + ".lastPrompt",""))) return;
        if (!prefs.edit().putString(active.namespace + ".lastPrompt",id).commit()) return;
        prompt = id;
        Intent open = new Intent(this,MainActivity.class).setAction(ACTION_PHOTOS)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pending = PendingIntent.getActivity(this,126,open,PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        getSystemService(NotificationManager.class).notify(126,new Notification.Builder(this,"photos")
                .setContentTitle("오토픽셀 · 구글 포토 열기")
                .setContentText("PC에서 전송을 시작했어요. 탭하면 백업 확인을 시작해요.")
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(pending).setAutoCancel(true).build());
    }

    String consumePrompt() {
        String value = prompt;
        prompt = "";
        if (!value.isEmpty()) getSystemService(NotificationManager.class).cancel(126);
        return value;
    }

    private String storagePath(Work w, Clip c) {
        String fallback = uri(w,c) == null ? DIRECTORY : LEGACY_DIRECTORY;
        String path = prefs.getString(w.key(c) + ".path",fallback);
        return DIRECTORY.equals(path) ? DIRECTORY : LEGACY_DIRECTORY;
    }

    static final class Received {
        final String name, path;
        final Uri uri;
        final int stage;
        Received(String name,String path,Uri uri,int stage) {
            this.name=name;
            this.path=path;
            this.uri=uri;
            this.stage=stage;
        }
    }

    List<Received> received() {
        Work w = work != null ? work : lastWork;
        List<Received> files = new ArrayList<>();
        if (w != null) for (Clip c : w.clips) {
            int stage=stage(w,c);
            if (stage>0) files.add(new Received(c.name,storagePath(w,c),uri(w,c),stage));
        }
        return files;
    }
    boolean waitingForAccount() {
        Work w=work;
        return w!=null && (w.cleanup || nextUnverified()!=null) && !readyToInspect();
    }
    void inspectionHint(String hint) {
        if (!inspectionHint.equals(hint)) {
            inspectionHint=hint;
            changed.set(true);
        }
    }
    private String currentNote() {
        if (!network.isEmpty()) return network;
        if (work != null && nextUnverified()!=null && current.isEmpty()
                && unlocked(this) && !inspectionHint.isEmpty()) return inspectionHint;
        return message;
    }

    String connectionText() {
        if (lastConnected==0) return "PC 연결 대기";
        if (SystemClock.elapsedRealtime()-lastConnected>4_000L*interval) return "PC 응답 지연";
        return "PC 연결됨";
    }

    String counts() {
        JSONObject view=totals;
        int total=view.optInt("totalCount"), verifiedCount=view.optInt("verifiedCount");
        long copiedBytes=view.optLong("batchReceived"), batchBytes=view.optLong("batchBytes");
        int batchCount=view.optInt("batchCount"), copiedCount=view.optInt("batchCopied");
        Work w=work;
        if (w!=null && w.batch==view.optInt("batchNumber")) {
            int localCopied=0, localVerified=0;
            long localBytes=0;
            for (Clip c:w.clips) {
                int state=stage(w,c);
                if (state>=1) {localCopied++; localBytes+=c.size;}
                else if(c.id.equals(current)) localBytes+=Math.min(c.size,bytes);
                if (state>=2) localVerified++;
            }
            copiedBytes=Math.max(copiedBytes,localBytes);
            copiedCount=Math.max(copiedCount,localCopied);
            verifiedCount+=Math.max(0,localVerified-view.optInt("batchVerified"));
        }
        String bundle=batchCount>0 ? String.format(java.util.Locale.KOREA,"이번 묶음 전송 %,d / %,d개 · %.2f / %.2f GB",
                copiedCount,batchCount,copiedBytes/1e9,batchBytes/1e9) : "현재 묶음 없음";
        return bundle+"\n원본 백업 확인 "+Math.min(total,verifiedCount)+" / "+total+"개";
    }

    private void updateNotification() {
        if (running!=this) return;
        String value=counts();
        String note=currentNote();
        if (value.equals(notificationText)) return;
        notificationText=value;
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent target=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_IMMUTABLE);
        getSystemService(NotificationManager.class).notify(125,new Notification.Builder(this,"transfer")
                .setContentTitle("오토픽셀 · "+connectionText())
                .setContentText(value.replace("\n"," · ")).setStyle(new Notification.BigTextStyle().bigText(value+"\n"+note))
                .setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(target)
                .setOnlyAlertOnce(true).setOngoing(true).build());
    }

    String status() { return currentNote(); }

    String lastChecked() {
        Link active=link;
        long stamp=active==null?0:prefs.getLong(active.namespace+".lastVerified",0);
        stamp=Math.max(stamp, totals.optLong("lastVerified")*1000);
        return stamp>0 ? "마지막 백업 확인 "+android.text.format.DateFormat.getTimeFormat(this).format(new Date(stamp)) : "백업 확인 대기";
    }
    boolean readyToInspect() { return accountOK(work); }
    String expectedAccount() { Work w = work; return w == null ? "" : w.account; }
    void confirmAccount(String account) {
        Work w = work;
        if (w != null && w.account.equalsIgnoreCase(account)) {
            boolean first = !accountOK(w);
            confirmedAccount = w.account;
            accountUntil = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(30);
            if (first) setMessage("구글 포토 백업 계정 확인 · 영상별 원본 확인 중");
        }
    }
    void clearAccountProof() {
        confirmedAccount = "";
        accountUntil = 0;
    }
    private int stage(Work w, Clip c) { return prefs.getInt(w.key(c) + ".stage", 0); }
    private void stage(Work w, Clip c, int value) throws IOException {
        SharedPreferences.Editor editor=prefs.edit().putInt(w.key(c) + ".stage", value);
        if (value==2) editor.putLong(w.namespace+".lastVerified",System.currentTimeMillis());
        if (!editor.commit()) {
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
        return report.put("version",3).put("accessible",PhotosInspector.ready())
                .put("batch",w==null?0:w.batch).put("files",receipts)
                .put("current",current).put("bytes",bytes).put("free",free())
                .put("locked",!unlocked(this)).put("charging",battery != null
                        && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED,0) != 0)
                .put("message",currentNote());
    }
    private void checkIn() {
        Link activeLink = link;
        if (activeLink == null) return;
        long now = SystemClock.elapsedRealtime();
        boolean urgent=changed.getAndSet(false);
        if (now-last < (pcRunning?interval:Math.min(2,interval))*1000L && !urgent) return;
        last = now;
        if (!PhotosInspector.ready()) setMessage("접근성 꺼짐 · 픽셀에서 접근성 서비스를 켜 주세요.");
        try {
            Work previous = work;
            JSONObject response = activeLink.sync(makeReport(previous));
            if (activeLink != link) return;
            if (response.optInt("version")!=3) throw new IOException("윈도우 오토픽셀도 0.3으로 업데이트해 주세요.");
            interval = Math.max(5, Math.min(120,response.optInt("interval",15)));
            network = "";
            lastConnected=SystemClock.elapsedRealtime();
            pcRunning=response.optBoolean("running");
            JSONObject view=response.optJSONObject("progress");
            if (view!=null) totals=view;
            if (pcRunning && PhotosInspector.ready()) requestPhotos(activeLink,response.optString("startId"));
            if (response.isNull("active")) {
                work = null;
                if (!busy.get()) {
                    String reason=response.optString("message");
                    if (!PhotosInspector.ready()) setMessage("접근성 꺼짐 · 픽셀에서 접근성 서비스를 켜 주세요.");
                    else if (totals.optInt("totalCount")>0 && totals.optInt("verifiedCount")==totals.optInt("totalCount"))
                        setMessage("전체 백업 확인 완료");
                    else setMessage(reason.isEmpty() ? "PC에서 전송 시작을 기다리는 중" : reason);
                }
                return;
            }
            Work next = new Work(response,activeLink);
            if (previous==null || previous.batch!=next.batch) {
                inspectionHint="";
                current="";
                bytes=0;
            }
            work = next;
            lastWork=next;
            if (!PhotosInspector.ready()) return;
            if (!busy.compareAndSet(false,true)) return;
            ioThread.execute(() -> process(activeLink,next));
        } catch (Exception error) {
            network = "PC 통신 대기 · " + safeMessage(error);
        } finally {
            updateNotification();
        }
    }
    private void process(Link activeLink, Work w) {
        try {
            if (!PhotosInspector.ready()) return;
            if (w.cleanup) {
                if (!accountOK(w)) {
                    setMessage("정리 전 대기 · 구글 포토의 백업 설정에서 계정을 다시 확인해 주세요.");
                    return;
                }
                for (Clip c : w.clips) {
                    if (activeLink != link || !PhotosInspector.ready() || Thread.currentThread().isInterrupted()) return;
                    if (stage(w,c) == 3) continue;
                    if (stage(w,c) != 2) throw new IOException("기기에 확인 기록이 없어 정리를 보류했습니다.");
                    remove(w,c);
                }
                setMessage("픽셀 복사본 정리 완료");
                return;
            }
            boolean allVerified = true;
            for (Clip c : w.clips) {
                if (activeLink != link || !PhotosInspector.ready() || Thread.currentThread().isInterrupted()) return;
                if (stage(w,c) < 1) {
                    if (!pcRunning) {
                        setMessage("PC에서 전송 시작을 기다리는 중");
                        return;
                    }
                    setMessage("전송 중 · " + c.name);
                    copy(activeLink,w,c);
                }
                allVerified &= stage(w,c) >= 2;
            }
            if (allVerified) setMessage("묶음 백업 확인 완료 · 정리 대기");
            else if (!unlocked(this)) setMessage("화면 잠금 · 잠금 해제 후 백업 확인");
            else if (!accountOK(w)) setMessage("구글 포토 열기 · 백업 계정 확인 대기");
            else setMessage("수신 완료 · 구글 포토에서 영상별 원본 백업 확인 중");
        } catch (Exception error) {
            setMessage(safeMessage(error));
        } finally {
            busy.set(false);
        }
    }
    private boolean sameOwnedItem(Work w, Clip c, Uri target, boolean allowPending) {
        if (target == null || !target.equals(uri(w,c))) return false;
        try (Cursor cursor = getContentResolver().query(MediaStore.setIncludePending(target),
                new String[]{COL_NAME,COL_PATH,COL_PENDING},null,null,null)) {
            return cursor != null && cursor.moveToFirst() && c.name.equals(cursor.getString(0))
                    && storagePath(w,c).equals(cursor.getString(1)) && (allowPending || cursor.getInt(2) == 0);
        }
    }
    private Uri prepare(Work w, Clip c) throws IOException {
        Uri target = uri(w,c);
        if (target != null) {
            if (!sameOwnedItem(w,c,target,true)) throw new IOException("전송 기록의 영상 위치가 바뀌었습니다.");
            return target;
        }
        String path=storagePath(w,c);
        // Only this scoped-storage app’s own rows are visible without a media read permission.
        try (Cursor existing = getContentResolver().query(MediaStore.setIncludePending(VIDEOS),
                new String[]{MediaStore.MediaColumns._ID,COL_PENDING},
                COL_NAME + "=? AND " + COL_PATH + "=?",new String[]{c.name,path},null)) {
            if (existing != null && existing.moveToFirst()) {
                if (existing.getInt(1) != 1) throw new IOException("같은 이름의 기기 파일이 있어 전송을 보류했습니다.");
                target = ContentUris.withAppendedId(VIDEOS,existing.getLong(0));
                if (existing.moveToNext()) throw new IOException("같은 이름의 기기 파일이 여러 개입니다.");
            }
        }
        if (target == null) {
            ContentValues values = new ContentValues();
            values.put(COL_NAME,c.name);
            values.put(COL_PATH,path);
            String ext = c.name.substring(c.name.lastIndexOf('.')+1);
            String mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            values.put(MediaStore.MediaColumns.MIME_TYPE,mime != null && mime.startsWith("video/") ? mime : "video/*");
            values.put(COL_PENDING,1);
            target = getContentResolver().insert(VIDEOS,values);
            if (target == null) throw new IOException("픽셀에서 영상 파일을 만들 수 없습니다.");
        }
        if (!prefs.edit().putString(w.key(c)+".uri",target.toString()).putString(w.key(c)+".path",path).commit()) {
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
                            if (activeLink!=link || !pcRunning || !PhotosInspector.ready() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("전송 일시 중지");
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
