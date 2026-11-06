package com.deepseekharness.app.module;

import android.content.Context;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一模块目录：把主页卡片（{@link ModuleCard}）与声明式清单（{@link ModuleManifest}）
 * 聚合成同一份可渲染、可启停、可卸载的列表。
 *
 * <p>两套契约仍然保留：卡片负责运行时小部件，清单负责页面/导航声明。这里不强行合并
 * 两种 JSON，而是统一「身份、来源、摘要、信任状态、开关、移除」这些宿主事实，避免
 * 同一个模块在模块中心出现两行、两套开关或两套错误。
 */
public final class ModuleCatalog {

  public enum Kind {
    CARD,
    MANIFEST
  }

  /** 一条统一模块。card / manifest 至少有一个非空。 */
  public static final class Entry {
    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String source;
    public final String digest;
    public final String trustStatus;
    public final String trustKeyId;
    public final String trustPublicKey;
    public final String trustMessage;
    public final Kind kind;
    public final boolean enabled;
    public final ModuleCard card;
    public final ModuleManifest manifest;

    Entry(
        String id,
        String name,
        String version,
        String author,
        String source,
        String digest,
        String trustStatus,
        String trustKeyId,
        String trustPublicKey,
        String trustMessage,
        Kind kind,
        boolean enabled,
        ModuleCard card,
        ModuleManifest manifest) {
      this.id = id;
      this.name = name;
      this.version = version;
      this.author = author;
      this.source = source;
      this.digest = digest;
      this.trustStatus = trustStatus;
      this.trustKeyId = trustKeyId == null ? "" : trustKeyId;
      this.trustPublicKey = trustPublicKey == null ? "" : trustPublicKey;
      this.trustMessage = trustMessage == null ? "" : trustMessage;
      this.kind = kind;
      this.enabled = enabled;
      this.card = card;
      this.manifest = manifest;
    }

    public boolean isCard() {
      return card != null;
    }

    public String identity() {
      return author + "/" + id + "@" + version;
    }

    public String digestShort() {
      return digest == null || digest.length() <= 12
          ? (digest == null ? "" : digest)
          : digest.substring(0, 12) + "…";
    }

    public String sourceLabel(boolean zh) {
      if (ModuleCard.SOURCE_CONTAINER.equals(source)) return zh ? "容器收取" : "container";
      if (ModuleCard.SOURCE_LOCAL.equals(source)) return zh ? "本地导入" : "local";
      if (ModuleCard.SOURCE_GALLERY.equals(source)) return zh ? "广场" : "gallery";
      if ("builtin".equals(source)) return zh ? "内置" : "builtin";
      if ("user".equals(source)) return zh ? "用户安装" : "user";
      return zh ? "来源未知" : "unknown";
    }

    public boolean verified() {
      return ModuleTrust.isVerified(trustStatus);
    }

    /** 一行给用户看的身份：作者/版本/来源/摘要/信任。 */
    public String identitySummary(boolean zh) {
      StringBuilder builder = new StringBuilder();
      builder.append(zh ? "作者 " : "author ").append(author);
      builder.append(" · v").append(version);
      builder.append(zh ? " · 来源 " : " · source ").append(sourceLabel(zh));
      builder.append(" · SHA-256 ").append(digestShort());
      builder.append(" · ").append(ModuleTrust.statusLabel(trustStatus, zh));
      return builder.toString();
    }

    /** 能力摘要：卡片看动作/脚本/执行/悬浮/主题；清单看类型/架构/页面/导航。 */
    public String capabilities(boolean zh) {
      List<String> caps = new ArrayList<>();
      if (card != null) {
        if (!card.bind.isEmpty()) caps.add(zh ? "读状态" : "live data");
        if (!card.fields.isEmpty()) caps.add(zh ? "输入" : "input");
        boolean script = false;
        boolean run = false;
        for (ModuleCard.Action action : card.actions) {
          if ("script".equals(action.type)) script = true;
          if ("exec".equals(action.type)) run = true;
          if ("sequence".equals(action.type)) {
            if (action.value.contains("script:")) script = true;
            if (action.value.contains("exec:")) run = true;
          }
        }
        if (card.overlay != null) caps.add(zh ? "悬浮层" : "overlay");
        if (card.theme != null) caps.add(zh ? "主题" : "theme");
        if (card.view != null) caps.add(zh ? "自绘界面" : "custom view");
        for (String surface : card.provides.keySet())
          if (!ModuleCard.KNOWN_SURFACES.contains(surface))
            caps.add((zh ? "未支持:" : "unsupported:") + surface);
        if (script) caps.add(zh ? "脚本" : "script");
        if (run) caps.add(zh ? "执行命令" : "shell");
        if (!card.image.isEmpty()) caps.add(zh ? "立绘" : "art");
        if (caps.isEmpty()) caps.add(zh ? "展示" : "display");
      }
      if (manifest != null) {
        caps.add(manifest.type().wireValue());
        if (manifest.architecture() != null) caps.add(manifest.architecture());
        if (!manifest.pages().isEmpty())
          caps.add((zh ? "页面 " : "pages ") + manifest.pages().size());
        if (!manifest.navigation().isEmpty())
          caps.add((zh ? "导航 " : "nav ") + manifest.navigation().size());
        for (String capability : manifest.capabilities()) caps.add(capability);
        if (caps.isEmpty()) caps.add(zh ? "声明" : "declaration");
      }
      return String.join(" · ", caps);
    }
  }

