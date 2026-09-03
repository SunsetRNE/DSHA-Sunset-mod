package com.deepseekharness.app.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.Constants;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/**
 * 设置页：模块入口（安装/配置/数据与备份）+ 其他（更新/自检/重新解压/关于）。
 */
public class SettingsFragment extends Fragment {

    private final Handler main = new Handler(Looper.getMainLooper());

    private static final TabOption[] TAB_OPTIONS = {
            new TabOption("安装", "分步安装 rootfs / 工具 / Node / harness", InstallFragment::new),
            new TabOption("配置", "API key · 端口 · 行为", ConfigFragment::new),
            new TabOption("数据与备份", "备份恢复 · 保存位置 · 工作区", WorkspaceFragment::new),
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_settings, container, false);

        LinearLayout tabs = v.findViewById(R.id.settings_tabs);
        for (int i = 0; i < TAB_OPTIONS.length; i++) {
            if (i > 0) {
                View divider = new View(requireContext());
                divider.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 1));
                divider.setBackgroundColor(requireContext().getColor(R.color.line));
                tabs.addView(divider);
            }
            tabs.addView(buildRow(i));
        }

        String version = "unknown";
        try {
            version = requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        TextView ver = v.findViewById(R.id.settings_ver);
        ver.setText("DSHA v" + version + " · MIT License");
        TextView updateSub = v.findViewById(R.id.settings_update_sub);
        updateSub.setText("当前 v" + version + " · 从 GitHub Releases 检查");

        v.findViewById(R.id.settings_about).setOnClickListener(x -> AboutDialog.show(requireContext()));
        v.findViewById(R.id.settings_update).setOnClickListener(x -> checkUpdate());
        v.findViewById(R.id.settings_selftest).setOnClickListener(x -> runSelftest());
        v.findViewById(R.id.settings_reextract).setOnClickListener(x -> confirmReextract());

        return v;
    }

    private void confirmReextract() {
        new AlertDialog.Builder(requireContext())
                .setTitle("重新解压内置环境")
                .setMessage("用 APK 里自带的环境覆盖当前容器，约数分钟。\n\n"
                        + "会保留：配置、API Key（自动备份后还原）。\n"
                        + "会回到出厂状态：自己在容器里额外装的东西。\n\n"
                        + "适用场景：dsh 或 npm 不见了、环境怎么修都不对。")
                .setPositiveButton("重新解压", (d, w) -> {
                    try {
                        Intent i = new Intent(requireContext(), ExtractActivity.class);
                        i.putExtra("force_extract", true);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                        startActivity(i);
                    } catch (Throwable t) {
                        Toast.makeText(requireContext(), "打不开解压页：" + t.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private void runSelftest() {
        new Thread(() -> {
            HarnessController c = new HarnessController(requireContext());
            String out = c.smokeTest();
            main.post(() -> {
                if (!isAdded()) return;
                showSelfTestDialog(out == null ? "自检失败" : out);
            });
        }, "dsha-selftest").start();
    }

    private void showSelfTestDialog(final String report) {
        TextView body = new TextView(requireContext());
        body.setText(report);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTextColor(requireContext().getColor(R.color.text));
        body.setTextIsSelectable(true);
        body.setPadding(dp(16), dp(8), dp(16), dp(8));
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(body);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(420)));
        new AlertDialog.Builder(requireContext())
                .setTitle("自检结果")
                .setView(scroll)
                .setPositiveButton("复制", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager)
                            requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("DSHA 自检", report));
                        Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void checkUpdate() {
        final String cur = currentVersion();
        new Thread(() -> {
            String tag = fetchLatestRelease();
            main.post(() -> {
                if (!isAdded()) return;
                if (tag == null) {
                    Toast.makeText(requireContext(), "检查失败，请稍后再试", Toast.LENGTH_SHORT).show();
                } else if (!isNewer(tag, cur)) {
                    Toast.makeText(requireContext(), "当前 v" + cur + " 已是最新", Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(requireContext())
                            .setTitle("发现新版本 " + tag)
                            .setMessage("当前版本 v" + cur + "\n是否前往下载？")
                            .setPositiveButton("更新", (d, w) ->
                                    AboutDialog.openBrowser(requireContext(),
                                            "https://github.com/qiannianhuanxiang/DSHA/releases/latest"))
                            .setNegativeButton("取消", null)
                            .show();
                }
            });
        }, "check-update").start();
    }

    private String currentVersion() {
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private String fetchLatestRelease() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(
                    "https://api.github.com/repos/qiannianhuanxiang/DSHA/releases/latest").openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "DSHA");
            if (conn.getResponseCode() != 200) return null;
            BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            String body = sb.toString();
            int i = body.indexOf("\"tag_name\"");
            if (i < 0) return null;
            int c = body.indexOf('"', body.indexOf('"', i + 11) + 1);
            int e = body.indexOf('"', c + 1);
            return c >= 0 && e > c ? body.substring(c + 1, e) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private boolean isNewer(String tag, String cur) {
        if (tag == null || cur == null) return false;
        String t = tag.startsWith("v") ? tag.substring(1) : tag;
        String c = cur.startsWith("v") ? cur.substring(1) : cur;
        String[] ta = t.split("\\.");
        String[] ca = c.split("\\.");
        for (int i = 0; i < Math.max(ta.length, ca.length); i++) {
            int tn = i < ta.length ? safeInt(ta[i]) : 0;
            int cn = i < ca.length ? safeInt(ca[i]) : 0;
            if (tn != cn) return tn > cn;
        }
        return false;
    }

    private int safeInt(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    private LinearLayout buildRow(final int index) {
        TabOption opt = TAB_OPTIONS[index];
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(15), dp(15), dp(15), dp(15));
        // 用主题的 selectableItemBackground（Material ripple），不用 Holo 的黄色 list_selector
        TypedValue tv = new TypedValue();
        requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, tv, true);
        row.setBackgroundResource(tv.resourceId);

        LinearLayout body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(requireContext());
        title.setText(opt.title);
        title.setTextSize(14);
        title.setTextColor(requireContext().getColor(R.color.text));
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);

        TextView sub = new TextView(requireContext());
        sub.setText(opt.sub);
        sub.setTextSize(12);
        sub.setTextColor(requireContext().getColor(R.color.text_muted));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        sub.setLayoutParams(slp);

        body.addView(title);
        body.addView(sub);

        TextView chev = new TextView(requireContext());
        chev.setText("›");
        chev.setTextSize(18);
        chev.setTextColor(requireContext().getColor(R.color.text_muted));

        row.addView(body);
        row.addView(chev);
        row.setOnClickListener(v -> getParentFragmentManager().beginTransaction()
                .replace(R.id.fragment_container, opt.factory.get())
                .addToBackStack("settings")
                .commit());
        return row;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class TabOption {
        final String title;
        final String sub;
        final Supplier<Fragment> factory;

        TabOption(String title, String sub, Supplier<Fragment> factory) {
            this.title = title;
            this.sub = sub;
            this.factory = factory;
        }
    }
}
