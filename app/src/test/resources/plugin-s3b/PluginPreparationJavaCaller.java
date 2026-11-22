package com.deepseekharness.app.plugins;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/** 真实 javac 调用边界夹具；只由本地窄 JVM 驱动编译，不进入产品。 */
public final class PluginPreparationJavaCaller {
  public static void main(String[] args) {
    PluginPreparationSnapshot.FileFact apk =
        new PluginPreparationSnapshot.FileFact(
            PluginPreparationSnapshot.NodeKind.REGULAR,
            "DSHA_APK_GENERATION_V1\n369\n".getBytes(StandardCharsets.US_ASCII),
            null,
            true,
            false);
    PluginPreparationSnapshot.FileFact absent =
        new PluginPreparationSnapshot.FileFact(
            PluginPreparationSnapshot.NodeKind.MISSING, null, null, true, false);
    PluginPreparationSnapshot snapshot =
        new PluginPreparationSnapshot(
            "java-fixture",
            "web",
            true,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap(),
            "/root/.dsh/plugin-src",
            apk,
            absent);
    if (!"apk:369".equals(PluginPreparationSnapshot.generation(apk, absent))) {
      throw new AssertionError("Java generation 调用形状改变");
    }
    PluginLegacyReviewPlan legacy = PluginLegacyReviewPlan.create(snapshot);
    if (!"ok".equals(legacy.classify(Collections.emptyMap()).getStatus())) {
      throw new AssertionError("空 legacy 计划分类改变");
    }
    PluginBuiltinRegistrationPlan builtin = PluginBuiltinRegistrationPlan.create(snapshot);
    if (!builtin.getRequiresRuntimeModuleScan()
        || builtin.classify(Collections.emptyMap()).getClassification()
            != PluginBuiltinRegistrationPlan.Classification.UNKNOWN) {
      throw new AssertionError("计划不得冒充已完成或免扫描");
    }
    System.out.println("PASS S3B Java caller: static factories, facts, plan and unknown result");
  }
}
