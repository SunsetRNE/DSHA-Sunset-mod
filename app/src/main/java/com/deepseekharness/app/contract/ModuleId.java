package com.deepseekharness.app.contract;

import java.util.regex.Pattern;

/** 编译期模块身份；只增不复用，不从文件或网络读取。 */
public final class ModuleId {
  private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9._-]{0,63}");

  private final String value;

  public ModuleId(String value) {
    if (value == null || !VALID.matcher(value).matches())
      throw new IllegalArgumentException("MODULE_ID_INVALID");
    this.value = value;
  }

  public static ModuleId of(String value) {
    return new ModuleId(value);
  }

  public String value() {
    return value;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof ModuleId that && value.equals(that.value);
  }

  @Override
  public int hashCode() {
    return value.hashCode();
  }

  @Override
  public String toString() {
    return value;
  }
}
