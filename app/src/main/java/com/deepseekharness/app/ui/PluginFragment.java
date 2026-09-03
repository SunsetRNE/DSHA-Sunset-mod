package com.deepseekharness.app.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.HarnessController;
import com.deepseekharness.app.util.BuiltinPlugins;
import com.deepseekharness.app.util.SensitiveData;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 插件页：两个页签 —— 「插件市场」与「插件管理」。
 *
 * <p><b>插件管理</b>：列出 dsh web profile 里可管理的 6 个插件（4 个内置 + 2 个官方核心），
 * 每个带开关，可自定义启用 / 禁用；改动写进 profile 的 dsh.profile.bundles（内置插件
 * 另写 node_modules/&lt;name&gt;.disabled 标记，注册流程会尊重它），重启 Web 后生效。
 * 内置插件不进市场 —— 它们随 APK/rootfs 自带，不是「从市场安装」的。
 *
 * <p><b>插件市场</b>：安装第三方插件的能力（GitHub 链接安装等）待回填，这里显示占位说明。
 * 打开本页会顺带触发一次内置插件注册（幂等），覆盖安装与全新安装都能看到真实状态。
 */
public class PluginFragment extends Fragment {

    /** 管理页插件：{名字, 一句话描述, 是否内置（有 /root/dsha-* 实体）}。 */
    private static final Object[][] MANAGED = {
            {"@deepseek-ai/dsh-base", "官方核心：dsh 基础服务、系统提示词与运行时", false},
            {"@deepseek-ai/dsh-web-app", "官方核心：Web UI 应用层（对话界面）", false},
            {"dsh-device-shell-guide", "设备引导：新会话自动注入 ADB/Shizuku 设备操作提示词", true},
            {"dsh-task-notifier", "任务通知：agent 每轮完成时经 3090 桥通知手机", true},
            {"dsh-status-overlay", "状态悬浮条：把 agent 输出与工具调用流到顶部悬浮条", true},
            {"dsh-web-mobile", "移动端适配：竖屏 / 触控优化 dsh WebUI", true},
    };

    private TextView statusText;
    private ProgressBar busy;
    private RecyclerView pluginList;
    private TextView btnMarket;
    private TextView btnInstalled;
    private final List<PluginItem> items = new ArrayList<>();
    private Adapter adapter;
    /** 当前是否停在「插件市场」页签：为真时列表显示为空，但 items 数据保留（切回管理页能直接恢复）。 */
    private boolean marketMode = false;

    private static class PluginItem {
        final String name;
        final String desc;
        final boolean builtin;
        boolean enabled;   // 是否在 dsh.profile.bundles
        boolean pending;   // 开关操作进行中（防重入/并发）
        String note = "";  // 实体缺失等附加说明

        PluginItem(String name, String desc, boolean builtin) {
            this.name = name;
            this.desc = desc;
            this.builtin = builtin;
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_plugins, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        statusText = view.findViewById(R.id.statusText);
        busy = view.findViewById(R.id.pluginBusy);
        pluginList = view.findViewById(R.id.pluginList);
        btnMarket = view.findViewById(R.id.btnMarket);
        btnInstalled = view.findViewById(R.id.btnInstalled);
        for (Object[] m : MANAGED) {
            items.add(new PluginItem((String) m[0], (String) m[1], (Boolean) m[2]));
        }
        adapter = new Adapter(items, this::onToggle, () -> marketMode);
        pluginList.setLayoutManager(new LinearLayoutManager(requireContext()));
        pluginList.setAdapter(adapter);

        btnMarket.setOnClickListener(x -> showMarket());
        btnInstalled.setOnClickListener(x -> refresh());
        view.findViewById(R.id.btnRefresh).setOnClickListener(x -> refresh());
        refresh();
    }

