package cn.aiglasses.companion;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.*;
import android.speech.tts.TextToSpeech;
import org.json.JSONObject;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.HttpsURLConnection;

/** Cloud-connected prototype. No camera mirroring, Bluetooth, or accessibility automation. */
public final class MainActivity extends Activity {
    private static final String BASE = "https://ai-glasses-backend-7bj3.onrender.com";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor();
    private final ExecutorService previews = Executors.newSingleThreadExecutor();
    private volatile boolean foreground;
    private volatile boolean previewEnabled;
    private volatile int generation;
    private final AtomicBoolean previewRunning = new AtomicBoolean(false);
    private boolean busy;
    private EditText agentToken, ocrToken;
    private TextView status, result, cameraStatus;
    private ImageView image;
    private Button start;
    private Spinner modes;
    private CheckBox consent, readAloud;
    private TextToSpeech speech;
    private boolean speechReady;
    private String lastAnswer = "";
    private String lastTask = "";
    private Bitmap currentBitmap;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 24, 24, 32);
        ScrollView scroll = new ScrollView(this); scroll.addView(layout); setContentView(scroll);
        label(layout, "AI 眼镜助手 · 云端联调版", 25);
        label(layout, "硬件直传云端，手机查看和触发识别。不镜像、不改变原有视频。", 16);
        image = new ImageView(this); image.setAdjustViewBounds(true);
        image.setContentDescription("眼镜上传的最新原图");
        layout.addView(image, new LinearLayout.LayoutParams(-1, 520));
        cameraStatus = label(layout, "尚未请求画面", 16);
        Button preview = button(layout, "开始 / 停止原图预览");
        preview.setOnClickListener(v -> {
            previewEnabled = !previewEnabled;
            if (previewEnabled) { previews.submit(this::previewLoop); }
        });
        button(layout, "检查服务与助手配置").setOnClickListener(v -> check());
        label(layout, "网站 Agent 访问令牌（VISION_API_TOKEN）", 16);
        agentToken = secret(layout);
        label(layout, "OCR 访问令牌（服务器配置后可留空）", 16);
        ocrToken = secret(layout);
        modes = new Spinner(this);
        modes.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"读文字（保留药品 fields）", "识别物品", "描述场景"}));
        layout.addView(modes);
        consent = new CheckBox(this);
        consent.setText("同意将当前照片交给 OCR / 云视觉服务处理，可能产生费用及服务端留存。");
        layout.addView(consent);
        readAloud = new CheckBox(this); readAloud.setText("成功后使用手机 TTS 播报（需已安装中文语音引擎）");
        layout.addView(readAloud);
        start = button(layout, "识别当前画面"); start.setOnClickListener(v -> analyze(false));
        button(layout, "查询上次 OCR 任务（不重复提交）").setOnClickListener(v -> analyze(true));
        button(layout, "停止等待与播报").setOnClickListener(v -> {
            generation++; busy = false; start.setEnabled(true);
            if (speech != null) speech.stop();
            status.setText("已停止本机等待；服务器任务可能仍运行，不会自动重新提交。");
        });
        button(layout, "重播上次答案").setOnClickListener(v -> say(lastAnswer));
        button(layout, "清空令牌").setOnClickListener(v -> {
            agentToken.setText(""); ocrToken.setText("");
        });
        status = label(layout, "等待操作。识别结果不能用于决定服药或保证通行安全。", 16);
        result = label(layout, "识别结果将在这里显示。", 18); result.setTextIsSelectable(true);
        label(layout, "当前版本不包含蓝牙控制、硬件按键触发、导航、手机自动操作或后台监控。", 14);
        speech = new TextToSpeech(this, code -> {
            if (code == TextToSpeech.SUCCESS) {
                int language = speech.setLanguage(Locale.SIMPLIFIED_CHINESE);
                speechReady = language >= TextToSpeech.LANG_AVAILABLE;
            }
        });
    }

    private TextView label(LinearLayout parent, String text, int size) {
        TextView view = new TextView(this); view.setText(text); view.setTextSize(size);
        view.setPadding(0, 12, 0, 12); parent.addView(view); return view;
    }
    private Button button(LinearLayout parent, String text) {
        Button b = new Button(this); b.setText(text); parent.addView(b); return b;
    }
    private EditText secret(LinearLayout parent) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field.setSingleLine(true); field.setSaveEnabled(false);
        field.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        parent.addView(field); return field;
    }
    private void say(String answer) {
        if (answer.isEmpty()) return;
        if (!speechReady) { status.setText("中文 TTS 不可用，请先配置手机语音引擎；仍可查看文字。"); return; }
        int end = Math.min(answer.length(), TextToSpeech.getMaxSpeechInputLength());
        speech.speak(answer.substring(0, end), TextToSpeech.QUEUE_FLUSH, null, "answer");
    }

    private static final class Reply {
        int code; byte[] bytes;
        JSONObject json() throws Exception { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
    }
    private Reply call(String method, String path, String token, String ocr, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(BASE + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(20000); connection.setReadTimeout(145000);
        connection.setRequestMethod(method);
        connection.setRequestProperty("Cache-Control", "no-store");
        if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
        if (!ocr.isEmpty()) connection.setRequestProperty("X-OCR-Token", ocr);
        try {
            if (body != null) {
                connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            Reply reply = new Reply(); reply.code = connection.getResponseCode();
            InputStream stream = reply.code < 400 ? connection.getInputStream() : connection.getErrorStream();
            try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                if (in != null) {
                    byte[] chunk = new byte[8192]; int count;
                    while ((count = in.read(chunk)) != -1) {
                        if (out.size() + count > 12 * 1024 * 1024) throw new IOException("响应超过大小限制");
                        out.write(chunk, 0, count);
                    }
                }
                reply.bytes = out.toByteArray();
            }
            if (reply.code >= 300 && reply.code != 304) {
                String message = "HTTP " + reply.code;
                try {
                    JSONObject error = reply.json().optJSONObject("error");
                    if (error != null) message += "：" + error.optString("message") + " [" + error.optString("code") + "]";
                } catch (Exception ignored) { }
                throw new IOException(message);
            }
            return reply;
        } finally { connection.disconnect(); }
    }

    private void check() {
        if (busy) return;
        final String token = agentToken.getText().toString().trim();
        final int id = ++generation;
        status.setText("正在检查……"); busy = true; start.setEnabled(false);
        work.submit(() -> {
            if (id != generation || !foreground) return;
            try {
                JSONObject health = call("GET", "/health", "", "", null).json();
                JSONObject camera = call("GET", "/camera/performance", "", "", null).json();
                JSONObject cap = call("GET", "/api/v1/assistant/capabilities", token, "", null).json();
                String text = "服务：" + health.optString("status") + "\n硬件连接：" + camera.optBoolean("ws_connected")
                    + "\n接收 FPS：" + camera.optDouble("receive_fps") + "\n视觉模型配置：" + cap.optBoolean("vision_configured")
                    + "\n服务端 OCR 令牌配置：" + cap.optBoolean("ocr_token_configured")
                    + "\n配置存在不代表模型账号可用或已完成效果验收。";
                show(id, text, "检查完成", "");
            } catch (Exception e) { fail(id, e); }
        });
    }

    private void analyze(boolean queryOnly) {
        if (busy) return;
        final String token = agentToken.getText().toString().trim();
        final String ocr = ocrToken.getText().toString().trim();
        if (token.isEmpty()) { status.setText("请填写网站 Agent 访问令牌。"); return; }
        if (!queryOnly && !consent.isChecked()) { status.setText("请先确认照片交给云服务处理。"); return; }
        if (queryOnly && lastTask.isEmpty()) { status.setText("本次会话没有可查询的 OCR 任务。"); return; }
        final String oldTask = lastTask;
        final String mode = new String[]{"read_text", "objects", "scene"}[modes.getSelectedItemPosition()];
        final int id = ++generation;
        busy = true; start.setEnabled(false); status.setText("正在识别，请勿重复提交……");
        work.submit(() -> {
            if (id != generation || !foreground) return;
            try {
                JSONObject response;
                if (queryOnly) response = call("GET", "/api/v1/assistant/ocr/" + oldTask, token, ocr, null).json();
                else response = call("POST", "/api/v1/assistant/analyze", token, ocr,
                        new JSONObject().put("mode", mode)).json();
                if (id != generation) return;
                String task = queryOnly ? oldTask : response.optString("task_id");
                if (queryOnly || mode.equals("read_text")) {
                    if (!task.matches("assistant-[0-9a-f]{32}")) throw new IOException("服务器未返回有效 OCR 任务号");
                    ui.post(() -> { if (id == generation) { lastTask = task; status.setText("OCR 任务：" + task); } });
                    JSONObject state = queryOnly ? response : response.getJSONObject("ocr");
                    long deadline = SystemClock.elapsedRealtime() + 180000;
                    while (!terminal(state.optString("status"))) {
                        if (id != generation || !foreground) return;
                        if (SystemClock.elapsedRealtime() >= deadline) throw new IOException("等待超时，请查询任务：" + task);
                        Thread.sleep(2000);
                        if (id != generation) return;
                        state = call("GET", "/api/v1/assistant/ocr/" + task, token, ocr, null).json();
                    }
                    response.put("ocr", state);
                    if (!state.optString("status").equals("succeeded")) {
                        show(id, response.toString(2), "OCR 未成功，请查看状态，不自动重新提交。", ""); return;
                    }
                    String answer = state.optString("answer");
                    if (answer.isEmpty() && state.optJSONObject("result") != null)
                        answer = state.getJSONObject("result").optString("answer");
                    show(id, response.toString(2), "OCR 完成，请对照实物核查。", answer);
                } else {
                    show(id, response.toString(2), "识别完成，请核对原图。", response.optString("answer"));
                }
            } catch (Exception e) { fail(id, e); }
        });
    }
    private boolean terminal(String state) {
        return state.equals("succeeded") || state.equals("failed") || state.equals("timed_out");
    }
    private void show(int id, String text, String message, String answer) {
        ui.post(() -> {
            if (id != generation || !foreground) return;
            busy = false; start.setEnabled(true); result.setText(text); status.setText(message);
            lastAnswer = answer;
            if (readAloud.isChecked()) say(answer);
        });
    }
    private void fail(int id, Exception error) {
        // No credentials, photos or upstream body logged; no automatic paid retries.
        String message = error instanceof IOException ? error.getMessage() : "网络或响应解析失败，请检查服务后重试。";
        show(id, "未获得可确认的结果。", "请求失败：" + message + "\n已知 OCR 任务：" + lastTask, "");
    }
    private void previewLoop() {
        if (!previewRunning.compareAndSet(false, true)) return;
        try {
        while (foreground && previewEnabled) {
            try {
                Reply frame = call("GET", "/camera/latest", "", "", null);
                Bitmap bitmap = frame.code == 200 ? BitmapFactory.decodeByteArray(frame.bytes, 0, frame.bytes.length) : null;
                ui.post(() -> {
                    if (!foreground || !previewEnabled) { if (bitmap != null) bitmap.recycle(); return; }
                    if (bitmap != null) {
                        image.setImageBitmap(bitmap);
                        currentBitmap = bitmap;
                        cameraStatus.setText("服务器缓存原图（可能是最后一帧，实时性请检查服务状态）");
                    } else cameraStatus.setText("暂无画面");
                });
            } catch (Exception e) {
                ui.post(() -> { if (foreground) cameraStatus.setText("画面获取失败，请检查网络与硬件"); });
            }
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
        }
        } finally { previewRunning.set(false); }
    }
    @Override protected void onResume() { super.onResume(); foreground = true; }
    @Override protected void onPause() {
        foreground = false; previewEnabled = false; generation++; busy = false; start.setEnabled(true);
        if (speech != null) speech.stop();
        status.setText("已暂停网络轮询。重新识别须手动操作；可查询本会话已知 OCR 任务。");
        super.onPause();
    }
    @Override protected void onDestroy() {
        generation++; work.shutdownNow(); previews.shutdownNow();
        if (speech != null) { speech.stop(); speech.shutdown(); }
        image.setImageDrawable(null); currentBitmap = null;
        agentToken.setText(""); ocrToken.setText("");
        super.onDestroy();
    }
}
