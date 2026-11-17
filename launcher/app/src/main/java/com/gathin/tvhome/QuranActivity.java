package com.gathin.tvhome;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class QuranActivity extends Activity {
    static class Reciter { final String name, query; String server = ""; int moshafId, readId;
        Reciter(String n, String q) { name = n; query = q; } }
    static class Surah { final int id; final String name; final boolean makkia;
        Surah(int i, String n, boolean m) { id = i; name = n; makkia = m; } }
    static class AyahTiming { final int ayah, start, end; String text;
        AyahTiming(int a, int s, int e, String t) { ayah = a; start = s; end = e; text = t; } }

    private final List<Reciter> reciters = new ArrayList<>();
    private final List<Surah> surahs = new ArrayList<>();
    private final List<AyahTiming> currentTimings = new ArrayList<>();
    private final Map<Integer, TextView> ayahViews = new HashMap<>();
    private int selectedReciterIdx = 0, highlightedAyah = -1;
    private TextView reciterSelectorBtn, statusLabel;
    private LinearLayout listContainer;
    private ScrollView mainScroll;
    private MediaPlayer mediaPlayer;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isReaderView = false;

    private final Runnable syncRunnable = new Runnable() {
        @Override
        public void run() {
            if (mediaPlayer != null && mediaPlayer.isPlaying() && isReaderView) {
                int pos = mediaPlayer.getCurrentPosition();
                for (AyahTiming t : currentTimings) {
                    if (pos >= t.start && pos <= t.end) { highlightAyah(t.ayah); break; }
                }
            }
            handler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        reciters.add(new Reciter("عبد الباسط عبد الصمد", "عبدالباسط عبدالصمد"));
        reciters.add(new Reciter("محمد صديق المنشاوي", "محمد صديق المنشاوي"));
        reciters.add(new Reciter("محمود خليل الحصري", "محمود خليل الحصري"));
        reciters.add(new Reciter("محمود علي البنا", "محمود علي البنا"));
        reciters.add(new Reciter("أحمد العجمي", "أحمد بن علي العجمي"));
        reciters.add(new Reciter("عبد الرحمن السديس", "عبدالرحمن السديس"));
        reciters.add(new Reciter("سعود الشريم", "سعود الشريم"));
        reciters.add(new Reciter("ماهر المعيقلي", "ماهر المعيقلي"));
        reciters.add(new Reciter("سعد الغامدي", "سعد الغامدي"));
        reciters.add(new Reciter("مشاري راشد العفاسي", "مشاري العفاسي"));
        reciters.add(new Reciter("فارس عباد", "فارس عباد"));
        buildUi();
        fetchInitialData();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0B1016"));
        root.setPadding(dp(48), dp(28), dp(48), dp(28));
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(0, 0, 0, dp(18));

        TextView title = new TextView(this);
        title.setText("القرآن الكريم");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setTypeface(null, Typeface.BOLD);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        reciterSelectorBtn = createPillButton("اختر القارئ: " + reciters.get(0).name, true);
        reciterSelectorBtn.setOnClickListener(v -> showReciterPicker());
        topBar.addView(reciterSelectorBtn);
        root.addView(topBar);

        statusLabel = new TextView(this);
        statusLabel.setText("جاري الاتصال بموقع MP3Quran وجلب السور...");
        statusLabel.setTextColor(Color.parseColor("#A0AEC0"));
        statusLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        statusLabel.setPadding(0, 0, 0, dp(12));
        root.addView(statusLabel);

        mainScroll = new ScrollView(this);
        mainScroll.setFillViewport(true);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        mainScroll.addView(listContainer);
        root.addView(mainScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        reciterSelectorBtn.requestFocus();
    }

    private TextView createPillButton(String text, boolean accent) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        btn.setPadding(dp(22), dp(12), dp(22), dp(12));
        btn.setFocusable(true);
        btn.setClickable(true);
        btn.setGravity(Gravity.CENTER);

        GradientDrawable normalBg = new GradientDrawable();
        normalBg.setCornerRadius(dp(14));
        normalBg.setColor(accent ? Color.parseColor("#134E4A") : Color.parseColor("#1A222D"));
        normalBg.setStroke(dp(1), Color.parseColor("#2D3748"));

        GradientDrawable focusedBg = new GradientDrawable();
        focusedBg.setCornerRadius(dp(14));
        focusedBg.setColor(Color.parseColor("#0D9488"));
        focusedBg.setStroke(dp(2), Color.parseColor("#FBBF24"));

        btn.setBackground(normalBg);
        btn.setOnFocusChangeListener((v, hasFocus) -> v.setBackground(hasFocus ? focusedBg : normalBg));
        return btn;
    }

    private void showReciterPicker() {
        isReaderView = false; stopAudio(); listContainer.removeAllViews();
        statusLabel.setText("اختر القارئ المفضل من القائمة:");
        for (int i = 0; i < reciters.size(); i++) {
            final int idx = i; Reciter r = reciters.get(i);
            TextView item = createListRow(toArabicDigits(i + 1) + ".  " + r.name + (i == selectedReciterIdx ? "  ✓" : ""), i == selectedReciterIdx ? "مختار حالياً" : "");
            item.setOnClickListener(v -> {
                selectedReciterIdx = idx;
                reciterSelectorBtn.setText("اختر القارئ: " + r.name);
                if (r.server.isEmpty()) resolveReciterServer(r, this::renderSurahList);
                else renderSurahList();
            });
            listContainer.addView(item);
            if (i == selectedReciterIdx) item.requestFocus();
        }
    }
    private void fetchInitialData() {
        executor.execute(() -> {
            try {
                JSONArray arr = new JSONObject(httpGet("https://mp3quran.net")).getJSONArray("suwar");
                surahs.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    surahs.add(new Surah(o.getInt("id"), o.getString("name").trim(), o.optInt("makkia", 1) == 1));
                }
                JSONArray rArr = new JSONObject(httpGet("https://mp3quran.net")).getJSONArray("reciters");
                for (Reciter target : reciters) matchReciterFromApi(target, rArr);
                handler.post(this::renderSurahList);
            } catch (Exception e) {
                handler.post(() -> statusLabel.setText("تعذر الاتصال بالإنترنت. تأكد من اتصال التلفاز بالشبكة."));
            }
        });
    }
    private void resolveReciterServer(Reciter target, Runnable onDone) {
        statusLabel.setText("جاري تجهيز تلاوات القارئ " + target.name + "...");
        executor.execute(() -> {
            try {
                JSONArray rArr = new JSONObject(httpGet("https://mp3quran.net")).getJSONArray("reciters");
                matchReciterFromApi(target, rArr);
            } catch (Exception ignored) {}
            handler.post(onDone);
        });
    }
    private void matchReciterFromApi(Reciter target, JSONArray rArr) {
        String key = target.query.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا");
        for (int i = 0; i < rArr.length(); i++) {
            JSONObject obj = rArr.optJSONObject(i);
            if (obj == null) continue;
            String apiName = obj.optString("name", "").replace("أ", "ا").replace("إ", "ا").replace("آ", "ا");
            if (apiName.contains(key) || key.contains(apiName)) {
                JSONArray moshafArr = obj.optJSONArray("moshaf");
                if (moshafArr != null && moshafArr.length() > 0) {
                    JSONObject chosen = moshafArr.optJSONObject(0);
                    for (int m = 0; m < moshafArr.length(); m++) {
                        JSONObject cand = moshafArr.optJSONObject(m);
                        if (cand != null && cand.optInt("surah_total", 0) == 114) { chosen = cand; break; }
                    }
                    if (chosen != null) {
                        target.server = chosen.optString("server", "");
                        target.moshafId = chosen.optInt("id", 0);
                        target.readId = obj.optInt("id", 0);
                        return;
                    }
                }
            }
        }
    }
    private void renderSurahList() {
        isReaderView = false; stopAudio(); listContainer.removeAllViews(); mainScroll.scrollTo(0, 0);
        Reciter active = reciters.get(selectedReciterIdx);
        statusLabel.setText("السور القرآنية (١ - ١١٤) • القارئ: " + active.name + " • اضغط على السورة للتلاوة والتظليل");
        for (Surah s : surahs) {
            TextView row = createListRow(toArabicDigits(s.id) + " - سورة " + s.name, s.makkia ? "(مكية)" : "(مدنية)");
            row.setOnClickListener(v -> openSurahPlayer(s));
            listContainer.addView(row);
        }
        if (listContainer.getChildCount() > 0) listContainer.getChildAt(0).requestFocus();
    }
    private TextView createListRow(String rightText, String leftBadge) {
        TextView tv = new TextView(this);
        tv.setText(leftBadge.isEmpty() ? rightText : rightText + "     " + leftBadge);
        tv.setTextColor(Color.WHITE); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        tv.setPadding(dp(24), dp(14), dp(24), dp(14));
        tv.setFocusable(true); tv.setClickable(true); tv.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(8)); tv.setLayoutParams(lp);
        GradientDrawable nBg = new GradientDrawable(); nBg.setCornerRadius(dp(12)); nBg.setColor(Color.parseColor("#141B24"));
        GradientDrawable fBg = new GradientDrawable(); fBg.setCornerRadius(dp(12)); fBg.setColor(Color.parseColor("#1E293B")); fBg.setStroke(dp(2), Color.parseColor("#FBBF24"));
        tv.setBackground(nBg);
        tv.setOnFocusChangeListener((v, hasFocus) -> tv.setBackground(hasFocus ? fBg : nBg));
        return tv;
    }

    private void openSurahPlayer(Surah surah) {
        isReaderView = true; highlightedAyah = -1; ayahViews.clear(); currentTimings.clear();
        listContainer.removeAllViews(); mainScroll.scrollTo(0, 0);
        Reciter active = reciters.get(selectedReciterIdx);
        String typeLabel = surah.makkia ? "مكية" : "مدنية";
        statusLabel.setText("سورة " + surah.name + " (" + typeLabel + ") • القارئ: " + active.name + " • جاري تحميل الآيات والتلاوة...");

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL); controls.setPadding(0, 0, 0, dp(16));
        TextView backBtn = createPillButton("← قائمة السور", false);
        backBtn.setOnClickListener(v -> renderSurahList());
        controls.addView(backBtn);

        TextView playPauseBtn = createPillButton("إيقاف مؤقت / استئناف", true);
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ppLp.setMargins(dp(14), 0, 0, 0);
        playPauseBtn.setOnClickListener(v -> { if (mediaPlayer != null) { if (mediaPlayer.isPlaying()) mediaPlayer.pause(); else mediaPlayer.start(); } });
        controls.addView(playPauseBtn, ppLp);
        listContainer.addView(controls);
        backBtn.requestFocus();

        executor.execute(() -> {
            try {
                List<AyahTiming> loaded = new ArrayList<>();
                try {
                    JSONArray tArr = new JSONArray(httpGet("https://mp3quran.net" + surah.id + "&read=" + active.readId));
                    for (int i = 0; i < tArr.length(); i++) {
                        JSONObject item = tArr.getJSONObject(i);
                        int ayah = item.optInt("ayah", i);
                        if (ayah > 0) loaded.add(new AyahTiming(ayah, item.optInt("start_time", 0), item.optInt("end_time", 0), item.optString("ayah_text", "").trim()));
                    }
                } catch (Exception ignored) {}

                if (loaded.isEmpty() || loaded.get(0).text.isEmpty()) {
                    JSONArray ayahsArr = new JSONObject(httpGet("https://alquran.cloud" + surah.id)).getJSONObject("data").getJSONArray("ayahs");
                    if (loaded.isEmpty()) {
                        for (int i = 0; i < ayahsArr.length(); i++) {
                            JSONObject a = ayahsArr.getJSONObject(i);
                            loaded.add(new AyahTiming(a.getInt("numberInSurah"), 0, 0, a.getString("text")));
                        }
                    } else {
                        for (int i = 0; i < Math.min(loaded.size(), ayahsArr.length()); i++) loaded.get(i).text = ayahsArr.getJSONObject(i).getString("text");
                    }
                }
                currentTimings.clear(); currentTimings.addAll(loaded);
                String paddedId = String.format(Locale.US, "%03d", surah.id);
                String audioUrl = active.server.endsWith("/") ? active.server + paddedId + ".mp3" : active.server + "/" + paddedId + ".mp3";
                handler.post(() -> {
                    statusLabel.setText("سورة " + surah.name + " (" + typeLabel + ") • القارئ: " + active.name);
                    for (AyahTiming a : currentTimings) {
                        TextView tv = new TextView(this);
                        tv.setText(a.text + "  ﴿" + toArabicDigits(a.ayah) + "﴾");
                        tv.setTextColor(Color.parseColor("#E2E8F0")); tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
                        tv.setLineSpacing(dp(8), 1.15f); tv.setPadding(dp(20), dp(14), dp(20), dp(14));
                        tv.setGravity(Gravity.RIGHT); tv.setFocusable(true);
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp.setMargins(0, 0, 0, dp(8)); tv.setLayoutParams(lp);
                        tv.setOnClickListener(v -> { if (mediaPlayer != null && a.start > 0) { mediaPlayer.seekTo(a.start); highlightAyah(a.ayah); } });
                        ayahViews.put(a.ayah, tv); listContainer.addView(tv);
                    }
                    startAudio(audioUrl);
                });
            } catch (Exception e) {
                handler.post(() -> Toast.makeText(this, "حدث خطأ أثناء تحميل السورة", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void highlightAyah(int ayahNum) {
        if (highlightedAyah == ayahNum) return;
        if (highlightedAyah != -1 && ayahViews.containsKey(highlightedAyah)) {
            TextView prev = ayahViews.get(highlightedAyah);
            if (prev != null) { prev.setBackgroundColor(Color.TRANSPARENT); prev.setTextColor(Color.parseColor("#E2E8F0")); }
        }
        highlightedAyah = ayahNum;
        TextView curr = ayahViews.get(ayahNum);
        if (curr != null) {
            GradientDrawable hl = new GradientDrawable(); hl.setCornerRadius(dp(12)); hl.setColor(Color.parseColor("#1E3A3A")); hl.setStroke(dp(2), Color.parseColor("#FBBF24"));
            curr.setBackground(hl); curr.setTextColor(Color.parseColor("#FDE68A"));
            mainScroll.smoothScrollTo(0, Math.max(0, curr.getTop() - dp(120)));
        }
    }

    private void startAudio(String url) {
        stopAudio();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).setUsage(AudioAttributes.USAGE_MEDIA).build());
            mediaPlayer.setDataSource(url);
            mediaPlayer.setOnPreparedListener(mp -> { mp.start(); handler.post(syncRunnable); });
            mediaPlayer.prepareAsync();
        } catch (Exception e) { Toast.makeText(this, "تعذر تشغيل الصوت", Toast.LENGTH_SHORT).show(); }
    }

    private void stopAudio() {
        handler.removeCallbacks(syncRunnable);
        if (mediaPlayer != null) { try { if (mediaPlayer.isPlaying()) mediaPlayer.stop(); mediaPlayer.release(); } catch (Exception ignored) {} mediaPlayer = null; }
    }

    private String httpGet(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET"); conn.setConnectTimeout(10000); conn.setReadTimeout(10000);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(); String line;
            while ((line = br.readLine()) != null) sb.append(line);
            return sb.toString();
        } finally { conn.disconnect(); }
    }

    private String toArabicDigits(int number) {
        char[] ar = {0x0660, 0x0661, 0x0662, 0x0663, 0x0664, 0x0665, 0x0666, 0x0667, 0x0668, 0x0669};
        String s = String.valueOf(number); StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); sb.append(c >= 48 && c <= 57 ? ar[c - 48] : c); }
        return sb.toString();
    }

    private int dp(int val) { return (int) (val * getResources().getDisplayMetrics().density); }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && isReaderView) { renderSurahList(); return true; }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() { stopAudio(); executor.shutdownNow(); super.onDestroy(); }
}