    /** 切到「插件市场」：安装第三方插件的能力待回填，先显示占位。
     *  注意：不能清空 items —— 否则切回「插件管理」时列表就空了。 */
    private void showMarket() {
        marketMode = true;
        btnMarket.setBackgroundResource(R.drawable.bg_tab_on);
        btnInstalled.setBackgroundResource(R.drawable.bg_tab);
        adapter.notifyDataSetChanged(); // marketMode=true → getItemCount()==0，列表空
        statusText.setText("插件市场待接回：当前版本暂不支持从市场安装新插件。\n"
                + "「插件管理」里是随 App 自带的插件（含开关）。");
    }

    /** 切到「插件管理」：触发注册（幂等）并刷新 6 个插件的真实状态。 */
    private void refresh() {
        marketMode = false;
        btnMarket.setBackgroundResource(R.drawable.bg_tab);
        btnInstalled.setBackgroundResource(R.drawable.bg_tab_on);
        statusText.setText("正在同步插件状态…");
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String summary;
            try {
                HarnessController c = new HarnessController(requireContext());
                if (!c.isEnvironmentReady()) {
                    summary = "环境未就绪：请先完成解压 / 安装";
                } else {
                    String reg = c.proot().registerBuiltinPlugins();
                    updateStates(c);
                    summary = summarize(reg);
                }
            } catch (Throwable e) {
                summary = "同步失败：" + SensitiveData.redact(String.valueOf(e));
            }
            final String s = summary;
            if (getActivity() == null || !isAdded()) return;
            getActivity().runOnUiThread(() -> {
                busy.setVisibility(View.GONE);
                statusText.setText(s);
                adapter.notifyDataSetChanged();
            });
        }, "plugin-refresh").start();
    }

    /** 从 rootfs 直读 web profile，逐插件标出启用状态。 */
    private void updateStates(HarnessController c) {
        File rootfs = c.proot().getRootfsDir();
        File manifest = new File(rootfs, "root/.dsh/profiles/web/package.json");
        String m = manifest.isFile() ? readSmall(manifest) : "";
        for (PluginItem it : items) {
            it.enabled = BuiltinPlugins.inBundlesSection(m, it.name);
            it.note = "";
            if (it.builtin) {
                // 实体在 /root/dsha-*；node_modules 链接指向容器内路径，Android 侧
                // 用 isSymbolicLink 识别链接本身
                boolean entity = new File(rootfs,
                        BuiltinPlugins.entityDir(it.name).substring(1) + "/package.json").isFile();
                if (!entity) {
                    it.note = "实体缺失（精简包？）";
                } else {
                    File nm = new File(rootfs, BuiltinPlugins.profileNodeModulesRel(it.name));
                    boolean linked = com.deepseekharness.app.util.Compat.isSymbolicLink(nm) || nm.exists();
                    if (it.enabled && !linked) it.note = "链接待修复（点 ↻）";
                }
            }
        }
    }

    /** 用户拨动开关：启用 / 禁用插件（官方核心禁用先确认）。 */
    private void onToggle(PluginItem it, boolean enable) {
        if (it.pending || it.enabled == enable) return;
        // 官方核心禁用会拖垮 Web，确认一次再动手
        if (!it.builtin && !enable) {
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle("禁用官方核心？")
                    .setMessage(it.name + " 是 dsh 的核心插件，禁用后 Web 可能无法正常启动。\n\n"
                            + "确定要禁用吗？（可在本页重新开启）")
                    .setPositiveButton("禁用", (d, w) -> applyToggle(it, false))
                    .setNegativeButton("取消", (d, w) -> {
                        it.enabled = true;
                        if (getActivity() != null) getActivity().runOnUiThread(adapter::notifyDataSetChanged);
                    })
                    .show();
            return;
        }
        applyToggle(it, enable);
    }

    private void applyToggle(PluginItem it, boolean enable) {
        it.pending = true;
        it.enabled = enable; // 立即乐观更新：重绑/重入时守卫（checked != enabled）直接拦住
        if (getActivity() != null) getActivity().runOnUiThread(adapter::notifyDataSetChanged);
        statusText.setText((enable ? "正在启用 " : "正在禁用 ") + it.name + "…");
        busy.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String out;
            try {
                out = new HarnessController(requireContext())
                        .proot().setPluginEnabled(it.name, enable);
            } catch (Throwable e) {
                out = "ERROR: " + SensitiveData.redact(String.valueOf(e));
            }
            final String r = out == null ? "" : out;
            if (getActivity() == null || !isAdded()) return;
            getActivity().runOnUiThread(() -> {
                busy.setVisibility(View.GONE);
                boolean ok = r.contains("BUILTIN_REGISTER_OK");
                it.pending = false;
                updateStates(new HarnessController(requireContext())); // 回读真实状态
                adapter.notifyDataSetChanged();
                if (ok) {
                    statusText.setText("已" + (enable ? "启用 " : "禁用 ") + it.name
                            + "，重启 Web 后生效（启动页点「重启」）");
                } else {
                    statusText.setText("操作失败：" + (r.isEmpty() ? "无输出" : r.trim()));
                    Toast.makeText(requireContext(), "插件状态未改变", Toast.LENGTH_SHORT).show();
                }
            });
        }, "plugin-toggle").start();
    }

    private String summarize(String reg) {
        int on = 0, off = 0, bad = 0;
        for (PluginItem it : items) {
            if (it.enabled) on++;
            else off++;
            if (it.note.startsWith("实体缺失")) bad++;
        }
        StringBuilder sb = new StringBuilder("管理 " + items.size() + " 个：启用 " + on
                + "，禁用 " + off + (bad > 0 ? "，实体缺失 " + bad : "") + "。");
        if (reg != null && reg.contains("BUILTIN_REGISTER_OK")) {
            sb.append("（注册已同步）");
        } else if (reg != null && reg.contains("BUILTIN_REGISTER_PARTIAL")) {
            sb.append("（部分待处理，见列表与 repair-builtin.log）");
        } else if (reg != null && reg.contains("FAIL")) {
            sb.append("注册失败，见 /root/.dsh/repair-builtin.log");
        }
        return sb.toString();
    }

    private static String readSmall(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(buf);
            return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8) : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<PluginItem> data;
        private final java.util.function.BiConsumer<PluginItem, Boolean> onToggle;
        private final java.util.function.BooleanSupplier market;

        Adapter(List<PluginItem> data,
                java.util.function.BiConsumer<PluginItem, Boolean> onToggle,
                java.util.function.BooleanSupplier market) {
            this.data = data;
            this.onToggle = onToggle;
            this.market = market;
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView name;
            final TextView state;
            final TextView desc;
            final View install;
            final Switch sw;

            Holder(View v) {
                super(v);
                name = v.findViewById(R.id.pluginName);
                state = v.findViewById(R.id.pluginStatus);
                desc = v.findViewById(R.id.pluginDesc);
                install = v.findViewById(R.id.pluginInstall);
                sw = v.findViewById(R.id.pluginSwitch);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_plugin, parent, false);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int pos) {
            PluginItem it = data.get(pos);
            h.name.setText(it.name);
            h.desc.setText(it.desc);
            h.install.setVisibility(View.GONE); // 管理页用开关，不提供按钮
            h.sw.setVisibility(View.VISIBLE);
            // 先摘监听再 setChecked：否则 notifyDataSetChanged 重绑时的程序性 setChecked
            // 会触发监听器造成「一次点击连环开关」的重入级联
            h.sw.setOnCheckedChangeListener(null);
            h.sw.setChecked(it.enabled);
            // 打开页面时直接定格在最终状态，不播「关→开」动画（用户拨动时仍有动画反馈）
            h.sw.jumpDrawablesToCurrentState();
            h.state.setText(it.note.isEmpty()
                    ? (it.enabled ? "已启用" : "已禁用")
                    : it.note);
            h.sw.setOnCheckedChangeListener((b, checked) -> {
                if (checked != it.enabled && !it.pending) onToggle.accept(it, checked);
            });
        }

        @Override
        public int getItemCount() {
            // 市场页签：显示空列表（占位说明在状态栏），但不动 items，切回管理页立即恢复
            return market.getAsBoolean() ? 0 : data.size();
        }
    }
}
