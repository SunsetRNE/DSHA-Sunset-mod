package com.deepseekharness.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import com.deepseekharness.app.BuildConfig
import com.deepseekharness.app.R
import com.deepseekharness.app.core.DiagnosticLog
import com.deepseekharness.app.core.UpdateRepository
import com.deepseekharness.app.util.Constants
import com.deepseekharness.app.util.CurrentReleaseNotes
import com.deepseekharness.app.util.UiText
import com.deepseekharness.app.util.UpdateActionPolicy
import com.deepseekharness.app.util.UpdatePolicy
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** 更新由用户选择通道和确认安装；下载任务不依赖页面生命周期。 */
class UpdateActivity : AppCompatActivity() {
    private lateinit var repository: UpdateRepository
    private var installDispatchReady = false
    private var versionExpanded = false
    // 平板横屏右栏标签页；手机与平板竖屏为 null。
    private var paneTabs: RadioGroup? = null
    private var paneChosen = false
    private var selectingPane = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        setContentView(R.layout.activity_update)
        PressFeedback.attachButtons(findViewById<View>(android.R.id.content))
        repository = ViewModelProvider(this).get(UpdateRepository::class.java)
        // 旧保存态只用于提示中断，不能重建或授权一次安装请求。
        val wasInstallPermissionPending = saved != null && saved.getBoolean("resumeInstall")
        repository.restoreInterruptedInstall(
            (saved != null && saved.getBoolean("installPending")) || wasInstallPermissionPending
        )
        repository.restoreInterruptedDownloadPermission(
            saved != null && saved.getBoolean("downloadPermissionPending")
        )
        versionExpanded = saved != null && saved.getBoolean("versionExpanded")
        paneChosen = saved != null && saved.getBoolean("paneChosen")
        findViewById<TextView>(R.id.update_current).text = BuildConfig.VERSION_NAME
        findViewById<TextView>(R.id.update_code).text = BuildConfig.VERSION_CODE.toString()
        val buildLabel =
            getString(
                R.string.ui134_changelog_sub,
                CurrentReleaseNotes.versionLabel(BuildConfig.VERSION_NAME),
                BuildConfig.VERSION_CODE,
            )
        findViewById<TextView>(R.id.update_changelog_summary).text = buildLabel
        findViewById<TextView>(R.id.update_version_line).text = buildLabel
        findViewById<TextView>(R.id.update_edition).text = UiText.choose("标准版", "Standard")
        bindVersionDetails()
        val channels = findViewById<RadioGroup>(R.id.update_channels)
        channels.check(
            if (UpdatePolicy.PREVIEW == repository.channel()) R.id.update_preview
            else R.id.update_stable
        )
        // 切换通道即重新检查（UpdateEngine.setChannel）；检查中切换会作废旧检查，下载 / 校验时由 UpdateUi 锁定两段按钮。
        channels.setOnCheckedChangeListener { _, id ->
            repository.setChannel(
                if (id == R.id.update_preview) UpdatePolicy.PREVIEW else UpdatePolicy.STABLE
            )
        }
        findViewById<View>(R.id.update_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.update_dsh).text = Constants.DSH_VERSION
        findViewById<View>(R.id.update_runtime).setOnClickListener { showRuntimePlan() }
        findViewById<View>(R.id.update_runtime_rollback).setOnClickListener {
            RuntimeRecoveryUi.show(this)
        }