  public static final class Snapshot {
    private final List<Entry> entries;
    private final List<Issue> issues;

    Snapshot(List<Entry> entries, List<Issue> issues) {
      this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
      this.issues = Collections.unmodifiableList(new ArrayList<>(issues));
    }

    public List<Entry> entries() {
      return entries;
    }

    public List<Issue> issues() {
      return issues;
    }

    public Entry byId(String id, Kind kind) {
      for (Entry entry : entries) if (entry.id.equals(id) && entry.kind == kind) return entry;
      return null;
    }
  }

  /** 统一问题项；来源标识 + 人话。 */
  public static final class Issue {
    public final String source;
    public final String message;

    Issue(String source, String message) {
      this.source = source;
      this.message = message;
    }
  }

  private ModuleCatalog() {}

  public static Snapshot load(Context context) {
    List<Entry> entries = new ArrayList<>();
    List<Issue> issues = new ArrayList<>();
    ModuleRegistry.Snapshot manifests = ModuleRegistry.load(context);
    for (ModuleRegistry.Issue issue : manifests.issues())
      issues.add(new Issue(issue.source(), issue.message()));
    for (ModuleManifest manifest : manifests.modules().values()) {
      entries.add(
          new Entry(
              manifest.id(),
              manifest.name(),
              manifest.version(),
              manifest.author(),
              manifest.source(),
              manifest.digest(),
              ModuleTrust.statusOf(manifest),
              "",
              "",
              "清单没有签名机制",
              Kind.MANIFEST,
              manifests.isEnabled(manifest.id()),
              null,
              manifest));
    }
    for (ModuleCard card : ModuleCardStore.loadAll(context)) {
      ModuleSignature.Result signature = ModuleTrust.inspect(context, card);
      entries.add(
          new Entry(
              card.id,
              card.name,
              card.version,
              card.author,
              card.source,
              card.digest,
              ModuleTrust.statusOf(context, card),
              signature.keyId,
              signature.publicKey,
              signature.message,
              Kind.CARD,
              ModuleState.isEnabled(context, ModuleState.SCOPE_CARD, card.id),
              card,
              null));
    }
    for (ModuleCardStore.Issue issue : ModuleCardStore.issues())
      issues.add(new Issue(issue.where, issue.message));
    java.util.Map<String, Integer> seen = new java.util.LinkedHashMap<>();
    for (Entry entry : entries) {
      Integer count = seen.get(entry.id);
      seen.put(entry.id, count == null ? 1 : count + 1);
    }
    for (java.util.Map.Entry<String, Integer> pair : seen.entrySet()) {
      if (pair.getValue() > 1)
        issues.add(new Issue(pair.getKey(), "同一 id 同时存在卡片与清单，模块中心会分别显示；请确认这是你要的两个模块。"));
    }
    entries.sort(
        java.util.Comparator.comparing((Entry entry) -> entry.name)
            .thenComparing(entry -> entry.kind.name())
            .thenComparing(entry -> entry.id));
    return new Snapshot(entries, issues);
  }

  public static void setEnabled(Context context, Entry entry, boolean enabled) {
    if (entry == null) return;
    ModuleState.setEnabled(
        context,
        entry.kind == Kind.MANIFEST ? ModuleState.SCOPE_MANIFEST : ModuleState.SCOPE_CARD,
        entry.id,
        enabled);
    ModuleCardStore.bump();
  }

  /**
   * 移除一条统一模块。卡片走整目录事务清理；清单只删 module.json，不碰同目录卡片素材。
   * 移除同时撤销执行授权，避免留下指向不存在模块的授权。
   */
  public static boolean remove(Context context, Entry entry) {
    if (entry == null) return false;
    ModuleExec.revoke(context, entry.id);
    ModuleProgramAuth.revokeAll(context, entry.id);
    ModuleLifecycleAuth.revoke(context, entry.id);
    boolean removed;
    if (entry.card != null) {
      ModuleCardStore.remove(context, entry.id);
      removed = true;
    } else {
      try {
        removed = ModuleRegistry.uninstallUserManifest(context, entry.id);
      } catch (java.io.IOException error) {
        removed = false;
      }
    }
    // 只有确定没有另一条同 id 模块时才清状态；否则会把另一个模块的开关一起清掉。
    if (removed) {
      boolean otherKind =
          entry.card != null
              ? ModuleRegistry.load(context).modules().containsKey(entry.id)
              : ModuleCardStore.loadAll(context).stream()
                  .anyMatch(card -> card.id.equals(entry.id));
      if (!otherKind) ModuleState.clear(context, entry.id);
    }
    ModuleCardStore.bump();
    return removed;
  }
}
