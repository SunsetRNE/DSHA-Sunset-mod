package com.deepseekharness.app.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.deepseekharness.app.R;
import com.deepseekharness.app.core.ConfigStore;
import com.deepseekharness.app.core.HarnessController;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主界面外壳：启动门禁 + 底部导航（启动 / 插件 / 设置 / 终端）+ 顶栏标题 + 关于入口。
 */
public class MainActivity extends AppCompatActivity {

    public static volatile MainActivity current;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = this;

        ConfigStore config = new ConfigStore(this);
        HarnessController controller = new HarnessController(this);
        boolean skipExtract = getIntent().getBooleanExtra("skip_extract", false);

        // 启动门禁：未欢迎 → Welcome；环境未解压 → Extract
        if (!config.isWelcomed()) {
            startActivity(new Intent(this, WelcomeActivity.class));
            finish();
            return;
        }
        if (!skipExtract && !controller.isEnvironmentReady()) {
            startActivity(new Intent(this, ExtractActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        TextView title = findViewById(R.id.app_title);
        findViewById(R.id.btn_about).setOnClickListener(v -> AboutDialog.show(this));

        BottomNavigationView nav = findViewById(R.id.bottom_nav);
        nav.setOnItemSelectedListener(item -> {
            Fragment f;
            int id = item.getItemId();
            if (id == R.id.nav_launch) {
                f = new LaunchFragment();
                title.setText(R.string.nav_launch);
            } else if (id == R.id.nav_plugins) {
                f = new PluginFragment();
                title.setText(R.string.nav_plugins);
            } else if (id == R.id.nav_settings) {
                f = new SettingsFragment();
                title.setText(R.string.nav_settings);
            } else {
                // 终端：默认挂真 PTY 页（vim/htop/tmux 能跑），可在 PTY 页切回简易版
                f = PtyTerminalFragment.preferred(this)
                        ? new PtyTerminalFragment() : new TerminalFragment();
                title.setText(R.string.nav_terminal);
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, f)
                    .commit();
            return true;
        });

        if (savedInstanceState == null) {
            nav.setSelectedItemId(R.id.nav_launch);
        }
    }

    @Override
    protected void onDestroy() {
        if (current == this) current = null;
        // 收掉 PTY 会话与简易 shell（防在容器里留孤儿 bash）
        try {
            PtyTerminalFragment.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            TerminalFragment.shutdownShell();
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    public static void start(Context ctx) {
        ctx.startActivity(new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
}
