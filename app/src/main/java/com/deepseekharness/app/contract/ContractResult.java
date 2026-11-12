package com.deepseekharness.app.contract;

import java.util.regex.Pattern;

/** fail-closed 的契约操作结果；失败只带稳定机器码，不带自由文本。 */
public record ContractResult(boolean accepted, String code) {
  private static final Pattern VALID_CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");

  public ContractResult {
    if (code == null || !VALID_CODE.matcher(code).matches())
      throw new IllegalArgumentException("CONTRACT_RESULT_CODE_INVALID");
  }

  public static ContractResult ok() {
    return new ContractResult(true, ContractCodes.OK);
  }

  public static ContractResult rejected(String code) {
    return new ContractResult(false, code);
  }

  public boolean rejected() {
    return !accepted;
  }
}