        paneTabs = findViewById(R.id.update_pane_tabs)
        if (paneTabs != null) bindPane(saved) else centerColumn()
        findViewById<View>(R.id.update_changelog).setOnClickListener {
            if (paneTabs != null) choosePane(R.id.update_pane_current)
            else CardSheet.show(this, getString(R.string.ui134_changelog), bundledChangelog())
        }
        findViewById<View>(R.id.update_release_detail).setOnClickListener {
            val state = repository.state().value
            if (state == null || state.release == null) return@setOnClickListener
            if (paneTabs != null) choosePane(R.id.update_pane_candidate)
            else showRelease(state.release)
        }
        findViewById<View>(R.id.update_check).setOnClickListener { repository.check() }
        val action = findViewById<UpdateProgressButton>(R.id.update_action)
        action.setOnClickListener {
            when (action.action().command) {
                UpdateActionPolicy.Command.CHECK -> repository.check()
                UpdateActionPolicy.Command.DOWNLOAD -> download()
                UpdateActionPolicy.Command.INSTALL -> install()
                else -> {}
            }
        }
        findViewById<View>(R.id.update_browser).setOnClickListener {
            val state = repository.state().value
            AboutDialog.openBrowser(
                this,
                if (state != null && state.release != null) state.release.pageUrl
                else Constants.REPOSITORY_URL + "/releases",
            )
        }
        repository.state().observe(this, ::renderState)
        repository.installation().observe(this) {
            renderState(repository.state().value)
            dispatchInstall()
        }
    }

    private fun showRuntimePlan() {
        val page =
            CardPage(
                this,
                getString(R.string.ui2_managed_update),
                UiText.choose(
                    "查看当前环境与包内运行组件。",
                    "Review the current environment and bundled components.",
                ),
            )
        val metadata = page.card()
        page.kv(metadata, "Ubuntu", bundledBase())
        page.kv(metadata, "DSH", Constants.DSH_VERSION)
        page.kv(
            metadata,
            UiText.choose("个人数据", "Personal data"),
            UiText.choose("保持原位", "Kept in place"),
        )
        metadata.addView(
            page.text(
                UiText.choose(
                    "检查后展示实际差异；确认更新时停止 Web 与终端，准备候选并完成隔离试运行。",
                    "Review actual differences first. Updating stops Web and terminals, prepares a candidate and verifies it in an isolated trial.",
                ),
                12,
                R.color.text_secondary,
            )
        )
        val dialog = CardSheet.create(this, page)
        page.button(page.footer, UiText.choose("检查更新计划", "Review update plan"), true) {
            dialog.dismiss()
            startActivity(Intent(this, ExtractActivity::class.java).putExtra("review_only", true))
        }
        page.button(page.footer, UiText.choose("取消", "Cancel"), false, dialog::dismiss)
        CardSheet.show(dialog, this)
    }

    private fun bundledBase(): String {
        try {
            assets.open("offline-rootfs.version").use { input ->
                val bytes = ByteArray(64)
                val count = input.read(bytes)
                return if (count > 0) {
                    String(bytes, 0, count, StandardCharsets.UTF_8).trim { it <= ' ' }
                } else {
                    "—"
                }
            }
        } catch (unavailable: IOException) {
            return "—"
        }
    }

    private fun bundledChangelog(): String {
        try {
            resources.openRawResource(R.raw.changelog).use { input ->
                ByteArrayOutputStream().use { output ->
                    val block = ByteArray(2048)
                    var count = input.read(block)
                    while (count != -1) {
                        if (output.size() + count > 16384) throw IOException("CHANGELOG_LIMIT")
                        output.write(block, 0, count)
                        count = input.read(block)
                    }
                    val notes = output.toString("UTF-8")
                    if (
                        !CurrentReleaseNotes.matches(
                            BuildConfig.VERSION_NAME,
                            BuildConfig.VERSION_CODE,
                            notes,
                        )
                    ) {
                        DiagnosticLog.record(
                            this,
                            "changelog",
                            "Bundled notes do not match the installed build",
                        )
                    }
                    return currentChangelog(notes)
                }
            }
        } catch (failure: IOException) {
            DiagnosticLog.record(this, "changelog", failure.toString())
            return currentChangelog(null)
        } catch (failure: Resources.NotFoundException) {
            DiagnosticLog.record(this, "changelog", failure.toString())
            return currentChangelog(null)
        }
    }

    private fun currentChangelog(notes: String?): String {
        return CurrentReleaseNotes.render(
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
            notes,
            UiText.language(),
        )
    }

    /** 版本一行可展开完整版本、版本码与 DSH；展开块里可复制。 */
    private fun bindVersionDetails() {
        val toggle = findViewById<View>(R.id.update_version_toggle)
        toggle.setOnClickListener {
            versionExpanded = !versionExpanded
            applyVersionExpanded()
        }
        toggle.accessibilityDelegate =
            object : View.AccessibilityDelegate() {
                override fun onInitializeAccessibilityNodeInfo(
                    host: View,
                    info: AccessibilityNodeInfo,
                ) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.addAction(
                        if (versionExpanded)
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        else AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND
                    )
                }

                override fun performAccessibilityAction(
                    host: View,
                    action: Int,
                    args: Bundle?,
                ): Boolean {
                    if (
                        action == AccessibilityNodeInfo.ACTION_EXPAND ||
                            action == AccessibilityNodeInfo.ACTION_COLLAPSE
                    ) {
                        host.performClick()
                        return true
                    }
                    return super.performAccessibilityAction(host, action, args)
                }
            }
        findViewById<View>(R.id.update_copy_version).setOnClickListener {
            val clipboard =
                getSystemService(ClipboardManager::class.java) ?: return@setOnClickListener
            clipboard.setPrimaryClip(
                ClipData.newPlainText(
                    "DSHA",
                    BuildConfig.VERSION_NAME +
                        " / build" +
                        BuildConfig.VERSION_CODE +
                        " / DSH " +
                        Constants.DSH_VERSION,
                )
            )
            Toast.makeText(this, UiText.text("已复制版本信息"), Toast.LENGTH_SHORT).show()
        }
        applyVersionExpanded()
    }

    private fun applyVersionExpanded() {
        findViewById<View>(R.id.update_version_details).visibility =
            if (versionExpanded) View.VISIBLE else View.GONE
        findViewById<View>(R.id.update_version_chevron).rotation = if (versionExpanded) 180f else 0f
    }

    /** 平板横屏右栏：候选版本 / 本版本两个标签页；有候选时默认看候选，用户切过之后不再自动切换。 */
    private fun bindPane(saved: Bundle?) {
        val current = bundledChangelog()
        val split = current.indexOf('\n')
        findViewById<TextView>(R.id.update_pane_current_title).text =
            if (split > 0) current.substring(0, split).trim { it <= ' ' }
            else current.trim { it <= ' ' }
        findViewById<TextView>(R.id.update_pane_current_notes).text =
            if (split > 0) current.substring(split + 1).trim { it <= ' ' } else ""
        val initial =
            if (saved != null) saved.getInt("pane", R.id.update_pane_candidate)
            else R.id.update_pane_candidate
        // 左栏两行与右栏内容对应：选中行用 activated 软底，卡片按圆角裁切。
        for (id in intArrayOf(R.id.update_release_detail, R.id.update_changelog)) {
            val row = findViewById<View>(id)
            row.setBackgroundResource(R.drawable.bg_update_pane_row)
            (row.parent as View).clipToOutline = true
        }
        paneTabs!!.check(initial)
        applyPane(initial)
        paneTabs!!.setOnCheckedChangeListener { _, id ->
            if (!selectingPane) paneChosen = true
            applyPane(id)
        }
    }

    private fun choosePane(id: Int) {
        paneChosen = true
        paneTabs!!.check(id)
    }

    private fun autoPane(id: Int) {
        if (paneChosen || paneTabs!!.checkedRadioButtonId == id) return
        selectingPane = true
        paneTabs!!.check(id)
        selectingPane = false
    }

    private fun applyPane(id: Int) {
        val candidate = id != R.id.update_pane_current
        findViewById<View>(R.id.update_pane_candidate_body).visibility =
            if (candidate) View.VISIBLE else View.GONE
        findViewById<View>(R.id.update_pane_current_body).visibility =
            if (candidate) View.GONE else View.VISIBLE
        findViewById<View>(R.id.update_release_detail).isActivated = candidate
        findViewById<View>(R.id.update_changelog).isActivated = !candidate
    }

    /** 平板竖屏（600–839dp）：单栏收成居中约 640dp，不随屏宽拉满。 */
    private fun centerColumn() {
        val scroll = findViewById<View>(R.id.update_scroll)
        val content = findViewById<View>(R.id.update_content)
        val base = content.paddingStart
        scroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val column = Math.round(640 * resources.displayMetrics.density)
            val side = Math.max(base, (v.width - column) / 2)
            if (side != content.paddingStart) {
                v.post {
                    content.setPaddingRelative(
                        side,
                        content.paddingTop,
                        side,
                        content.paddingBottom,
                    )
                }
            }
        }
    }

    private fun showRelease(release: UpdatePolicy.Release) {
        val notes = if (release.notes == null) "" else release.notes.trim { it <= ' ' }
        CardSheet.show(
            this,
            UpdateUi.releaseTitle(this, release),
            UpdateUi.releaseMeta(release) + (if (notes.isEmpty()) "" else "\n\n" + notes),
        )
    }

    private fun download() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED
        ) {
            val request = repository.prepareDownloadPermission()
            if (request < 0) return
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), request)
            } catch (error: Exception) {
                if (repository.discardDownloadPermission(request)) showPermissionError(error)
            }
        } else {
            repository.download()
        }
    }

    private fun renderState(state: UpdateRepository.State?) {
        if (state == null) return
        val install = repository.installation().value!!
        UpdateUi.render(
            findViewById<View>(android.R.id.content),
            state,
            install.pending(),
            install.verifying,
            install.error != null,
        )
        if (paneTabs != null) {
            autoPane(
                if (state.release != null) R.id.update_pane_candidate else R.id.update_pane_current
            )
        }
        if (install.pending()) {
            findViewById<TextView>(R.id.update_status).text =
                if (install.verifying) UiText.text("正在重新校验安装包…") else UiText.text("校验完成，返回此页面后继续安装")
        } else if (install.error != null) {
            findViewById<TextView>(R.id.update_status).text =
                UiText.format(
                    "%s\n%s",
                    UpdateUi.status(state.message),
                    UpdateUi.status(install.error),
                )
        }
    }

    private fun install() {
        if (repository.installationPending()) return
        try {
            if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
                val request = repository.prepareInstallPermission()
                if (request < 0) return
                try {
                    startActivityForResult(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + packageName),
                        ),
                        request,
                    )
                } catch (error: Exception) {
                    if (repository.discardInstallPermission(request)) showPermissionError(error)
                }
                return
            }
            repository.requestInstall()
        } catch (error: Exception) {
            showInstallError(error)
        }
    }

    private fun dispatchInstall() {
        if (
            !installDispatchReady ||
                isFinishing ||
                isDestroyed ||
                supportFragmentManager.isStateSaved
        )
            return
        val apk = repository.takeInstallReady() ?: return
        try {
            val uri = FileProvider.getUriForFile(this, packageName + ".updates", apk)
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (error: Exception) {
            showInstallError(error)
        }
    }

    private fun showInstallError(error: Exception) {
        DiagnosticLog.record(this, "APP_UPDATE_INSTALL", error.toString())
        repository.installFailed(
            UiText.choose(
                "无法打开系统安装器，请重试或在浏览器下载安装。",
                "Unable to open the system installer. Retry or download in the browser.",
            )
        )
        Toast.makeText(this, repository.installation().value!!.error, Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(
        request: Int,
        permissions: Array<out String>,
        results: IntArray,
    ) {
        super.onRequestPermissionsResult(request, permissions, results)
        if (isFinishing || isDestroyed) return
        val known =
            permissions.contentEquals(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) &&
                results.size == 1 &&
                (results[0] == PackageManager.PERMISSION_GRANTED ||
                    results[0] == PackageManager.PERMISSION_DENIED)
        // 通知拒绝仍可续接已点按的 FGS 下载；空/未知回执只退休原票，不推断许可或重放。
        if (known) repository.downloadAfterPermission(request)
        else repository.discardDownloadPermission(request)
    }

    override fun onSaveInstanceState(saved: Bundle) {
        saved.putBoolean("resumeInstall", repository.hasInstallPermissionRequest())
        saved.putBoolean("downloadPermissionPending", repository.hasDownloadPermissionRequest())
        saved.putBoolean("installPending", repository.installationPending())
        saved.putBoolean("versionExpanded", versionExpanded)
        saved.putBoolean("paneChosen", paneChosen)
        if (paneTabs != null) saved.putInt("pane", paneTabs!!.checkedRadioButtonId)
        super.onSaveInstanceState(saved)
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (isFinishing || isDestroyed) return
        try {
            // 设置页 RESULT_CANCELED 也可能已经授权，返回后实查；仍只消费这个请求号。
            val granted = Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls()
            if (repository.finishInstallPermission(request, granted)) {
                if (!granted)
                    Toast.makeText(this, UiText.text("未允许安装更新，可稍后重试"), Toast.LENGTH_SHORT).show()
            }
        } catch (error: Exception) {
            if (repository.discardInstallPermission(request)) {
                showPermissionError(error)
            }
        }
    }

    private fun showPermissionError(error: Exception) {
        DiagnosticLog.record(this, "APP_UPDATE_PERMISSION", error.toString())
        val reason =
            UiText.choose(
                "无法确认权限请求结果，请重新操作",
                "Unable to confirm the permission result. Try the action again.",
            )
        repository.installFailed(reason)
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    override fun onPostResume() {
        super.onPostResume()
        installDispatchReady = true
        dispatchInstall()
    }

    override fun onPause() {
        installDispatchReady = false
        super.onPause()
    }
}
