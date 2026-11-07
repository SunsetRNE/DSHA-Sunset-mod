package com.deepseekharness.app.module;

import android.app.Activity;
import android.content.Context;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.fragment.app.Fragment;

import java.util.Objects;
import java.util.function.Supplier;

/** 设置页原生入口的不可变描述，不持有 View、Context 或 Fragment 实例。 */
public final class UiModuleDescriptor {
  public enum DestinationType {
    FRAGMENT,
    ACTIVITY
  }

  private final String id;
  private final String title;
  private final @StringRes int titleRes;
  private final String subtitle;
  private final @DrawableRes int icon;
  private final DestinationType destinationType;
  private final Supplier<? extends Fragment> fragmentFactory;
  private final Class<? extends Activity> activityClass;
  private final boolean islandOnly;

  private UiModuleDescriptor(
      String id,
      String title,
      @StringRes int titleRes,
      String subtitle,
      @DrawableRes int icon,
      DestinationType destinationType,
      Supplier<? extends Fragment> fragmentFactory,
      Class<? extends Activity> activityClass,
      boolean islandOnly) {
    this.id = Objects.requireNonNull(id);
    this.title = title;
    this.titleRes = titleRes;
    this.subtitle = Objects.requireNonNull(subtitle);
    this.icon = icon;
    this.destinationType = Objects.requireNonNull(destinationType);
    this.fragmentFactory = fragmentFactory;
    this.activityClass = activityClass;
    this.islandOnly = islandOnly;
    if (title == null && titleRes == 0) throw new IllegalArgumentException("模块标题不能为空");
  }

  public static UiModuleDescriptor fragment(
      String id,
      String title,
      String subtitle,
      @DrawableRes int icon,
      Supplier<? extends Fragment> factory) {
    return fragment(id, title, 0, subtitle, icon, factory, false);
  }

  public static UiModuleDescriptor fragmentResourceTitle(
      String id,
      @StringRes int titleRes,
      String subtitle,
      @DrawableRes int icon,
      Supplier<? extends Fragment> factory) {
    return fragment(id, null, titleRes, subtitle, icon, factory, false);
  }

  public static UiModuleDescriptor fragment(
      String id,
      String title,
      String subtitle,
      @DrawableRes int icon,
      Supplier<? extends Fragment> factory,
      boolean islandOnly) {
    return fragment(id, title, 0, subtitle, icon, factory, islandOnly);
  }

  private static UiModuleDescriptor fragment(
      String id,
      String title,
      @StringRes int titleRes,
      String subtitle,
      @DrawableRes int icon,
      Supplier<? extends Fragment> factory,
      boolean islandOnly) {
    return new UiModuleDescriptor(
        id,
        title,
        titleRes,
        subtitle,
        icon,
        DestinationType.FRAGMENT,
        Objects.requireNonNull(factory),
        null,
        islandOnly);
  }

  public static UiModuleDescriptor activity(
      String id,
      String title,
      String subtitle,
      @DrawableRes int icon,
      Class<? extends Activity> activityClass) {
    return new UiModuleDescriptor(
        id,
        title,
        0,
        subtitle,
        icon,
        DestinationType.ACTIVITY,
        null,
        Objects.requireNonNull(activityClass),
        false);
  }

  public String id() {
    return id;
  }

  public String title(Context context) {
    return titleRes == 0 ? title : context.getString(titleRes);
  }

  public String subtitle() {
    return subtitle;
  }

  public @DrawableRes int icon() {
    return icon;
  }

  public DestinationType destinationType() {
    return destinationType;
  }

  public boolean islandOnly() {
    return islandOnly;
  }

  public Fragment newFragment() {
    if (fragmentFactory == null) throw new IllegalStateException("模块不是 Fragment 目标：" + id);
    return Objects.requireNonNull(fragmentFactory.get());
  }

  public Class<? extends Activity> activityClass() {
    if (activityClass == null) throw new IllegalStateException("模块不是 Activity 目标：" + id);
    return activityClass;
  }
}
